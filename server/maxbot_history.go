package main

import (
	"context"
	"log"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"time"
)

// Догон пропущенных сообщений.
//
// Длинный опрос надёжен, пока сервис жив: MAX держит очередь и отдаёт всё,
// что накопилось. Но связь рвётся, сервер перезапускается при обновлении, и
// сообщение может проскочить мимо. Продавец узнаёт об этом хуже всего —
// заказ просто не появился.
//
// Поэтому раз в десять минут (и сразу при запуске) бот сам перечитывает
// последние сообщения каждого своего чата и разбирает те, которых ещё не
// видел. Отметка о разобранном лежит в bot_seen, поэтому повторной заявки
// не будет: сравниваем по id сообщения, а не по тексту.
//
// В переписку догон не вмешивается совсем: он только заводит заявки.

const (
	// keyCatchUpAt — до какого времени история уже разобрана.
	keyCatchUpAt = "bot.catchUpAt"
	// catchUpEvery — как часто перечитывать чаты.
	catchUpEvery = 10 * time.Minute
	// catchUpDepth — на сколько назад заглядываем максимум. Дальше суток
	// не ходим: те заказы продавец уже разобрал руками.
	catchUpDepth = 24 * time.Hour
	// catchUpFirst — окно самого первого догона, когда отметки ещё нет.
	// Короткое нарочно: поднимать вчерашнюю переписку при первом запуске
	// значило бы завалить приложение заявками, которые давно закрыты.
	catchUpFirst = 30 * time.Minute
	// catchUpMessages — сколько сообщений просить у MAX за раз.
	catchUpMessages = 100
)

// chatHistory — последние сообщения чата, как их помнит MAX.
func (m *maxAPI) chatHistory(ctx context.Context, chatID int64, count int) ([]botMessage, error) {
	var out struct {
		Messages []botMessage `json:"messages"`
	}
	q := url.Values{
		"chat_id": {strconv.FormatInt(chatID, 10)},
		"count":   {strconv.Itoa(count)},
	}
	err := m.call(ctx, http.MethodGet, "/messages", q, nil, &out)
	return out.Messages, err
}

// chatAdmins — администраторы чата: для нас это продавцы.
func (m *maxAPI) chatAdmins(ctx context.Context, chatID int64) ([]botUser, error) {
	var out struct {
		Members []botUser `json:"members"`
	}
	err := m.call(ctx, http.MethodGet,
		"/chats/"+strconv.FormatInt(chatID, 10)+"/members/admins", nil, nil, &out)
	return out.Members, err
}

// refreshStaff обновляет список продавцов — администраторов рабочей группы.
// Их сообщения не превращаются в заявки без телефона: объявление «привезли
// свежую партию» это не заказ.
func refreshStaff(ctx context.Context, api *maxAPI, chatID int64) {
	if chatID == 0 {
		return
	}
	reqCtx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()

	members, err := api.chatAdmins(reqCtx, chatID)
	if err != nil {
		// Бота могли не сделать администратором — тогда список не отдадут.
		// Не беда: перехват просто останется без этой подсказки.
		log.Printf("бот MAX: администраторы группы недоступны: %v", err)
		return
	}
	ids := make([]int64, 0, len(members))
	for _, m := range members {
		ids = append(ids, m.UserID)
	}
	setChatStaff(ids)
}

// catchUpHistory перечитывает чаты и заводит заявки по пропущенным сообщениям.
func catchUpHistory(ctx context.Context, api *maxAPI) {
	defer func() {
		// Кривое сообщение не должно ронять опрос: он зовёт догон напрямую.
		if rec := recover(); rec != nil {
			log.Printf("бот MAX: сбой при догоне истории: %v", rec)
		}
	}()
	if !catchOn.Load() {
		return
	}
	since := catchUpSince()

	chats, err := chatsWithRetry(ctx, api)
	if err != nil {
		// Список чатов иногда не приходит — MAX отвечает за сотые доли
		// секунды, но раз в сотню-другую запросов запрос просто виснет.
		// Шуметь в журнале из-за этого не нужно: отметку мы не двигаем,
		// и через десять минут догон пройдёт по тому же окну заново.
		catchUpFailed(err)
		return
	}
	catchUpWorked()

	handled := 0
	// missed — хоть один чат не отдал историю. Тогда отметку «разобрано до»
	// не двигаем: иначе пропущенные в этом чате сообщения остались бы
	// позади окна навсегда, и заявки по ним не появились бы никогда.
	missed := false
	for _, ch := range chats {
		reqCtx, cancel := context.WithTimeout(ctx, 20*time.Second)
		msgs, err := api.chatHistory(reqCtx, ch.ChatID, catchUpMessages)
		cancel()
		if err != nil {
			log.Printf("бот MAX: история чата %d недоступна: %v", ch.ChatID, err)
			missed = true
			continue
		}
		// MAX отдаёт свежие сверху, а разбирать нужно по порядку: первое
		// сообщение человека заводит заявку, следующие дописываются в неё.
		sort.Slice(msgs, func(i, j int) bool { return msgs[i].Timestamp < msgs[j].Timestamp })
		for i := range msgs {
			if msgs[i].Timestamp <= since {
				continue
			}
			l, mid := leadFromMessage(&msgs[i])
			if !markSeen(mid) {
				continue // это сообщение опрос уже разобрал
			}
			catchLead(l)
			handled++
		}
	}

	// Двигаем окно только когда прочитали всё. Повторный проход по тому же
	// окну ничего не задваивает — сообщения отмечены в bot_seen, — а вот
	// сдвиг поверх непрочитанного чата терял бы заказы молча.
	if !missed {
		setSetting(keyCatchUpAt, strconv.FormatInt(nowMs(), 10))
	}
	if handled > 0 {
		log.Printf("бот MAX: догон истории — разобрано пропущенных сообщений: %d", handled)
	}
}

// chatsWithRetry просит список чатов, при неудаче — ещё раз.
//
// Обычный ответ приходит за сотые доли секунды, поэтому зависший запрос —
// это почти всегда разовая заминка связи, и вторая попытка проходит сразу.
func chatsWithRetry(ctx context.Context, api *maxAPI) ([]botChat, error) {
	for attempt := 0; ; attempt++ {
		reqCtx, cancel := context.WithTimeout(ctx, 20*time.Second)
		chats, err := api.chats(reqCtx)
		cancel()
		if err == nil || attempt == 1 || ctx.Err() != nil {
			return chats, err
		}
	}
}

// Заминки связи считаем и жалуемся только на настоящую поломку: одна
// неудача раз в несколько часов — это нормальная жизнь сети, а строка в
// журнале про «не вышло получить список чатов» выглядит как авария и
// заставляет искать несуществующую причину.
var catchUpFails int

// catchUpFailsToLog — сколько неудач подряд считаем поломкой.
const catchUpFailsToLog = 3

func catchUpFailed(err error) {
	catchUpFails++
	if catchUpFails == catchUpFailsToLog {
		log.Printf("бот MAX: список чатов для догона не приходит %d раза подряд: %v",
			catchUpFails, err)
	}
}

func catchUpWorked() {
	if catchUpFails >= catchUpFailsToLog {
		log.Printf("бот MAX: список чатов для догона снова приходит")
	}
	catchUpFails = 0
}

// catchUpSince — с какого момента перечитывать историю.
func catchUpSince() int64 {
	now := nowMs()
	deepest := now - catchUpDepth.Milliseconds()
	saved, err := strconv.ParseInt(siteSetting(keyCatchUpAt), 10, 64)
	if err != nil || saved <= 0 {
		return now - catchUpFirst.Milliseconds()
	}
	if saved < deepest {
		return deepest
	}
	return saved
}
