package main

import (
	"context"
	"encoding/json"
	"log"
	"net/http"
	"net/url"
	"strings"
	"sync/atomic"
)

// Защита группы от спама.
//
// В большую группу («Домашние заготовки», больше пятисот человек) регулярно
// приходят с заработком, ставками и «пишите в личку». Продавец удаляет это
// руками и не всегда быстро — а видят все.
//
// Бот читает сообщения группы и удаляет спам сам. Работает только там, где
// это безопасно, и очень осторожно:
//
//   - только в группах: в личной переписке удалять чужое незачем;
//   - только при включённом переключателе: по умолчанию выключено, потому
//     что удаление не отменить;
//   - никогда не трогает продавцов и администраторов группы;
//   - только когда модель уверена: порог выше, чем у разбора заказов;
//   - модель недоступна — не удаляем ничего. Здесь «не знаю» означает
//     «не трогай», а не «решай по словам»: удалить чужое сообщение по
//     совпадению слова — хуже, чем пропустить спам.
//
// Что удалено, остаётся в журнале сервера целиком: текст, автор и причина.
// Иначе сообщение исчезало бы бесследно, и спорить было бы не с чем.

// spamOn — включена ли защита. Настройка живёт рядом с остальными
// настройками бота и меняется на лету, с экрана «Бот MAX».
var spamOn atomic.Bool

func setAntiSpam(v bool) { spamOn.Store(v) }

// spamSure — насколько модель должна быть уверена, чтобы сообщение удалить.
// Выше, чем aiSureNo у разбора заказов: там ошибка стоит лишней заявки,
// здесь — стёртого сообщения живого человека.
const spamSure = 0.85

// spamPrompt — задание модели. Отдельным запросом, а не вместе с разбором
// заказа: смешивать «это заказ?» и «это спам?» в одном вопросе — значит
// получить оба ответа хуже, а удалять сообщения по мутному ответу нельзя.
const spamPrompt = `Ты модератор группы интернет-магазина. Реши, спам ли это сообщение.
Спам: предложения заработка и подработки, инвестиции, криптовалюта, ставки, казино, займы; реклама чужих товаров и услуг; призывы перейти по ссылке, написать в личку или в другой мессенджер за заработком; раздачи, розыгрыши, «пишите плюс»; вербовка и объявления о найме.
НЕ спам: всё, что касается магазина и его товаров — заказы, вопросы о цене и наличии, отзывы, благодарности, жалобы, обсуждение товаров, разговоры участников между собой, фотографии товаров. Объявления самого магазина — тоже не спам.
Ответь ТОЛЬКО JSON без пояснений:
{"spam": true|false, "confidence": 0.0-1.0, "why": "коротко, почему"}
Сомневаешься — ставь spam=false: удалённое сообщение вернуть нельзя.`

// spamVerdict — что модель думает о сообщении.
type spamVerdict struct {
	Spam       bool    `json:"spam"`
	Confidence float64 `json:"confidence"`
	Why        string  `json:"why"`
}

// deleteMessage удаляет сообщение в MAX по его id.
func (m *maxAPI) deleteMessage(ctx context.Context, mid string) error {
	return m.call(ctx, http.MethodDelete, "/messages",
		url.Values{"message_id": {mid}}, nil, nil)
}

// checkSpam удаляет сообщение, если это спам. Возвращает true, если удалил:
// тогда разбирать его дальше не нужно — заявки из спама не бывает.
func checkSpam(ctx context.Context, api *maxAPI, l lead, mid string) bool {
	if !spamOn.Load() || strings.TrimSpace(mid) == "" {
		return false
	}
	// Своих не трогаем никогда: ни бота, ни продавцов, ни администраторов
	// группы. Объявление «привезли свежую партию» — не спам, даже если
	// выглядит как реклама.
	if l.UserID == 0 || l.UserID == botSelfID() || isChatStaff(l.UserID) {
		return false
	}

	v, ok := askSpam(l.Text)
	if !ok || !v.Spam || v.Confidence < spamSure {
		return false
	}

	// Пишем в журнал до удаления и целиком: после удаления сообщения нет
	// уже нигде, и посмотреть, за что его стёрли, будет негде.
	log.Printf("бот MAX: удаляю спам от %s (%s, уверенность %.2f): %q",
		l.Name, v.Why, v.Confidence, oneLine(l.Text))

	if err := api.deleteMessage(ctx, mid); err != nil {
		// Чаще всего это значит, что бота не сделали администратором
		// группы: чужие сообщения удаляет только админ.
		log.Printf("бот MAX: не вышло удалить спам (бот — администратор группы?): %v",
			err)
		return false
	}
	return true
}

// askSpam спрашивает модель. Как и везде: второе значение — ответила ли она.
// Не ответила — ничего не удаляем.
func askSpam(text string) (spamVerdict, bool) {
	text = strings.TrimSpace(text)
	if !aiReady() || aiPaused() || len([]rune(text)) < 4 {
		return spamVerdict{}, false
	}
	if r := []rune(text); len(r) > aiMaxText {
		text = string(r[:aiMaxText])
	}

	ctx, cancel := context.WithTimeout(context.Background(), aiTimeout)
	defer cancel()

	raw, err := aiChat(ctx, spamPrompt, text, true)
	if err != nil && strings.Contains(strings.ToLower(err.Error()), "think") {
		raw, err = aiChat(ctx, spamPrompt, text, false)
	}
	if err != nil {
		aiFailed(err)
		return spamVerdict{}, false
	}
	aiWorked()

	var v spamVerdict
	if err := json.Unmarshal([]byte(jsonObject(raw)), &v); err != nil {
		return spamVerdict{}, false
	}
	v.Why = oneLine(v.Why)
	return v, true
}
