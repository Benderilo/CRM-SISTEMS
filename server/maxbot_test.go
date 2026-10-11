package main

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"
)

// fakeMax — подставной botapi.max.ru: запоминает отправленные сообщения,
// чтобы проверить, что именно бот пишет покупателю.
type fakeMax struct {
	mu        sync.Mutex
	messages  []fakeMessage
	server    *httptest.Server
	lastAuth  string
	lastQuery string
	// history — что MAX отдаст на GET /messages: так проверяем догон
	// пропущенного. admins — кого он считает продавцами.
	history []botMessage
	admins  []int64
	// failMessages — MAX не отдаёт историю чата; chatsFail — сколько раз
	// подряд не отдать список чатов. Нужны для проверки догона: заминки
	// связи там не редкость, и вести себя при них нужно правильно.
	failMessages bool
	chatsFail    int
	chatsCalls   int
	// deleted — id сообщений, которые бот попросил удалить: по ним
	// проверяем защиту от спама.
	deleted []string
}

type fakeMessage struct {
	Target  string // «user:123» или «chat:456»
	Text    string
	Photo   string      // адрес картинки, если она приложена
	Buttons []string    // подписи кнопок в порядке появления
	Colors  []botButton // кнопки целиком — по ним проверяем цвета
}

func newFakeMax(t *testing.T) *fakeMax {
	t.Helper()
	f := &fakeMax{}
	f.server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		f.lastAuth = r.Header.Get("Authorization")
		f.lastQuery = r.URL.RawQuery
		f.mu.Unlock()
		switch r.URL.Path {
		case "/me":
			json.NewEncoder(w).Encode(map[string]any{
				"user_id": 7, "name": "Магазин", "username": "shopbot"})
		case "/chats":
			f.mu.Lock()
			f.chatsCalls++
			fail := f.chatsFail > 0
			if fail {
				f.chatsFail--
			}
			f.mu.Unlock()
			if fail {
				w.WriteHeader(http.StatusServiceUnavailable)
				return
			}
			json.NewEncoder(w).Encode(map[string]any{"chats": []map[string]any{
				{"chat_id": fakeChat, "type": "chat", "title": "Группа"},
			}})
		case "/chats/99/members/admins":
			f.mu.Lock()
			ids := f.admins
			f.mu.Unlock()
			members := make([]map[string]any, 0, len(ids))
			for _, id := range ids {
				members = append(members, map[string]any{"user_id": id, "name": "Продавец"})
			}
			json.NewEncoder(w).Encode(map[string]any{"members": members})
		case "/messages":
			// GET — история чата, POST — отправка сообщения.
			if r.Method == http.MethodGet {
				f.mu.Lock()
				hist := f.history
				fail := f.failMessages
				f.mu.Unlock()
				if fail {
					w.WriteHeader(http.StatusServiceUnavailable)
					return
				}
				json.NewEncoder(w).Encode(map[string]any{"messages": hist})
				return
			}
			if r.Method == http.MethodDelete {
				f.mu.Lock()
				f.deleted = append(f.deleted, r.URL.Query().Get("message_id"))
				f.mu.Unlock()
				json.NewEncoder(w).Encode(map[string]any{"success": true})
				return
			}
			f.record(r)
			json.NewEncoder(w).Encode(map[string]any{"message": map[string]any{}})
		case "/answers":
			json.NewEncoder(w).Encode(map[string]any{"success": true})
		default:
			json.NewEncoder(w).Encode(map[string]any{})
		}
	}))

	oldBase := botAPIBase
	botAPIBase = f.server.URL
	t.Cleanup(func() {
		botAPIBase = oldBase
		f.server.Close()
	})
	return f
}

func (f *fakeMax) record(r *http.Request) {
	body, _ := io.ReadAll(r.Body)
	var in struct {
		Text        string `json:"text"`
		Attachments []struct {
			Type    string `json:"type"`
			Payload struct {
				URL     string        `json:"url"`
				Buttons [][]botButton `json:"buttons"`
			} `json:"payload"`
		} `json:"attachments"`
	}
	json.Unmarshal(body, &in)

	m := fakeMessage{Text: in.Text}
	if id := r.URL.Query().Get("chat_id"); id != "" {
		m.Target = "chat:" + id
	} else {
		m.Target = "user:" + r.URL.Query().Get("user_id")
	}
	for _, a := range in.Attachments {
		if a.Type == "image" {
			m.Photo = a.Payload.URL
		}
		for _, row := range a.Payload.Buttons {
			for _, b := range row {
				m.Buttons = append(m.Buttons, b.Text)
				m.Colors = append(m.Colors, b)
			}
		}
	}
	f.mu.Lock()
	f.messages = append(f.messages, m)
	f.mu.Unlock()
}

func (f *fakeMax) last() fakeMessage {
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.messages) == 0 {
		return fakeMessage{}
	}
	return f.messages[len(f.messages)-1]
}

// fakeChat — номер группы у подставного MAX.
const fakeChat = 99

// callback собирает событие нажатия кнопки.
func callbackUpdate(userID, chatID int64, payload string) botUpdate {
	u := botUpdate{Type: "message_callback"}
	u.Callback = &botCallback{CallbackID: "cb", Payload: payload,
		User: botUser{UserID: userID, Name: "Покупатель"}}
	if chatID != 0 {
		u.Message = newMessage(userID, chatID, "")
		u.Message.Recipient.ChatType = "chat"
	}
	return u
}

func newMessage(userID, chatID int64, text string) *botMessage {
	m := &botMessage{Sender: botUser{UserID: userID, Name: "Покупатель"}}
	m.Recipient.UserID = userID
	m.Recipient.ChatID = chatID
	m.Recipient.ChatType = "dialog"
	m.Body.Text = text
	return m
}

func textUpdate(userID, chatID int64, text string) botUpdate {
	u := botUpdate{Type: "message_created", Message: newMessage(userID, chatID, text)}
	if chatID != 0 {
		u.Message.Recipient.ChatType = "chat"
	}
	return u
}

// contactUpdate — покупатель нажал «Отправить мой номер»: телефон приходит
// вложением-контактом в формате vCard.
func contactUpdate(userID int64, phone string) botUpdate {
	u := textUpdate(userID, 0, "")
	att := botAttachment{Type: "contact"}
	att.Payload.VcfInfo = "BEGIN:VCARD\nVERSION:3.0\nFN:Покупатель\nTEL:" + phone + "\nEND:VCARD"
	u.Message.Body.Attachments = []botAttachment{att}
	return u
}

// Полный путь покупателя в личке: каталог → товар → корзина → оформление.
// В конце на сайте появляется обычный заказ со списанным складом.
func TestBotOrderFlow(t *testing.T) {
	openTestDB(t)
	resetSiteCache()
	f := newFakeMax(t)
	api := newMaxAPI("test-token")
	ctx := context.Background()

	p := testProduct(t, "Пельмени домашние", "кг", 450, 10)
	const user = int64(555)

	handleBotUpdate(ctx, api, botUpdate{Type: "bot_started", UserID: user})
	if got := f.last(); !strings.Contains(strings.Join(got.Buttons, "|"), "Каталог") {
		t.Fatalf("в приветствии нет каталога: %#v", got)
	}

	handleBotUpdate(ctx, api, callbackUpdate(user, 0, "p:"+i64(p.ID)))
	if got := f.last(); !strings.Contains(got.Text, "Пельмени домашние") {
		t.Fatalf("карточка товара не показана: %#v", got)
	}

	// Весовой товар — предлагаем круглые килограммы.
	if got := f.last(); !strings.Contains(strings.Join(got.Buttons, "|"), "2 кг") {
		t.Errorf("нет кнопки на два килограмма: %#v", got.Buttons)
	}

	handleBotUpdate(ctx, api, callbackUpdate(user, 0, "add:"+i64(p.ID)+":2"))
	if got := f.last(); !strings.Contains(got.Text, "2 кг") || !strings.Contains(got.Text, "900") {
		t.Fatalf("корзина посчитана неверно: %q", got.Text)
	}

	handleBotUpdate(ctx, api, callbackUpdate(user, 0, "checkout"))
	handleBotUpdate(ctx, api, textUpdate(user, 0, "Иван"))
	handleBotUpdate(ctx, api, textUpdate(user, 0, "8 999 111-22-33"))
	handleBotUpdate(ctx, api, textUpdate(user, 0, "Ленина 5, кв 3"))

	orders, err := listOrders(-1, "")
	if err != nil || len(orders) != 1 {
		t.Fatalf("заказов %d (err %v), ожидался один", len(orders), err)
	}
	o := orders[0]
	if o.Source != sourceMax {
		t.Errorf("источник заказа %q, ожидался %q", o.Source, sourceMax)
	}
	if o.Total != 900 {
		t.Errorf("сумма заказа %v, ожидалось 900", o.Total)
	}
	if o.Phone != "+79991112233" {
		t.Errorf("телефон %q", o.Phone)
	}
	if o.Address != "Ленина 5, кв 3" {
		t.Errorf("адрес %q", o.Address)
	}

	// Склад списан так же, как при заказе с витрины.
	if got, _ := getProduct(p.ID); got.Stock != 8 {
		t.Errorf("остаток %v, ожидалось 8", got.Stock)
	}
	// Покупатель заведён в клиентах — потом с ним можно работать в CRM.
	if _, err := getClientByPhone("+79991112233"); err != nil {
		t.Errorf("клиент не заведён: %v", err)
	}
	// Разговор закрыт: корзина не тянется в следующий заказ.
	s := loadSession(user, 0)
	if len(s.Cart) != 0 || s.State != stateIdle {
		t.Errorf("разговор не сброшен: %#v", s)
	}
	if got := f.last(); !strings.Contains(got.Text, "принят") {
		t.Errorf("нет подтверждения заказа: %q", got.Text)
	}
}

// В группе бот не разворачивает каталог — зовёт в личный чат и оставляет
// ссылку на сайт: длинные списки мешают остальным участникам.
func TestBotInGroup(t *testing.T) {
	openTestDB(t)
	resetSiteCache()
	f := newFakeMax(t)
	api := newMaxAPI("test-token")
	ctx := context.Background()
	publicURL = "https://example.ru"
	t.Cleanup(func() { publicURL = "" })
	// Ссылка на личный чат появляется после знакомства с MAX.
	if _, err := api.me(ctx); err != nil {
		t.Fatalf("знакомство с MAX: %v", err)
	}
	bot.mu.Lock()
	bot.link, bot.username = "https://max.ru/shopbot", "shopbot"
	bot.mu.Unlock()
	t.Cleanup(func() {
		bot.mu.Lock()
		bot.link, bot.username = "", ""
		bot.mu.Unlock()
	})

	p := testProduct(t, "Колбаса", "кг", 800, 5)
	const user, chat = int64(11), int64(99)

	// Обычная болтовня бота не касается — даже со словом «заказ».
	before := len(f.messages)
	handleBotUpdate(ctx, api, textUpdate(user, chat, "а кто пойдёт завтра"))
	handleBotUpdate(ctx, api, textUpdate(user, chat, "мне вчера заказ привезли, всё вкусно"))
	if len(f.messages) != before {
		t.Fatalf("бот влез в разговор в группе: %#v", f.last())
	}

	// На обращение отвечает коротко: приглашение + две ссылки, без каталога.
	handleBotUpdate(ctx, api, textUpdate(user, chat, "бот, покажи каталог пожалуйста"))
	got := f.last()
	if got.Target != "chat:99" {
		t.Fatalf("ответ ушёл не в группу: %q", got.Target)
	}
	if strings.Contains(got.Text, p.Name) {
		t.Errorf("каталог развернулся в группе: %q", got.Text)
	}
	buttons := strings.Join(got.Buttons, "|")
	if !strings.Contains(buttons, "Каталог и заказ") || !strings.Contains(buttons, "Открыть магазин") {
		t.Errorf("нет кнопок в личку и на сайт: %#v", got.Buttons)
	}

	// Нажатие кнопки из старого закреплённого сообщения — тоже в личку.
	handleBotUpdate(ctx, api, callbackUpdate(user, chat, "catalog"))
	if got := f.last(); !strings.Contains(got.Text, "личном чате") {
		t.Errorf("кнопка в группе не увела в личку: %q", got.Text)
	}
	if orders, _ := listOrders(-1, ""); len(orders) != 0 {
		t.Errorf("в группе создался заказ: %d", len(orders))
	}
}

// Второй заказ постоянный покупатель оформляет одной кнопкой: имя, телефон
// и адрес бот помнит с прошлого раза. Телефон приходит кнопкой «Отправить мой номер».
func TestBotRemembersCustomer(t *testing.T) {
	openTestDB(t)
	resetSiteCache()
	f := newFakeMax(t)
	api := newMaxAPI("test-token")
	ctx := context.Background()

	p := testProduct(t, "Рёбра", "кг", 600, 10)
	const user = int64(42)

	// Первый заказ: имя кнопкой из профиля, телефон — вложением-контактом.
	handleBotUpdate(ctx, api, callbackUpdate(user, 0, "add:"+i64(p.ID)+":1"))
	handleBotUpdate(ctx, api, callbackUpdate(user, 0, "checkout"))
	if got := f.last(); !strings.Contains(strings.Join(got.Buttons, "|"), "Я Покупатель") {
		t.Fatalf("имя из профиля не предложено: %#v", got.Buttons)
	}
	handleBotUpdate(ctx, api, callbackUpdate(user, 0, "name_ok"))
	if got := f.last(); !strings.Contains(strings.Join(got.Buttons, "|"), "Отправить мой номер") {
		t.Fatalf("нет кнопки с номером: %#v", got.Buttons)
	}
	handleBotUpdate(ctx, api, contactUpdate(user, "+7 999 555-33-11"))
	handleBotUpdate(ctx, api, textUpdate(user, 0, "Мира 7"))

	orders, _ := listOrders(-1, "")
	if len(orders) != 1 || orders[0].Phone != "+79995553311" {
		t.Fatalf("первый заказ: %#v", orders)
	}

	// Второй заказ: ничего не спрашиваем, только подтверждение.
	handleBotUpdate(ctx, api, callbackUpdate(user, 0, "add:"+i64(p.ID)+":2"))
	handleBotUpdate(ctx, api, callbackUpdate(user, 0, "checkout"))
	ask := f.last()
	if !strings.Contains(ask.Text, "+7 (999) 555-33-11") || !strings.Contains(ask.Text, "Мира 7") {
		t.Fatalf("бот не вспомнил покупателя: %q", ask.Text)
	}
	if !strings.Contains(strings.Join(ask.Buttons, "|"), "Всё верно") {
		t.Fatalf("нет кнопки подтверждения: %#v", ask.Buttons)
	}

	// Прошлый заказ ещё в работе, поэтому новый не заводится: товары уходят
	// в него, и продавец звонит покупателю один раз.
	handleBotUpdate(ctx, api, callbackUpdate(user, 0, "confirm"))
	orders, _ = listOrders(-1, "")
	if len(orders) != 1 {
		t.Fatalf("заказы не объединились: %d", len(orders))
	}
	merged, err := getOrder(orders[0].ID)
	if err != nil {
		t.Fatalf("заказ не читается: %v", err)
	}
	if merged.Phone != "+79995553311" || merged.Address != "Мира 7" || merged.Total != 1800 {
		t.Errorf("общий заказ: телефон %q, адрес %q, сумма %v",
			merged.Phone, merged.Address, merged.Total)
	}
	if len(merged.Items) != 1 || merged.Items[0].Qty != 3 {
		t.Errorf("состав общего заказа: %#v", merged.Items)
	}
}

// Главные кнопки заметны цветом: оформление зелёное, отмена красная.
func TestBotButtonColors(t *testing.T) {
	openTestDB(t)
	resetSiteCache()
	f := newFakeMax(t)
	api := newMaxAPI("test-token")
	ctx := context.Background()

	p := testProduct(t, "Окорок", "кг", 700, 5)
	const user = int64(64)

	handleBotUpdate(ctx, api, callbackUpdate(user, 0, "add:"+i64(p.ID)+":1"))
	intent := map[string]string{}
	for _, b := range f.last().Colors {
		intent[b.Text] = b.Intent
	}
	if intent["✅ Оформить заказ"] != "positive" {
		t.Errorf("кнопка оформления не зелёная: %q", intent["✅ Оформить заказ"])
	}
	if intent["Очистить"] != "negative" {
		t.Errorf("кнопка очистки не красная: %q", intent["Очистить"])
	}

	handleBotUpdate(ctx, api, callbackUpdate(user, 0, "checkout"))
	for _, b := range f.last().Colors {
		if b.Text == "Отменить" && b.Intent != "negative" {
			t.Errorf("кнопка отмены не красная: %q", b.Intent)
		}
	}
}

// Длинный каталог листается кнопками «Далее» и «Назад»: за раз показываем
// восемь товаров, иначе нижние строки в мессенджере просто не видно.
func TestBotCatalogPaging(t *testing.T) {
	openTestDB(t)
	resetSiteCache()
	f := newFakeMax(t)
	api := newMaxAPI("test-token")
	ctx := context.Background()

	for i := 1; i <= 20; i++ {
		testProduct(t, "Товар "+strconv.Itoa(i), "шт", float64(100+i), 10)
	}
	const user = int64(88)

	handleBotUpdate(ctx, api, callbackUpdate(user, 0, "cat::0"))
	first := f.last()
	if len(first.Buttons) != productsPerPage+2 { // товары + «Далее» + «← Разделы»
		t.Fatalf("на первой странице кнопок %d: %#v", len(first.Buttons), first.Buttons)
	}
	joined := strings.Join(first.Buttons, "|")
	if !strings.Contains(joined, "Далее →") || strings.Contains(joined, "← Назад") {
		t.Errorf("навигация первой страницы: %#v", first.Buttons)
	}
	if !strings.Contains(first.Text, "1–8 из 20") {
		t.Errorf("не видно, какие товары показаны: %q", first.Text)
	}

	handleBotUpdate(ctx, api, callbackUpdate(user, 0, "cat::1"))
	second := f.last()
	joined = strings.Join(second.Buttons, "|")
	if !strings.Contains(joined, "← Назад") || !strings.Contains(joined, "Далее →") {
		t.Errorf("навигация второй страницы: %#v", second.Buttons)
	}
	if !strings.Contains(second.Text, "9–16 из 20") {
		t.Errorf("вторая страница: %q", second.Text)
	}

	// Последняя страница: «Далее» уже не нужно.
	handleBotUpdate(ctx, api, callbackUpdate(user, 0, "cat::2"))
	last := f.last()
	if strings.Contains(strings.Join(last.Buttons, "|"), "Далее →") {
		t.Errorf("на последней странице осталось «Далее»: %#v", last.Buttons)
	}
	if !strings.Contains(last.Text, "17–20 из 20") {
		t.Errorf("последняя страница: %q", last.Text)
	}

	// Номер страницы за пределами списка не роняет бота.
	handleBotUpdate(ctx, api, callbackUpdate(user, 0, "cat::99"))
	if got := f.last(); !strings.Contains(got.Text, "17–20 из 20") {
		t.Errorf("страница вне диапазона: %q", got.Text)
	}
}

// Слово-обращение задаётся из приложения: бота можно назвать Марусей,
// и тогда он откликается на неё, а на «бот» — уже нет.
func TestCustomCallWords(t *testing.T) {
	t.Cleanup(func() { setCallWords("") })

	setCallWords("Маруся, помощник")
	cases := []struct {
		text   string
		intent string
		called bool
	}{
		{"маруся, каталог", intentCatalog, true},
		{"Маруся оформи заказ", intentCheckout, true},
		{"марусе привет", intentMenu, true},       // падежи ловим по началу слова
		{"помощник, корзина", intentCart, true},   // второе слово из списка
		{"бот, каталог", intentNone, false},       // старое слово больше не работает
		{"марусенька колбаса", intentNone, false}, // длинный хвост — уже не обращение
	}
	for _, c := range cases {
		intent, called := parseIntent(c.text, true)
		if intent != c.intent || called != c.called {
			t.Errorf("«%s» → %q/%v, ожидалось %q/%v", c.text, intent, called, c.intent, c.called)
		}
	}

	// Пустая строка возвращает значение по умолчанию.
	setCallWords("   ")
	if _, called := parseIntent("бот, каталог", true); !called {
		t.Error("без настройки бот должен откликаться на «бот»")
	}
}

// Телефон из вложения-контакта разбирается из vCard.
func TestPhoneFromContact(t *testing.T) {
	vcf := "BEGIN:VCARD\nVERSION:3.0\nFN:Иван\nTEL;TYPE=CELL:+7 (999) 111-22-33\nEND:VCARD"
	if got := phoneFromContact(vcf); got != "+79991112233" {
		t.Errorf("телефон из vCard: %q", got)
	}
	if got := phoneFromContact("BEGIN:VCARD\nFN:Без телефона\nEND:VCARD"); got != "" {
		t.Errorf("из карточки без телефона получили %q", got)
	}
}

// «Бот, отмена» прерывает оформление в любой момент, а брошенный разговор
// сгорает сам — чужая реплика через день не станет именем в заказе.
func TestBotCancelAndStaleSession(t *testing.T) {
	openTestDB(t)
	resetSiteCache()
	f := newFakeMax(t)
	api := newMaxAPI("test-token")
	ctx := context.Background()

	p := testProduct(t, "Рулет", "кг", 500, 5)
	const user = int64(31)

	handleBotUpdate(ctx, api, callbackUpdate(user, 0, "add:"+i64(p.ID)+":1"))
	handleBotUpdate(ctx, api, callbackUpdate(user, 0, "checkout"))
	handleBotUpdate(ctx, api, textUpdate(user, 0, "бот, отмена"))
	if got := f.last(); !strings.Contains(got.Text, "Отменил") {
		t.Fatalf("отмена не сработала: %q", got.Text)
	}
	if s := loadSession(user, 0); s.State != stateIdle || len(s.Cart) != 1 {
		t.Errorf("после отмены разговор %#v — корзина должна остаться", s)
	}

	// Заказ бросили: сдвигаем время последнего сообщения на сутки назад.
	handleBotUpdate(ctx, api, callbackUpdate(user, 0, "checkout"))
	if _, err := db.Exec(`UPDATE bot_sessions SET updated_at = ? WHERE key = ?`,
		nowMs()-24*60*60*1000, sessionKey(user, 0)); err != nil {
		t.Fatalf("состаривание разговора: %v", err)
	}
	s := loadSession(user, 0)
	if s.State != stateIdle || len(s.Cart) != 0 {
		t.Errorf("протухший разговор не сброшен: %#v", s)
	}
}

// Разбор обращения: слова в любом порядке, лишние знаки не мешают,
// а без обращения в группе бот молчит.
func TestParseIntent(t *testing.T) {
	cases := []struct {
		text   string
		group  bool
		intent string
		called bool
	}{
		{"каталог", false, intentCatalog, true},
		{"хочу оформить заказ", false, intentCheckout, true},
		{"бот, покажи каталог", true, intentCatalog, true},
		{"заказ бот оформи", true, intentCheckout, true},
		{"боту привет", true, intentMenu, true},
		{"/каталог", true, intentCatalog, true},
		{"бот отмена", true, intentCancel, true},
		{"когда привезут заказ?", true, intentNone, false},
		{"робот пылесос", true, intentNone, false},
		{"", true, intentNone, false},
	}
	for _, c := range cases {
		intent, called := parseIntent(c.text, c.group)
		if intent != c.intent || called != c.called {
			t.Errorf("«%s» (группа=%v) → %q/%v, ожидалось %q/%v",
				c.text, c.group, intent, called, c.intent, c.called)
		}
	}
}

// Неверный телефон бот не пропускает — иначе заказ некому подтвердить.
func TestBotChecksPhone(t *testing.T) {
	openTestDB(t)
	resetSiteCache()
	f := newFakeMax(t)
	api := newMaxAPI("test-token")
	ctx := context.Background()

	p := testProduct(t, "Сало", "кг", 600, 3)
	const user = int64(21)

	handleBotUpdate(ctx, api, callbackUpdate(user, 0, "add:"+i64(p.ID)+":1"))
	handleBotUpdate(ctx, api, callbackUpdate(user, 0, "checkout"))
	handleBotUpdate(ctx, api, textUpdate(user, 0, "Анна"))
	handleBotUpdate(ctx, api, textUpdate(user, 0, "телефона нет"))

	if got := f.last(); !strings.Contains(got.Text, "Не похоже на телефон") {
		t.Fatalf("кривой телефон приняли: %q", got.Text)
	}
	if s := loadSession(user, 0); s.State != statePhone {
		t.Errorf("шаг оформления %q, ожидался телефон", s.State)
	}
	orders, _ := listOrders(-1, "")
	if len(orders) != 0 {
		t.Errorf("заказ создан без телефона: %d", len(orders))
	}
}

// В карточке товара идёт фото с сайта, а рядом — кнопка своего количества:
// покупатель может взять не «полкило или два», а сколько ему нужно.
func TestBotPhotoAndOwnQty(t *testing.T) {
	openTestDB(t)
	resetSiteCache()
	f := newFakeMax(t)
	api := newMaxAPI("test-token")
	ctx := context.Background()
	publicURL = "https://example.ru"
	t.Cleanup(func() { publicURL = "" })

	p := testProduct(t, "Грудинка", "кг", 900, 10)
	p.Image = "/photo/gr.jpg"
	if err := updateProduct(&p); err != nil {
		t.Fatalf("картинка товара: %v", err)
	}
	const user = int64(77)

	handleBotUpdate(ctx, api, callbackUpdate(user, 0, "p:"+i64(p.ID)))
	card := f.last()
	if card.Photo != "https://example.ru/photo/gr.jpg" {
		t.Errorf("фото товара не приложено: %q", card.Photo)
	}
	if !strings.Contains(strings.Join(card.Buttons, "|"), "Своё количество") {
		t.Fatalf("нет кнопки своего количества: %#v", card.Buttons)
	}

	handleBotUpdate(ctx, api, callbackUpdate(user, 0, stateQtyPrefix+i64(p.ID)))
	if got := f.last(); !strings.Contains(got.Text, "число в кг") {
		t.Errorf("бот не спросил количество: %q", got.Text)
	}

	// Кривое число не принимаем и остаёмся на том же шаге.
	handleBotUpdate(ctx, api, textUpdate(user, 0, "много"))
	if got := f.last(); !strings.Contains(got.Text, "Не понял количество") {
		t.Errorf("приняли не число: %q", got.Text)
	}

	// Меньше одного заказать нельзя — это тоже «не понял».
	handleBotUpdate(ctx, api, textUpdate(user, 0, "0,5"))
	if got := f.last(); !strings.Contains(got.Text, "Не понял количество") {
		t.Errorf("приняли полкило: %q", got.Text)
	}

	handleBotUpdate(ctx, api, textUpdate(user, 0, "3"))
	// Сумму сверяем через money(): в ней неразрывные пробелы.
	if got := f.last(); !strings.Contains(got.Text, "3 кг") ||
		!strings.Contains(got.Text, money(2700)) {
		t.Fatalf("своё количество не попало в корзину: %q", got.Text)
	}
	if s := loadSession(user, 0); s.State != stateIdle {
		t.Errorf("шаг разговора завис на %q", s.State)
	}
}

// MAX принимает токен только заголовком Authorization: параметр access_token
// в адресе он объявил устаревшим и отвечает на него 401.
func TestBotSendsTokenInHeader(t *testing.T) {
	openTestDB(t)
	f := newFakeMax(t)

	if _, err := newMaxAPI("secret-token").me(context.Background()); err != nil {
		t.Fatalf("запрос к MAX: %v", err)
	}
	f.mu.Lock()
	auth, query := f.lastAuth, f.lastQuery
	f.mu.Unlock()

	if auth != "secret-token" {
		t.Errorf("заголовок Authorization = %q", auth)
	}
	if strings.Contains(query, "access_token") {
		t.Errorf("токен ушёл в адресе: %q", query)
	}
}

// Токен наружу не отдаём — только хвост, чтобы понять, какой из них вписан.
func TestMaskToken(t *testing.T) {
	if got := maskToken("abcdef123456"); got != "••••3456" {
		t.Errorf("маска %q", got)
	}
	if got := maskToken(""); got != "" {
		t.Errorf("пустой токен дал %q", got)
	}
}

// Пропала связь — нажатия копились у MAX и пришли разом. Одинаковых
// сообщений подряд бот не шлёт: в чате остаётся одно приглашение.
func TestBotNoRepeatFlood(t *testing.T) {
	openTestDB(t)
	resetSiteCache()
	forgetSentTexts()
	f := newFakeMax(t)
	api := newMaxAPI("test-token")
	ctx := context.Background()
	const user, chat = int64(731), int64(788)

	for i := 0; i < 5; i++ {
		handleBotUpdate(ctx, api, callbackUpdate(user, chat, "catalog"))
	}
	if len(f.messages) != 1 {
		t.Fatalf("приглашений в группу: %d, ожидалось одно", len(f.messages))
	}

	// В личке то же самое: пять раз «привет» — одно меню.
	before := len(f.messages)
	for i := 0; i < 5; i++ {
		handleBotUpdate(ctx, api, textUpdate(user, 0, "привет"))
	}
	if got := len(f.messages) - before; got != 1 {
		t.Fatalf("ответов в личке: %d, ожидался один", got)
	}

	// Спустя время бот снова отвечает: замолчать насовсем он не должен.
	ageSentTexts(time.Hour)
	handleBotUpdate(ctx, api, textUpdate(user, 0, "привет"))
	if got := len(f.messages) - before; got != 2 {
		t.Fatalf("после паузы ответов: %d, ожидалось два", got)
	}
}

// forgetSentTexts и ageSentTexts подменяют тестам ход времени: иначе
// проверка «через минуту бот отвечает снова» ждала бы эту минуту.
func forgetSentTexts() {
	lastSentMu.Lock()
	lastSent = map[string]time.Time{}
	lastSentMu.Unlock()
}

func ageSentTexts(d time.Duration) {
	lastSentMu.Lock()
	for k, at := range lastSent {
		lastSent[k] = at.Add(-d)
	}
	lastSentMu.Unlock()
}
