package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"log"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Разговор с покупателем в MAX.
//
// Бот работает и в личке, и в группе. В группе он ведёт себя сдержанно:
// отвечает только на команды и нажатия кнопок, а рядом всегда предлагает
// уйти в личный чат или открыть сайт — чтобы телефон и адрес не пришлось
// писать при всех.
//
// Разговор хранится в таблице bot_sessions, ключ — «пользователь:чат»:
// один и тот же человек может набирать заказ и в группе, и в личке,
// и они не перемешаются. Заказ создаётся тем же createOrder, что и с сайта:
// он же списывает склад и заводит карточку клиента.

// botSession — состояние разговора и набранная корзина.
type botSession struct {
	Key     string
	UserID  int64
	ChatID  int64
	State   string
	Name    string
	Phone   string
	Address string
	Cart    []botCartLine
}

type botCartLine struct {
	ProductID int64   `json:"id"`
	Qty       float64 `json:"q"`
}

// Шаги оформления.
const (
	stateIdle    = ""
	stateName    = "name"
	statePhone   = "phone"
	stateAddress = "address"
	// Ждём, что покупатель напишет своё количество: state = «qty:<id товара>».
	stateQtyPrefix = "qty:"
)

func sessionKey(userID, chatID int64) string {
	return strconv.FormatInt(userID, 10) + ":" + strconv.FormatInt(chatID, 10)
}

// sessionTTL — сколько живёт брошенный разговор. Человек начал оформление,
// отвлёкся и вернулся через день — его «привет» не должно стать именем в заказе.
const sessionTTL = 3 * 60 * 60 * 1000

func loadSession(userID, chatID int64) botSession {
	s := botSession{Key: sessionKey(userID, chatID), UserID: userID, ChatID: chatID}
	var cart string
	var updated int64
	err := db.QueryRow(`SELECT state, name, phone, address, cart, updated_at FROM bot_sessions
      WHERE key = ?`, s.Key).Scan(&s.State, &s.Name, &s.Phone, &s.Address, &cart, &updated)
	if err != nil {
		return s
	}
	if updated > 0 && nowMs()-updated > sessionTTL {
		// Разговор протух: начинаем с чистого листа, корзину не тянем.
		clearSession(s)
		return botSession{Key: s.Key, UserID: userID, ChatID: chatID, Name: s.Name}
	}
	if cart != "" {
		if err := json.Unmarshal([]byte(cart), &s.Cart); err != nil {
			logErr("корзина бота", err)
		}
	}
	return s
}

func saveSession(s botSession) {
	cart, err := json.Marshal(s.Cart)
	if err != nil {
		logErr("корзина бота", err)
		return
	}
	_, err = db.Exec(`INSERT INTO bot_sessions
      (key, user_id, chat_id, state, name, phone, address, cart, updated_at)
      VALUES (?,?,?,?,?,?,?,?,?)
      ON CONFLICT(key) DO UPDATE SET state = excluded.state, name = excluded.name,
        phone = excluded.phone, address = excluded.address, cart = excluded.cart,
        chat_id = excluded.chat_id, updated_at = excluded.updated_at`,
		s.Key, s.UserID, s.ChatID, s.State, s.Name, s.Phone, s.Address, string(cart), nowMs())
	logErr("сохранение разговора бота", err)
}

// purgeOldSessions убирает разговоры, к которым не возвращались неделю:
// они всё равно протухли, а таблица росла бы вечно. Покупателей
// (bot_customers) не трогаем — на них держится «оформить как в прошлый раз».
func purgeOldSessions() {
	res, err := db.Exec(`DELETE FROM bot_sessions WHERE updated_at < ?`,
		nowMs()-7*24*60*60*1000)
	if err != nil {
		logErr("уборка разговоров бота", err)
		return
	}
	if n, _ := res.RowsAffected(); n > 0 {
		log.Printf("бот MAX: убрано старых разговоров: %d", n)
	}
}

func clearSession(s botSession) {
	_, err := db.Exec(`DELETE FROM bot_sessions WHERE key = ?`, s.Key)
	logErr("очистка разговора бота", err)
}

// ------------------------------------------------------- знакомые покупатели

// botCustomer — что бот помнит о человеке с прошлого заказа.
type botCustomer struct {
	UserID   int64
	ClientID *int64
	Name     string
	Phone    string
	Address  string
}

func (c botCustomer) known() bool { return validPhone(c.Phone) }

func loadCustomer(userID int64) botCustomer {
	c := botCustomer{UserID: userID}
	var clientID sql.NullInt64
	err := db.QueryRow(`SELECT client_id, name, phone, address FROM bot_customers
      WHERE user_id = ?`, userID).Scan(&clientID, &c.Name, &c.Phone, &c.Address)
	if err != nil {
		return c
	}
	if clientID.Valid {
		v := clientID.Int64
		c.ClientID = &v
	}
	return c
}

func saveCustomer(c botCustomer) {
	_, err := db.Exec(`INSERT INTO bot_customers
      (user_id, client_id, name, phone, address, updated_at) VALUES (?,?,?,?,?,?)
      ON CONFLICT(user_id) DO UPDATE SET client_id = excluded.client_id,
        name = excluded.name, phone = excluded.phone, address = excluded.address,
        updated_at = excluded.updated_at`,
		c.UserID, c.ClientID, c.Name, c.Phone, c.Address, nowMs())
	logErr("покупатель бота", err)
}

// phoneFromContact достаёт телефон из вложения-контакта (vCard),
// который присылает мессенджер по кнопке «Отправить мой номер».
func phoneFromContact(vcf string) string {
	for _, line := range strings.Split(vcf, "\n") {
		line = strings.TrimSpace(line)
		upper := strings.ToUpper(line)
		if !strings.HasPrefix(upper, "TEL") {
			continue
		}
		if i := strings.Index(line, ":"); i >= 0 {
			phone := normalizePhone(line[i+1:])
			if validPhone(phone) {
				return phone
			}
		}
	}
	return ""
}

// ------------------------------------------------------------ разбор событий

func handleBotUpdate(ctx context.Context, api *maxAPI, u botUpdate) {
	defer func() {
		// Кривое сообщение не должно ронять весь опрос.
		if rec := recover(); rec != nil {
			log.Printf("бот MAX: сбой на обновлении %s: %v", u.Type, rec)
		}
	}()

	switch u.Type {
	case "bot_started":
		userID := u.UserID
		if u.User != nil {
			userID = u.User.UserID
		}
		s := loadSession(userID, 0)
		if u.User != nil && s.Name == "" {
			s.Name = u.User.Name
		}
		saveSession(s)
		// Корзину могли набрать в группе и уйти сюда оформлять — показываем её сразу.
		if len(s.Cart) > 0 {
			sendCart(ctx, api, s)
			return
		}
		sendMenu(ctx, api, s, welcomeText())

	case "bot_added":
		// Бота позвали в группу — здороваемся и сразу показываем, что умеем.
		if u.ChatID != 0 {
			s := loadSession(0, u.ChatID)
			sendMenu(ctx, api, s, welcomeText())
		}

	case "message_created":
		if u.Message == nil {
			return
		}
		onBotMessage(ctx, api, u)

	case "message_edited":
		if u.Message == nil {
			return
		}
		onBotMessageEdited(u)

	case "message_callback":
		if u.Callback == nil {
			return
		}
		onBotCallback(ctx, api, u)
	}
}

// onBotMessageEdited — покупатель исправил уже отправленное сообщение.
//
// Так обычно и поправляют заказ: «не 3 кг, а 2» дописывают не новой строкой,
// а правкой прежней. MAX присылает такое отдельным событием с тем же mid,
// и раньше мы его просто не слушали — в заявке оставался первый вариант.
//
// Разбираем исправленный текст заново: он дописывается в ту же заявку
// с пометкой, что это правка. Отметку «разобрано» не ставим и не проверяем —
// mid тот же, а прочитать его нужно второй раз. Боту это повод промолчать:
// на правку сообщения отвечать в переписке незачем.
func onBotMessageEdited(u botUpdate) {
	l, _ := leadFromMessage(u.Message)
	l.Edited = true
	catchLead(l)
}

func onBotMessage(ctx context.Context, api *maxAPI, u botUpdate) {
	m := u.Message
	text := strings.TrimSpace(m.Body.Text)

	// Нажали «Отправить мой номер» — телефон пришёл вложением, а не текстом.
	for _, a := range m.Body.Attachments {
		if a.Type != "contact" {
			continue
		}
		if phone := phoneFromContact(a.Payload.VcfInfo); phone != "" {
			s := loadSession(m.Sender.UserID, 0)
			if s.Name == "" {
				s.Name = m.Sender.Name
			}
			acceptPhone(ctx, api, s, phone)
			return
		}
	}
	// Пересланное сообщение приходит с пустым текстом: он лежит внутри link.
	forwarded := m.Link != nil && m.Link.Type == "forward" &&
		strings.TrimSpace(m.Link.Message.Text) != ""
	if text == "" && !forwarded {
		return
	}
	group := m.Recipient.ChatType != "dialog" && m.Recipient.ChatID != 0

	chatID := int64(0)
	if group {
		chatID = m.Recipient.ChatID
	}
	s := loadSession(m.Sender.UserID, chatID)
	if s.Name == "" {
		s.Name = m.Sender.Name
	}

	// Перехват заявок. Молча и до всего остального: человек мог написать
	// заказ одной строкой, не позвав бота, — заявка всё равно попадёт в CRM.
	// Отмечаем сообщение разобранным в любом случае, чтобы догон истории
	// (maxbot_history.go) не завёл по нему вторую заявку. Посреди оформления
	// не вмешиваемся: там телефон и так спрашивают, заказ соберётся обычным путём.
	if l, mid := leadFromMessage(m); markSeen(mid) && s.State == stateIdle {
		// Сначала спам: заявки из «заработка в интернете» не бывает, а
		// удалённое сообщение разбирать уже незачем (см. maxbot_spam.go).
		if group && checkSpam(ctx, api, l, mid) {
			return
		}
		catchLead(l)
	}
	if text == "" {
		return // пересланное разобрали, отвечать на него нечего
	}

	// Обращение к боту разбираем всегда — даже посреди оформления:
	// «бот, отмена» должно срабатывать в любой момент.
	intent, called := parseIntent(text, group)
	if called && group {
		// В группе бот не разворачивает каталог: отвечает коротко и
		// приглашает в личный чат, чтобы не заваливать общий чат списками.
		sendGroupInvite(ctx, api, s, intent)
		return
	}
	if called {
		switch intent {
		case intentCancel:
			s.State = stateIdle
			saveSession(s)
			sendMenu(ctx, api, s, "Отменил. Ничего не потерялось — корзина на месте.")
			return
		case intentCatalog:
			s.State = stateIdle
			saveSession(s)
			sendCategories(ctx, api, s, 0)
			return
		case intentCart:
			s.State = stateIdle
			saveSession(s)
			sendCart(ctx, api, s)
			return
		case intentCheckout:
			s.State = stateIdle
			saveSession(s)
			startCheckout(ctx, api, s)
			return
		case intentMenu:
			// Посреди оформления «меню» не сбрасывает шаг: человек мог
			// просто написать слово, похожее на приветствие.
			if s.State == stateIdle {
				sendMenu(ctx, api, s, welcomeText())
				return
			}
		}
	}

	// Ничего не оформляем — в группе молчим, в личке подсказываем.
	if s.State == stateIdle {
		if !group {
			sendMenu(ctx, api, s, "Не понял. Выберите, что нужно:")
		}
		return
	}
	if strings.HasPrefix(s.State, stateQtyPrefix) {
		applyOwnQty(ctx, api, s, text)
		return
	}
	continueCheckout(ctx, api, s, text)
}

// leadFromMessage собирает из входящего сообщения заявку и id, по которому
// это сообщение больше не разберут повторно.
//
// Пересланное читаем как сообщение его автора: продавец кидает в рабочую
// группу просьбу покупателя, и заявка должна быть на покупателя, а не на
// продавца. Цитату в ответе не трогаем — там чужой старый текст, и заявку
// по нему заводить нельзя.
func leadFromMessage(m *botMessage) (lead, string) {
	l := lead{
		UserID:   m.Sender.UserID,
		Name:     m.Sender.Name,
		Username: m.Sender.Username,
		Text:     strings.TrimSpace(m.Body.Text),
		At:       m.Timestamp,
	}
	mid := m.Body.MID
	if m.Link == nil || m.Link.Type != "forward" {
		return l, mid
	}
	inner := strings.TrimSpace(m.Link.Message.Text)
	if inner == "" {
		return l, mid
	}
	// Подпись к пересланному тоже сохраняем: в ней бывает «это заказ на завтра».
	if l.Text != "" {
		inner += "\n" + l.Text
	}
	l.Via = m.Sender.Name
	l.UserID, l.Name, l.Username = m.Link.Sender.UserID, m.Link.Sender.Name,
		m.Link.Sender.Username
	l.Text = inner
	// Одно и то же пересланное сообщение считаем разобранным по его же id:
	// переслали второй раз — заявка не задвоится.
	if m.Link.Message.MID != "" {
		mid = m.Link.Message.MID
	}
	return l, mid
}

func onBotCallback(ctx context.Context, api *maxAPI, u botUpdate) {
	cb := u.Callback
	chatID := int64(0)
	if u.Message != nil && u.Message.Recipient.ChatType != "dialog" {
		chatID = u.Message.Recipient.ChatID
	}
	s := loadSession(cb.User.UserID, chatID)
	if s.Name == "" {
		s.Name = cb.User.Name
	}

	// Кнопка из группы (например, из старого закреплённого сообщения):
	// каталог там не разворачиваем, зовём в личку.
	if s.ChatID != 0 {
		sendGroupInvite(ctx, api, s, intentCatalog)
		if cb.CallbackID != "" {
			logErr("ответ на кнопку", api.answer(ctx, cb.CallbackID, "Откройте бота в личном чате"))
		}
		return
	}

	payload := cb.Payload
	note := ""
	switch {
	case payload == "menu":
		s.State = stateIdle
		saveSession(s)
		sendMenu(ctx, api, s, welcomeText())

	case payload == "catalog":
		sendCategories(ctx, api, s, 0)

	case strings.HasPrefix(payload, "cats:"):
		page, _ := strconv.Atoi(strings.TrimPrefix(payload, "cats:"))
		sendCategories(ctx, api, s, page)

	case strings.HasPrefix(payload, "cat:"):
		// «cat:<раздел>:<страница>» — раздел может быть пустым («всё сразу»).
		body := strings.TrimPrefix(payload, "cat:")
		category, page := body, 0
		if i := strings.LastIndex(body, ":"); i >= 0 {
			category = body[:i]
			page, _ = strconv.Atoi(body[i+1:])
		}
		sendCategory(ctx, api, s, category, page)

	case strings.HasPrefix(payload, "p:"):
		id, _ := strconv.ParseInt(strings.TrimPrefix(payload, "p:"), 10, 64)
		sendProduct(ctx, api, s, id)

	case strings.HasPrefix(payload, stateQtyPrefix):
		askOwnQty(ctx, api, s, strings.TrimPrefix(payload, stateQtyPrefix))

	case strings.HasPrefix(payload, "add:"):
		note = botAddToCart(ctx, api, &s, strings.TrimPrefix(payload, "add:"))

	case payload == "cart":
		sendCart(ctx, api, s)

	case strings.HasPrefix(payload, "del:"):
		id, _ := strconv.ParseInt(strings.TrimPrefix(payload, "del:"), 10, 64)
		botRemoveFromCart(&s, id)
		saveSession(s)
		sendCart(ctx, api, s)
		note = "Убрал из корзины"

	case payload == "clear":
		s.Cart = nil
		s.State = stateIdle
		saveSession(s)
		sendMenu(ctx, api, s, "Корзина пуста.")

	case payload == "checkout":
		startCheckout(ctx, api, s)

	case payload == "checkout_here":
		askName(ctx, api, s)

	case payload == "cancel":
		s.State = stateIdle
		saveSession(s)
		sendMenu(ctx, api, s, "Отменил. Корзина на месте.")
		note = "Отменено"

	case payload == "pickup":
		s.Address = "Самовывоз"
		finishOrder(ctx, api, s)

	case payload == "confirm":
		// Постоянный покупатель подтвердил прошлые данные.
		finishOrder(ctx, api, s)

	case payload == "edit":
		s.Phone, s.Address = "", ""
		askName(ctx, api, s)

	case payload == "name_ok":
		// Имя взяли из профиля MAX — остался телефон.
		saveSession(s)
		askPhone(ctx, api, s)

	case payload == "last_address":
		s.Address = loadCustomer(s.UserID).Address
		finishOrder(ctx, api, s)
	}

	if cb.CallbackID != "" {
		logErr("ответ на кнопку", api.answer(ctx, cb.CallbackID, note))
	}
}

// sendGroupInvite — короткий ответ в группе с приглашением в личный чат.
func sendGroupInvite(ctx context.Context, api *maxAPI, s botSession, intent string) {
	text := "Каталог, корзина и оформление — в личном чате с ботом: " +
		"там витрина с фотографиями, и телефон с адресом увидите только вы."
	switch intent {
	case intentCart:
		text = "Корзина живёт в личном чате с ботом — откройте его."
	case intentCheckout:
		text = "Оформить заказ можно в личном чате с ботом: " +
			"там телефон и адрес не увидят другие участники группы."
	}
	rows := groupRows()
	if len(rows) == 0 {
		// Ссылки на бота ещё нет (не узнали @username) — хотя бы отвечаем текстом.
		text += " Найдите бота в MAX и напишите ему."
	}
	to := sendTo{ChatID: s.ChatID}
	if justSent(to, text) {
		// Кнопку в группе нажали несколько раз подряд — приглашение уже висит.
		return
	}
	logErr("приглашение в личку", api.send(ctx, to, text, rows))
}

// ------------------------------------------------------------ экраны

func welcomeText() string {
	site := siteText()
	return site.Title + ".\n" +
		"Соберите заказ прямо здесь: каталог, корзина, оформление.\n\n" +
		"Телефон: " + site.Phone + "\nРаботаем: " + site.Hours
}

// tail — кнопки, которые уместны в любом сообщении: уйти в личку и на сайт.
func tail(s botSession) []botButton {
	var row []botButton
	if s.ChatID != 0 {
		if link := botLink(); link != "" {
			row = append(row, linkButton("Написать боту лично", link))
		}
	}
	if url := shopURL(); url != "" {
		row = append(row, linkButton("Открыть магазин", url))
	}
	return row
}

// Один и тот же текст подряд бот не повторяет.
//
// Пока не было связи, нажатия копились у MAX: человек жал «Каталог» ещё и
// ещё, а в чате ничего не происходило. Связь вернулась — весь запас пришёл
// разом, и подряд улетело несколько одинаковых приглашений. Такие повторы
// пропускаем: экран они не меняют, а чат засоряют. Заказ и корзину это не
// трогает — там текст каждый раз другой (сумма, состав, номер заказа).
var dupWindow = 10 * time.Second

var (
	lastSentMu sync.Mutex
	lastSent   = map[string]time.Time{}
)

// justSent — этот же текст только что ушёл этому же собеседнику.
func justSent(to sendTo, text string) bool {
	key := i64(to.ChatID) + ":" + i64(to.UserID) + ":" + text
	now := time.Now()

	lastSentMu.Lock()
	defer lastSentMu.Unlock()
	// Заодно подчищаем: иначе карта росла бы на каждое новое сообщение.
	for k, at := range lastSent {
		if now.Sub(at) > dupWindow {
			delete(lastSent, k)
		}
	}
	if at, ok := lastSent[key]; ok && now.Sub(at) <= dupWindow {
		return true
	}
	lastSent[key] = now
	return false
}

func send(ctx context.Context, api *maxAPI, s botSession, text string, rows [][]botButton) {
	sendWithPhoto(ctx, api, s, text, "", rows)
}

func sendWithPhoto(ctx context.Context, api *maxAPI, s botSession, text, photo string,
	rows [][]botButton) {
	if extra := tail(s); len(extra) > 0 {
		rows = append(rows, extra)
	}
	to := sendTo{UserID: s.UserID}
	if s.ChatID != 0 {
		to = sendTo{ChatID: s.ChatID}
	}
	if justSent(to, text) {
		return
	}
	logErr("отправка сообщения бота", api.sendPhoto(ctx, to, text, photo, rows))
}

// photoURL — полный адрес картинки товара. Сама картинка лежит на сайте,
// поэтому без адреса витрины (его присылает приложение) фото не отправляем.
func photoURL(p Product) string {
	img := strings.TrimSpace(p.Image)
	if img == "" {
		return ""
	}
	if strings.HasPrefix(img, "http") {
		return img
	}
	site := shopURL()
	if site == "" {
		return ""
	}
	return site + img
}

func sendMenu(ctx context.Context, api *maxAPI, s botSession, text string) {
	rows := [][]botButton{
		{callbackButton("🛍 Каталог", "catalog")},
		{callbackButton("🧺 Корзина ("+qtyText(cartCount(s))+")", "cart")},
	}
	send(ctx, api, s, text, rows)
}

// groupRows — кнопки для группы. Каталог и корзину в общем чате не
// показываем: длинные списки мешают остальным участникам. Вместо этого
// уводим человека в личный чат, а рядом оставляем ссылку на сайт.
func groupRows() [][]botButton {
	var row []botButton
	if link := botLink(); link != "" {
		row = append(row, linkButton("🛍 Каталог и заказ", link))
	}
	if u := shopURL(); u != "" {
		row = append(row, linkButton("Открыть магазин", u))
	}
	if len(row) == 0 {
		return nil
	}
	return [][]botButton{row}
}

// sendGroupMenu — карточка для группы: её закрепляют, и дальше покупателю
// достаточно нажать кнопку.
func sendGroupMenu(ctx context.Context, api *maxAPI, chatID int64) error {
	rows := groupRows()
	site := siteText()
	text := site.Title + " — заказ через бота.\n" +
		"Нажмите «Каталог и заказ»: бот откроется в личном чате, там витрина " +
		"с фотографиями, корзина и оформление.\n\n" +
		"Телефон: " + site.Phone + "\nРаботаем: " + site.Hours
	return api.send(ctx, sendTo{ChatID: chatID}, text, rows)
}

// Сколько кнопок показываем за раз: длинный список в мессенджере
// приходится листать, и нижние строки просто не видно.
const (
	categoriesPerPage = 8
	productsPerPage   = 8
)

func sendCategories(ctx context.Context, api *maxAPI, s botSession, page int) {
	cats, err := listCategories()
	if err != nil {
		send(ctx, api, s, "Каталог сейчас недоступен, попробуйте позже.", nil)
		return
	}
	var named []string
	for _, c := range cats {
		if c != "" {
			named = append(named, c)
		}
	}
	if len(named) == 0 {
		// Категорий нет — показываем всё подряд.
		sendCategory(ctx, api, s, "", 0)
		return
	}

	from, to, pages := pageBounds(len(named), page, categoriesPerPage)
	var rows [][]botButton
	for _, c := range named[from:to] {
		rows = append(rows, []botButton{callbackButton(cut(c, 40), "cat:"+c+":0")})
	}
	if nav := navRow(pageURL("cats", ""), page, pages); len(nav) > 0 {
		rows = append(rows, nav)
	}
	rows = append(rows, []botButton{callbackButton("Всё сразу", "cat::0")})

	text := "Выберите раздел:"
	if pages > 1 {
		text += "\n(страница " + strconv.Itoa(page+1) + " из " + strconv.Itoa(pages) + ")"
	}
	send(ctx, api, s, text, rows)
}

func sendCategory(ctx context.Context, api *maxAPI, s botSession, category string, page int) {
	products, err := listPublishedProducts(category, "")
	if err != nil {
		send(ctx, api, s, "Каталог сейчас недоступен, попробуйте позже.", nil)
		return
	}
	if len(products) == 0 {
		send(ctx, api, s, "В этом разделе пока пусто.",
			[][]botButton{{callbackButton("← Разделы", "catalog")}})
		return
	}

	from, to, pages := pageBounds(len(products), page, productsPerPage)
	var rows [][]botButton
	for _, p := range products[from:to] {
		rows = append(rows, []botButton{callbackButton(
			cut(p.Title(), 34)+" — "+money(p.Price)+"/"+p.Unit, "p:"+i64(p.ID))})
	}
	if nav := navRow(pageURL("cat", category), page, pages); len(nav) > 0 {
		rows = append(rows, nav)
	}
	rows = append(rows, []botButton{callbackButton("← Разделы", "catalog")})

	title := "Каталог"
	if category != "" {
		title = category
	}
	text := title + ": " + strconv.Itoa(len(products)) + " товаров.\nВыберите товар:"
	if pages > 1 {
		text = title + ", товары " + strconv.Itoa(from+1) + "–" + strconv.Itoa(to) +
			" из " + strconv.Itoa(len(products)) + ":"
	}
	send(ctx, api, s, text, rows)
}

// pageBounds — границы страницы и сколько всего страниц.
func pageBounds(total, page, size int) (from, to, pages int) {
	pages = (total + size - 1) / size
	if pages == 0 {
		pages = 1
	}
	if page < 0 {
		page = 0
	}
	if page >= pages {
		page = pages - 1
	}
	from = page * size
	to = from + size
	if to > total {
		to = total
	}
	return from, to, pages
}

// pageURL собирает начало payload для кнопок листания: «cat:Пельмени:».
func pageURL(kind, category string) string {
	if kind == "cats" {
		return "cats:"
	}
	return "cat:" + category + ":"
}

// navRow — строка с кнопками «назад» и «далее», если страниц больше одной.
func navRow(prefix string, page, pages int) []botButton {
	if pages <= 1 {
		return nil
	}
	var row []botButton
	if page > 0 {
		row = append(row, callbackButton("← Назад", prefix+strconv.Itoa(page-1)))
	}
	if page < pages-1 {
		row = append(row, callbackButton("Далее →", prefix+strconv.Itoa(page+1)))
	}
	return row
}

// cut укорачивает подпись кнопки: длинные названия в мессенджере обрезаются
// сами, и цена в конце пропадала бы.
func cut(text string, limit int) string {
	r := []rune(text)
	if len(r) <= limit {
		return text
	}
	return string(r[:limit-1]) + "…"
}

func sendProduct(ctx context.Context, api *maxAPI, s botSession, id int64) {
	p, err := getProduct(id)
	if err != nil || p.Archived || !p.Published {
		send(ctx, api, s, "Такого товара уже нет.",
			[][]botButton{{callbackButton("← Каталог", "catalog")}})
		return
	}
	text := p.Title() + "\n" + money(p.Price) + " за " + p.Unit
	if p.Description != "" {
		text += "\n\n" + p.Description
	}
	text += "\n\nСколько взять?"

	// Готовые количества — круглые: и килограммы, и штуки берут по одному,
	// по два, по три. Полтора килограмма кнопкой предлагали зря: такое
	// количество называют редко, а место в ряду оно занимало.
	// Кому нужно дробное — рядом кнопка «Своё количество».
	steps := []float64{1, 2, 3}
	var row []botButton
	for _, q := range steps {
		row = append(row, callbackButton(qtyText(q)+" "+p.Unit,
			"add:"+i64(p.ID)+":"+formatQty(q)))
	}
	rows := [][]botButton{row,
		{callbackButton("✏️ Своё количество", stateQtyPrefix+i64(p.ID))},
		{callbackButton("🧺 Корзина", "cart"), callbackButton("← Каталог", "catalog")}}
	sendWithPhoto(ctx, api, s, text, photoURL(p), rows)
}

// addToCart принимает «id:количество» из кнопки.
func botAddToCart(ctx context.Context, api *maxAPI, s *botSession, payload string) string {
	parts := strings.SplitN(payload, ":", 2)
	if len(parts) != 2 {
		return ""
	}
	id, _ := strconv.ParseInt(parts[0], 10, 64)
	qty := buyerQty(parseFloat(parts[1]))
	if id <= 0 {
		return ""
	}
	p, err := getProduct(id)
	if err != nil || p.Archived || !p.Published {
		return "Товара больше нет"
	}

	found := false
	for i := range s.Cart {
		if s.Cart[i].ProductID == id {
			s.Cart[i].Qty = buyerQty(s.Cart[i].Qty + qty)
			found = true
			break
		}
	}
	if !found {
		s.Cart = append(s.Cart, botCartLine{ProductID: id, Qty: qty})
	}
	saveSession(*s)
	sendCart(ctx, api, *s)
	return p.Title() + ": +" + qtyText(qty) + " " + p.Unit
}

func botRemoveFromCart(s *botSession, id int64) {
	out := s.Cart[:0]
	for _, l := range s.Cart {
		if l.ProductID != id {
			out = append(out, l)
		}
	}
	s.Cart = out
}

func cartCount(s botSession) float64 { return float64(len(s.Cart)) }

// cartLines превращает корзину в строки с актуальными ценами.
func cartLines(s botSession) []CartLine {
	var lines []CartLine
	for _, l := range s.Cart {
		p, err := getProduct(l.ProductID)
		if err != nil || p.Archived || !p.Published {
			continue
		}
		lines = append(lines, CartLine{Product: p, Qty: l.Qty})
	}
	return lines
}

func sendCart(ctx context.Context, api *maxAPI, s botSession) {
	lines := cartLines(s)
	if len(lines) == 0 {
		send(ctx, api, s, "Корзина пока пуста.",
			[][]botButton{{callbackButton("🛍 Каталог", "catalog")}})
		return
	}
	var sb strings.Builder
	var total float64
	sb.WriteString("Ваш заказ:\n")
	var rows [][]botButton
	for _, l := range lines {
		sum := l.Sum()
		total += sum
		sb.WriteString("• " + l.Product.Title() + " — " + qtyText(l.Qty) + " " +
			l.Product.Unit + " × " + money(l.Product.Price) + " = " + money(sum) + "\n")
		rows = append(rows, []botButton{callbackButton("✕ "+l.Product.Title(),
			"del:"+i64(l.Product.ID))})
	}
	sb.WriteString("\nИтого: " + money(total))
	rows = append(rows,
		[]botButton{positiveButton("✅ Оформить заказ", "checkout")},
		[]botButton{callbackButton("🛍 Добавить ещё", "catalog"),
			negativeButton("Очистить", "clear")})
	send(ctx, api, s, sb.String(), rows)
}

// askOwnQty — покупатель хочет не «полкило и два», а своё число.
func askOwnQty(ctx context.Context, api *maxAPI, s botSession, idText string) {
	id, _ := strconv.ParseInt(idText, 10, 64)
	p, err := getProduct(id)
	if err != nil || p.Archived || !p.Published {
		send(ctx, api, s, "Такого товара уже нет.",
			[][]botButton{{callbackButton("← Каталог", "catalog")}})
		return
	}
	s.State = stateQtyPrefix + i64(id)
	saveSession(s)
	send(ctx, api, s, "Сколько нужно? Напишите целое число в "+p.Unit+
		" — например 4. Меньше одного заказать нельзя.", nil)
}

// applyOwnQty принимает написанное количество и кладёт товар в корзину.
func applyOwnQty(ctx context.Context, api *maxAPI, s botSession, text string) {
	id, _ := strconv.ParseInt(strings.TrimPrefix(s.State, stateQtyPrefix), 10, 64)
	qty := parseFloat(text)
	if qty < 1 || qty > 1000 {
		send(ctx, api, s, "Не понял количество. Напишите целое число не меньше единицы — например 4.", nil)
		return
	}
	qty = buyerQty(qty)
	s.State = stateIdle
	saveSession(s)
	botAddToCart(ctx, api, &s, i64(id)+":"+formatQty(qty))
}

// ------------------------------------------------------------ оформление

func startCheckout(ctx context.Context, api *maxAPI, s botSession) {
	if len(cartLines(s)) == 0 {
		send(ctx, api, s, "Сначала выберите товары.",
			[][]botButton{{callbackButton("🛍 Каталог", "catalog")}})
		return
	}
	// В группе по умолчанию уводим в личку: телефон и адрес не должны
	// оказаться в общем чате. Корзину переносим, чтобы не набирать заново.
	if s.ChatID != 0 {
		moveCartToPrivate(s)
		var rows [][]botButton
		if link := botLink(); link != "" {
			rows = append(rows, []botButton{linkButton("💬 Оформить в личном чате", link)})
		}
		rows = append(rows, []botButton{callbackButton("Оформить прямо здесь", "checkout_here")})
		send(ctx, api, s, "Корзина перенесена в личный чат с ботом — там телефон и адрес "+
			"увидите только вы. Откройте его и нажмите «Оформить».\n\n"+
			"Если удобнее здесь — жмите «Оформить прямо здесь», но имя, телефон и адрес "+
			"увидят все участники группы.", rows)
		return
	}
	// Постоянного покупателя ни о чём не спрашиваем — показываем, что помним,
	// и ждём подтверждения одной кнопкой.
	if c := loadCustomer(s.UserID); c.known() {
		s.Name, s.Phone, s.Address = c.Name, c.Phone, c.Address
		s.State = stateIdle
		saveSession(s)
		text := "Оформляем на эти данные?\n\n" + c.Name + "\n" + formatPhone(c.Phone)
		if c.Address != "" {
			text += "\n" + c.Address
		}
		send(ctx, api, s, text, [][]botButton{
			{positiveButton("✅ Всё верно, оформить", "confirm")},
			{callbackButton("✏️ Изменить данные", "edit")},
			{negativeButton("Отменить", "cancel")},
		})
		return
	}
	askName(ctx, api, s)
}

// askName — первый шаг оформления. Имя из профиля MAX предлагаем кнопкой,
// чтобы не набирать его руками.
func askName(ctx context.Context, api *maxAPI, s botSession) {
	s.State = stateName
	saveSession(s)
	rows := [][]botButton{}
	if name := strings.TrimSpace(s.Name); name != "" {
		rows = append(rows, []botButton{callbackButton("Я "+name, "name_ok")})
	}
	rows = append(rows, []botButton{negativeButton("Отменить", "cancel")})
	send(ctx, api, s, "Оформляем заказ. Как вас зовут?", rows)
}

// askPhone — телефон одной кнопкой: мессенджер пришлёт его сам.
func askPhone(ctx context.Context, api *maxAPI, s botSession) {
	s.State = statePhone
	saveSession(s)
	send(ctx, api, s, "Телефон для связи. Нажмите кнопку — номер отправится сам, "+
		"или напишите его вручную.", [][]botButton{
		{contactButton("📱 Отправить мой номер")},
		{negativeButton("Отменить", "cancel")},
	})
}

// acceptPhone — телефон принят (кнопкой или текстом), спрашиваем адрес.
func acceptPhone(ctx context.Context, api *maxAPI, s botSession, phone string) {
	s.Phone = phone
	s.State = stateAddress
	saveSession(s)
	rows := [][]botButton{}
	if c := loadCustomer(s.UserID); c.Address != "" {
		rows = append(rows, []botButton{callbackButton("📍 "+c.Address, "last_address")})
	}
	rows = append(rows,
		[]botButton{callbackButton("Самовывоз", "pickup")},
		[]botButton{negativeButton("Отменить", "cancel")})
	send(ctx, api, s, "Куда доставить? Напишите адрес или выберите вариант.", rows)
}

// moveCartToPrivate копирует корзину из группы в личный разговор.
// В группе она остаётся: человек может передумать и оформить прямо там.
func moveCartToPrivate(s botSession) {
	if len(s.Cart) == 0 {
		return
	}
	p := loadSession(s.UserID, 0)
	p.Cart = s.Cart
	p.State = stateIdle
	if p.Name == "" {
		p.Name = s.Name
	}
	saveSession(p)
}

func continueCheckout(ctx context.Context, api *maxAPI, s botSession, text string) {
	switch s.State {
	case stateName:
		s.Name = text
		saveSession(s)
		askPhone(ctx, api, s)

	case statePhone:
		phone := normalizePhone(text)
		if !validPhone(phone) {
			send(ctx, api, s, "Не похоже на телефон. Напишите в формате "+
				"+7 (999) 123-45-67 или нажмите «Отправить мой номер».",
				[][]botButton{{contactButton("📱 Отправить мой номер")}})
			return
		}
		acceptPhone(ctx, api, s, phone)

	case stateAddress:
		s.Address = text
		finishOrder(ctx, api, s)
	}
}

func finishOrder(ctx context.Context, api *maxAPI, s botSession) {
	lines := cartLines(s)
	if len(lines) == 0 {
		send(ctx, api, s, "Корзина опустела — выберите товары заново.",
			[][]botButton{{callbackButton("🛍 Каталог", "catalog")}})
		return
	}
	if !validPhone(s.Phone) {
		s.State = statePhone
		saveSession(s)
		send(ctx, api, s, "Нужен телефон для связи: +7 (999) 123-45-67", nil)
		return
	}

	delivery := "Доставка"
	if s.Address == "Самовывоз" {
		delivery = "Самовывоз"
	}
	o := Order{
		ClientName: strings.TrimSpace(s.Name),
		Phone:      s.Phone,
		Address:    s.Address,
		Delivery:   delivery,
		PayMethod:  "Наличные при получении",
		Status:     StatusNew,
		Source:     sourceMax,
		Comment:    "Заказ из мессенджера MAX",
	}

	// Клиента заводим по телефону — так же, как при заказе с витрины.
	if c, err := getClientByPhone(s.Phone); err == nil {
		if c.Blocked {
			send(ctx, api, s, "Не получается оформить заказ. Позвоните нам: "+siteText().Phone, nil)
			return
		}
		o.ClientID, o.ClientName = &c.ID, c.FullName()
	} else {
		nc := Client{
			Phone: s.Phone, FirstName: strings.TrimSpace(s.Name), Address: s.Address,
			Segment: "Новый", Source: "MAX", Note: "Заведён автоматически ботом MAX",
		}
		if err := insertClient(&nc); err == nil {
			o.ClientID = &nc.ID
			if o.ClientName == "" {
				o.ClientName = nc.FullName()
			}
		}
	}

	if err := createOrder(&o, lines); err != nil {
		logErr("заказ из бота", err)
		send(ctx, api, s, "Не получилось сохранить заказ. Позвоните нам: "+siteText().Phone, nil)
		return
	}

	// Запоминаем, чтобы в следующий раз ничего не спрашивать.
	saveCustomer(botCustomer{UserID: s.UserID, ClientID: o.ClientID,
		Name: o.ClientName, Phone: s.Phone, Address: s.Address})

	clearSession(s)
	s.Cart, s.State = nil, stateIdle
	head := "Заказ " + o.Number() + " принят!"
	if o.Merged {
		// Товары дописались в уже открытый заказ — сумма больше корзины,
		// и об этом лучше сказать сразу.
		head = "Добавили в ваш заказ " + o.Number() + "."
	}
	text := head + "\nСумма: " + money(o.Total) +
		"\nМы перезвоним на " + formatPhone(o.Phone) + " для подтверждения."
	send(ctx, api, s, text, [][]botButton{{callbackButton("🛍 Заказать ещё", "catalog")}})
	log.Printf("бот MAX: заказ %s на %s", o.Number(), money(o.Total))
}

// ------------------------------------------------------------ мелочи

func qtyText(v float64) string { return formatQty(v) }

// shopURL — адрес витрины для кнопки «Открыть магазин».
func shopURL() string {
	return strings.TrimRight(publicURL, "/")
}
