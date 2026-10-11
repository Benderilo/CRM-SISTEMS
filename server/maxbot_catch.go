package main

import (
	"log"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// Перехват сообщений: как переписка превращается в заявку.
//
// По кнопкам ходят не все. Пишут одной строкой — «привезите 3 кг пельменей на
// Ленина 5, 8 900 123-45-67», пишут без телефона — «можно на пробу тушёнку и
// кашу», а продавец пересылает такие сообщения в рабочую группу. Раньше всё
// это до CRM не доходило: бот откликался только на слова-обращения.
//
// Теперь бот читает каждое сообщение — своё, пересланное и пропущенное, пока
// не было связи (см. maxbot_history.go), — и молча заводит заявку:
//
//   - есть телефон: находит клиента по номеру, нового заводит сам;
//   - нет телефона, но есть слово заказа: заявка без номера, с именем из
//     профиля MAX — продавец отвечает человеку прямо в мессенджере;
//   - человек уже писал: сообщение дописывается в его открытую заявку,
//     вторая не появляется.
//
// Само сообщение целиком ложится в комментарий заказа: в приложении его там
// же и правят. В переписку бот не вмешивается — ни в личке, ни в группе про
// заявку не пишет ни слова.

// catchOn — включён ли перехват. Значение живёт рядом с настройками бота и
// меняется на лету, поэтому держим его отдельно от разговоров.
var catchOn atomic.Bool

func setCatchPhones(v bool) { catchOn.Store(v) }

// isListSep — чем в приложении разделяют списки: запятой, точкой с запятой
// или новой строкой (перевод строки и табуляция — тоже управляющие знаки).
func isListSep(r rune) bool {
	return r == ',' || r == ';' || r < ' '
}

// Номера, которые перехват пропускает.
//
// Продавцы и админы сами пишут в группе свои телефоны — «звоните мне,
// 8 900 …», и каждый такой раз заводилась заявка на самого себя. Список
// задаётся в приложении, на экране «Бот MAX».
var (
	ignoreMu    sync.RWMutex
	ignoreList  []string
	ignoreIndex = map[string]bool{}
)

// parsePhoneList разбирает список номеров из приложения: они приходят в любом
// виде, через запятую или с новой строки. Всё, что на телефон не похоже,
// отбрасываем — иначе в списке копился бы мусор, а повторы схлопываем.
func parsePhoneList(list string) []string {
	var out []string
	seen := map[string]bool{}
	for _, part := range strings.FieldsFunc(list, isListSep) {
		p := normalizePhone(part)
		if !validPhone(p) || seen[p] {
			continue
		}
		seen[p] = true
		out = append(out, p)
	}
	return out
}

// setIgnorePhones — новый список исключений, применяется на лету.
func setIgnorePhones(list string) {
	out := parsePhoneList(list)
	index := make(map[string]bool, len(out))
	for _, p := range out {
		index[p] = true
	}
	ignoreMu.Lock()
	ignoreList, ignoreIndex = out, index
	ignoreMu.Unlock()
}

// currentIgnorePhones — список для показа в приложении, уже приведённый
// к одному виду.
func currentIgnorePhones() []string {
	ignoreMu.RLock()
	defer ignoreMu.RUnlock()
	out := make([]string, len(ignoreList))
	copy(out, ignoreList)
	return out
}

// prettyPhoneList — список для экрана в приложении: по номеру в строке,
// в привычном виде «+7 (900) 123-45-67».
func prettyPhoneList(phones []string) string {
	out := make([]string, 0, len(phones))
	for _, p := range phones {
		out = append(out, formatPhone(p))
	}
	return strings.Join(out, "\n")
}

// ignoredPhone — этот номер перехват пропускает.
func ignoredPhone(phone string) bool {
	ignoreMu.RLock()
	defer ignoreMu.RUnlock()
	return ignoreIndex[phone]
}

// ------------------------------------------------------------ слова заказа

// Слова, по которым узнаём заказ без телефона.
//
// Сравниваем по началу слова, поэтому «заказ» подходит и «заказать», и
// «заказываю». Список правится в приложении: в тихой группе можно оставить
// широкие слова («можно», «хочу»), в болтливой — вычеркнуть их и оставить
// только «заказ» и «привезите».
var defaultOrderWords = []string{
	"заказ", "закаж", "оформ", "привез", "привоз", "достав",
	"купи", "куплю", "беру", "брать", "возьм",
	"нужно", "надо", "хочу", "хотим", "можно",
}

var (
	orderMu    sync.RWMutex
	orderWords = defaultOrderWords
)

// setOrderWords принимает список из приложения: «заказ, привезите, можно».
func setOrderWords(list string) {
	var words []string
	for _, w := range strings.FieldsFunc(strings.ToLower(list), isListSep) {
		if w = strings.TrimSpace(w); len([]rune(w)) >= 3 {
			words = append(words, w)
		}
	}
	orderMu.Lock()
	if len(words) == 0 {
		orderWords = defaultOrderWords
	} else {
		orderWords = words
	}
	orderMu.Unlock()
}

// currentOrderWords — слова заказа для показа в приложении.
func currentOrderWords() []string {
	orderMu.RLock()
	defer orderMu.RUnlock()
	out := make([]string, len(orderWords))
	copy(out, orderWords)
	return out
}

// hasOrderWord — похоже ли сообщение на заказ.
func hasOrderWord(text string) bool {
	orderMu.RLock()
	defer orderMu.RUnlock()
	for _, w := range splitWords(text) {
		for _, stem := range orderWords {
			if strings.HasPrefix(w, stem) {
				return true
			}
		}
	}
	return false
}

// -------------------------------------------------------- свои за прилавком

// Администраторы рабочей группы — это продавцы. Их собственные сообщения
// («девочки, привезли свежую партию») заявками не становятся: заказ на самого
// себя никому не нужен. Пересланного покупателя это не касается — там автором
// считается тот, чьё сообщение переслали.
var (
	staffMu sync.RWMutex
	staff   = map[int64]bool{}
)

func setChatStaff(ids []int64) {
	m := make(map[int64]bool, len(ids))
	for _, id := range ids {
		m[id] = true
	}
	staffMu.Lock()
	staff = m
	staffMu.Unlock()
}

func isChatStaff(userID int64) bool {
	staffMu.RLock()
	defer staffMu.RUnlock()
	return staff[userID]
}

// ------------------------------------------------------------ заявка

// lead — сообщение, из которого может получиться заявка.
type lead struct {
	UserID   int64  // кто написал; для пересланного — чьё сообщение переслали
	Name     string // имя из профиля MAX
	Username string // @имя: по нему продавец найдёт человека в мессенджере
	Text     string
	At       int64  // время сообщения, мс
	Via      string // кто переслал, если сообщение пересланное
	Edited   bool   // человек исправил уже отправленное сообщение
}

// maxCatchComment — сколько букв сообщения кладём в комментарий. Длинную
// простыню продавец в карточке заказа всё равно не прочитает, а телефон и
// суть просьбы обычно в первых строках.
const maxCatchComment = 1000

// catchLead разбирает сообщение и заводит по нему заявку.
//
// Ничего не отвечает и не бросает ошибок наружу: перехват — дело фоновое,
// разговор с покупателем от него зависеть не должен.
func catchLead(l lead) {
	if !catchOn.Load() || strings.TrimSpace(l.Text) == "" {
		return
	}
	if l.At == 0 {
		l.At = nowMs()
	}

	// Эхо собственного сообщения бота — в заявку оно точно не годится.
	if l.UserID != 0 && l.UserID == botSelfID() {
		return
	}
	// Сообщения продавцов — тоже. Они и объявления пишут со своим номером
	// («домашняя тушёнка, звоните 8 927 …»), и на вопросы отвечают: заказом
	// это не является. Чтобы завести заявку от их имени, сообщение покупателя
	// достаточно переслать — автором считается он.
	if isChatStaff(l.UserID) {
		return
	}

	// Заявку, которую продавец удалил, правка сообщения не воскрешает.
	// Удалил — значит решил, что заявки быть не должно; а правка, в отличие
	// от нового сообщения, читается по тому же id второй раз и завела бы
	// удалённую заявку заново.
	if l.Edited && leadOrderGone(l.UserID) {
		log.Printf("бот MAX: правка сообщения от %s пропущена — заявку удалили",
			l.Name)
		return
	}

	// Что об этом сообщении думает модель (см. maxbot_ai.go). Пока она
	// отвечает — решает она; молчит — ниже работают прежние правила по словам.
	v, gotAI := askAI(l.Text)
	if gotAI && !v.Order && v.Confidence >= aiSureNo {
		// Благодарность, вопрос о цене, разговор ни о чём. Заявки нет, даже
		// если человек заодно написал свой номер: раньше такое сообщение
		// заводило пустой заказ на каждого, кто оставил в группе телефон.
		return
	}

	phone := findPhone(l.Text)
	if phone != "" {
		// Свой же номер из контактов магазина и номера из списка исключений
		// заявкой не считаем: их переписывают друг другу в группе.
		if phone == normalizePhone(siteText().Phone) || ignoredPhone(phone) {
			return
		}
	} else {
		// Телефона нет. Заявку заводим, если модель увидела заказ, а без
		// неё — по словам заказа, как было раньше.
		if !(gotAI && v.Order) && !hasOrderWord(l.Text) {
			return
		}
		// Знакомого покупателя узнаём по его MAX: телефон уже известен.
		if c := loadCustomer(l.UserID); validPhone(c.Phone) && !ignoredPhone(c.Phone) {
			phone = c.Phone
		}
	}

	// Имя из профиля MAX бывает пустым — особенно у пересланных сообщений.
	// Тогда годится то, которым человек назвался сам: «это Ирина, мне бы…».
	if strings.TrimSpace(l.Name) == "" && v.Name != "" {
		l.Name = v.Name
	}

	// Телефона по-прежнему нет. Сначала смотрим, не связывали ли мы этот MAX
	// с карточкой раньше: это надёжно, человек опознан по своему ID.
	var hints []Client
	if phone == "" {
		if c, ok := knownLeadClient(l.UserID); ok {
			phone = c.Phone
		} else if found, sure := matchClientsByName(l.Name); sure {
			// Первая встреча: остаётся имя. Привязываем, только когда оно
			// совпало целиком и такой человек в базе один.
			phone = found[0].Phone
			log.Printf("бот MAX: %s опознан(а) в базе по имени", l.Name)
		} else {
			hints = found
		}
	}

	client, ok := leadClient(phone, l.Name)
	if !ok {
		return // заблокированный покупатель или клиент не завёлся
	}
	// Чтобы в следующий раз не искать: запоминаем, чей это MAX, и сообщение
	// без телефона сразу ляжет в нужную карточку.
	if client != nil {
		rememberLeadClient(l.UserID, client.ID)
	}

	ready, rest := aiSplitItems(v)
	comment := catchComment(l, phone == "")
	if note := aiNote(v, ready, rest); note != "" {
		comment += "\n" + note
	}
	if hint := clientHint(hints); hint != "" {
		comment += "\n" + hint
	}

	// Открытую заявку того же человека не дублируем — дописываем в неё.
	// Ищем и по телефону, и по его MAX: сообщение без номера должно попасть
	// в ту же заявку, что и предыдущее с номером.
	if target, found := openLeadOrder(phone, l.UserID); found {
		if err := appendOrderComment(target, comment); err != nil {
			logErr("комментарий к заявке", err)
			return
		}
		// Телефон и карточка клиента могли появиться только сейчас —
		// дописываем их в заявку, заведённую раньше без номера.
		if phone != "" && target.Phone == "" {
			logErr("телефон в заявке", attachPhone(target, phone, client))
		}
		// Человек дозаказал — товар уходит в ту же заявку. Количество
		// складывается с тем, что уже есть: addOrderItem сам это делает.
		addLeadItems(target.ID, ready)
		rememberLead(l.UserID, target.ID)
		// Правки бота отмечаем как свои: иначе сверка (maxbot_recheck.go)
		// приняла бы их за работу продавца и прекратилась бы.
		if fresh, err := getOrder(target.ID); err == nil {
			botTouched(target.ID, fresh.UpdatedAt)
		}
		log.Printf("бот MAX: сообщение дописано в заявку %s", target.Number())
		return
	}

	// Состав заявки — то, что бот опознал в каталоге; остальное продавец
	// допишет сам, у него в комментарии и сообщение, и разбор. Склад
	// помечаем списанным, как у обычного заказа из MAX: дописанные потом
	// позиции уйдут со склада, а отмена вернёт их обратно.
	o := Order{
		ClientName: strings.TrimSpace(l.Name),
		Phone:      phone,
		Status:     StatusNew,
		Source:     sourceMax,
		Comment:    comment,
	}
	if client != nil {
		o.ClientID, o.ClientName, o.Address = &client.ID, client.FullName(), client.Address
	}
	// Адрес из самого сообщения — когда в карточке клиента его ещё нет.
	// Записанный в карточке не трогаем: он проверен продавцом, а этот
	// вычитан из переписки.
	if strings.TrimSpace(o.Address) == "" && v.Address != "" {
		o.Address = v.Address
	}
	if err := createOrderItems(&o, ready, true); err != nil {
		logErr("заявка по перехваченному сообщению", err)
		return
	}
	rememberLead(l.UserID, o.ID)
	// Берём заявку под сверку: бот вернётся к ней и проверит, не разошёлся
	// ли состав с тем, что человек просил дальше в переписке.
	watchOrder(o.ID, o.UpdatedAt)

	if phone == "" {
		log.Printf("бот MAX: заявка %s без телефона, от %s", o.Number(), l.Name)
		return
	}
	log.Printf("бот MAX: заявка %s по сообщению с %s", o.Number(), formatPhone(phone))
}

// --------------------------------------------------- сверка с базой клиентов

// maxHints — сколько подсказок показываем продавцу. Больше трёх — это уже
// не подсказка, а список, по которому всё равно придётся искать руками.
const maxHints = 3

// matchClientsByName ищет человека в базе по имени из профиля MAX.
//
// Это последняя попытка, а не основной способ. Человека узнают по телефону
// или по его MAX (bot_customers.user_id) — вот надёжные приметы. Имя идёт в
// ход, только когда ни того, ни другого нет: покупатель может годами брать
// через приложение и ни разу не назвать номер боту, и тогда в CRM он есть,
// а заявка висит с пустым телефоном — так вышло с заявкой №0143 от
// «Александры Олеговны».
//
// Второе значение — можно ли привязать карточку молча. Тёзок много, поэтому
// одного совпавшего слова мало: нужно не меньше двух («Иванова Ирина»,
// «Александра Олеговна») и единственный такой человек в базе. Всё остальное
// уходит продавцу подсказкой: ошибиться карточкой хуже, чем не угадать —
// чужой телефон в заявке значит звонок не тому человеку.
//
// Один раз опознанного бот запоминает по его MAX (rememberCustomer), так что
// имя нужно только до первой встречи.
func matchClientsByName(name string) ([]Client, bool) {
	words := nameWords(name)
	if len(words) == 0 {
		return nil, false
	}
	// По одному слову не привязываем никогда — только предлагаем.
	sure := len(words) >= 2
	clients, err := listClients("")
	if err != nil {
		logErr("поиск клиента по имени", err)
		return nil, false
	}

	var exact, partial []Client
	for _, c := range clients {
		// Без номера карточка заявке ничем не поможет, а заблокированным
		// и архивным заказы не оформляются.
		if c.Blocked || c.Archived || !validPhone(c.Phone) {
			continue
		}
		switch hits := nameHits(words, nameWords(c.FullName())); {
		case hits == len(words):
			exact = append(exact, c)
		case hits > 0:
			partial = append(partial, c)
		}
	}
	if sure && len(exact) == 1 {
		return exact, true
	}
	found := append(exact, partial...)
	if len(found) > maxHints {
		found = found[:maxHints]
	}
	return found, false
}

// nameWords — слова имени, годные для сверки. Короткие отбрасываем: по «ев»
// совпал бы кто угодно.
func nameWords(name string) []string {
	var out []string
	for _, w := range splitWords(name) {
		if len([]rune(w)) >= 4 {
			out = append(out, w)
		}
	}
	return out
}

// nameHits — сколько слов из want нашлось в have. Сверяем по началу слова:
// в MAX человек записан «Александра Олеговна», в базе — «Александра», а
// падежи и отчества у одного и того же человека пишут по-разному.
func nameHits(want, have []string) int {
	hits := 0
	for _, w := range want {
		stem := w
		if r := []rune(w); len(r) > 4 {
			stem = string(r[:4])
		}
		for _, h := range have {
			if strings.HasPrefix(h, stem) {
				hits++
				break
			}
		}
	}
	return hits
}

// clientHint — подсказка продавцу: возможно, это уже знакомый покупатель.
func clientHint(found []Client) string {
	if len(found) == 0 {
		return ""
	}
	parts := make([]string, 0, len(found))
	for _, c := range found {
		parts = append(parts, c.FullName()+" "+formatPhone(c.Phone))
	}
	return "Возможно, это клиент из базы (проверьте): " + strings.Join(parts, "; ")
}

// rememberLeadClient запоминает, чей это MAX. Следующее сообщение без номера
// попадёт в ту же карточку само — искать по имени уже не придётся.
//
// Пишем в bot_known, а не в bot_customers: второе бот показывает покупателю
// при оформлении («оформляем на эти данные?»), и подставлять туда телефон,
// который человек боту не называл, нельзя.
func rememberLeadClient(userID, clientID int64) {
	if userID == 0 || clientID == 0 {
		return
	}
	_, err := db.Exec(`INSERT INTO bot_known (user_id, client_id, at) VALUES (?,?,?)
      ON CONFLICT(user_id) DO UPDATE SET client_id = excluded.client_id,
        at = excluded.at`, userID, clientID, nowMs())
	logErr("карточка покупателя из MAX", err)
}

// knownLeadClient — карточка, которую перехват уже связал с этим MAX.
// Это и есть надёжное опознание: имя нужно только до первой встречи.
func knownLeadClient(userID int64) (Client, bool) {
	if userID == 0 {
		return Client{}, false
	}
	var clientID int64
	if err := db.QueryRow(`SELECT client_id FROM bot_known WHERE user_id = ?`,
		userID).Scan(&clientID); err != nil {
		return Client{}, false
	}
	c, err := getClient(clientID)
	if err != nil || c.Blocked || c.Archived || !validPhone(c.Phone) {
		return Client{}, false
	}
	return c, true
}

// addLeadItems дописывает опознанные товары в уже открытую заявку.
// Ошибку одной позиции не считаем поводом бросить остальные: заявка уже
// есть, и продавец скорее доберёт недостающее, чем заметит пропажу.
func addLeadItems(orderID int64, items []OrderItem) {
	for _, it := range items {
		if it.ProductID == nil {
			continue
		}
		logErr("товар в заявку", addOrderItem(orderID, *it.ProductID, it.Qty))
	}
}

// leadClient — клиент для заявки: найденный по телефону, заведённый заново
// или никакой, если телефона нет. Второе значение — можно ли заводить заявку.
func leadClient(phone, name string) (*Client, bool) {
	if phone == "" {
		return nil, true // заявка без карточки: телефон продавец узнает сам
	}
	c, err := getClientByPhone(phone)
	if err != nil {
		c = Client{
			Phone: phone, FirstName: strings.TrimSpace(name),
			Segment: "Новый", Source: "MAX",
			Note: "Заведён автоматически: бот перехватил сообщение с номером",
		}
		if err := insertClient(&c); err != nil {
			logErr("клиент по перехваченному сообщению", err)
			return nil, false
		}
		log.Printf("бот MAX: перехвачен новый клиент %s", formatPhone(phone))
		return &c, true
	}
	if c.Blocked {
		// Заблокированному заказы не оформляются ни на сайте, ни в боте —
		// и заявок от него в CRM тоже быть не должно.
		return nil, false
	}
	// Клиент есть, но безымянный: имя из профиля MAX лучше, чем ничего.
	if strings.TrimSpace(c.FullName()) == c.Phone && strings.TrimSpace(name) != "" {
		c.FirstName = strings.TrimSpace(name)
		logErr("имя клиента из MAX", updateClient(&c))
	}
	return &c, true
}

// catchComment — что увидит продавец в карточке заказа.
func catchComment(l lead, noPhone bool) string {
	what := "Сообщение из MAX "
	if l.Edited {
		// Про правку надо сказать прямо: ниже в комментарии стоит прежний,
		// уже неверный вариант заказа, и продавец должен понять, какой из
		// двух настоящий.
		what = "Покупатель исправил сообщение, новый текст — "
	}
	head := what + msk(l.At).Format("02.01.2006 15:04")
	if name := strings.TrimSpace(l.Name); name != "" {
		head += ", " + name
	}
	if u := strings.TrimSpace(l.Username); u != "" {
		head += " (@" + u + ")"
	}
	if via := strings.TrimSpace(l.Via); via != "" {
		head += ", переслал(а) " + via
	}

	body := strings.TrimSpace(l.Text)
	if r := []rune(body); len(r) > maxCatchComment {
		body = strings.TrimSpace(string(r[:maxCatchComment])) + "…"
	}
	out := head + ":\n" + body
	if noPhone {
		out += "\nТелефона в сообщении нет — ответьте человеку в MAX."
	}
	return out
}

// openLeadOrder — открытая заявка этого человека, куда дописать сообщение.
//
// Сначала по телефону (так же, как объединяются заказы с сайта), потом по его
// MAX: заявка могла появиться раньше, когда номера ещё не знали.
func openLeadOrder(phone string, userID int64) (Order, bool) {
	if phone != "" {
		if o, ok := findOpenOrderByPhone(phone); ok {
			return o, true
		}
	}
	if userID == 0 {
		return Order{}, false
	}
	var orderID int64
	if err := db.QueryRow(`SELECT order_id FROM bot_leads WHERE user_id = ?`,
		userID).Scan(&orderID); err != nil {
		return Order{}, false
	}
	o, err := getOrder(orderID)
	if err != nil || !mergeable(o) {
		return Order{}, false // заявку закрыли или удалили — заведём новую
	}
	return o, true
}

// leadOrderGone — заявка этого человека была, и её удалили.
//
// Именно удалили: закрытая или отменённая заявка здесь не в счёт, она
// на месте, и следующее сообщение заведёт новую — так и нужно.
func leadOrderGone(userID int64) bool {
	if userID == 0 {
		return false
	}
	var orderID int64
	if err := db.QueryRow(`SELECT order_id FROM bot_leads WHERE user_id = ?`,
		userID).Scan(&orderID); err != nil {
		return false // этот человек нам ещё не писал
	}
	_, err := getOrder(orderID)
	return err != nil
}

// rememberLead запоминает, в какой заявке ждут этого человека.
func rememberLead(userID, orderID int64) {
	if userID == 0 || orderID == 0 {
		return
	}
	_, err := db.Exec(`INSERT INTO bot_leads (user_id, order_id, at) VALUES (?,?,?)
      ON CONFLICT(user_id) DO UPDATE SET order_id = excluded.order_id, at = excluded.at`,
		userID, orderID, nowMs())
	logErr("заявка покупателя из MAX", err)
}

// attachPhone дописывает в заявку телефон и карточку клиента, когда человек
// назвал номер уже после того, как заявка появилась.
func attachPhone(o Order, phone string, client *Client) error {
	var clientID *int64
	name := o.ClientName
	if client != nil {
		clientID = &client.ID
		if strings.TrimSpace(name) == "" {
			name = client.FullName()
		}
	}
	_, err := db.Exec(`UPDATE orders SET phone = ?, client_id = COALESCE(?, client_id),
      client_name = ?, updated_at = ? WHERE id = ?`,
		phone, clientID, name, nowMs(), o.ID)
	return err
}

// appendOrderComment дописывает сообщение в комментарий заказа.
// Статус и сумму не трогаем: человек ничего не заказал заново, а лишь
// добавил просьбу к тому, что продавец уже видит.
func appendOrderComment(o Order, text string) error {
	_, err := db.Exec(`UPDATE orders SET comment = ?, updated_at = ? WHERE id = ?`,
		joinComments(o.Comment, text), nowMs(), o.ID)
	return err
}

// --------------------------------------------------------- уже разобранное

// markSeen отмечает сообщение разобранным и говорит, впервые ли мы его видим.
// По одному и тому же mid заявка заводится единожды — иначе догон истории
// (maxbot_history.go) повторил бы всё, что уже разобрал опрос.
func markSeen(mid string) bool {
	if strings.TrimSpace(mid) == "" {
		return true // без id проверить нечем — разбираем
	}
	res, err := db.Exec(`INSERT OR IGNORE INTO bot_seen (mid, at) VALUES (?,?)`,
		mid, nowMs())
	if err != nil {
		logErr("отметка сообщения бота", err)
		return true
	}
	n, _ := res.RowsAffected()
	return n > 0
}

// purgeSeenMessages чистит отметки старше недели: догон дальше суток не ходит,
// а таблица иначе росла бы вечно.
func purgeSeenMessages() {
	_, err := db.Exec(`DELETE FROM bot_seen WHERE at < ?`, nowMs()-7*24*60*60*1000)
	logErr("уборка отметок бота", err)
}

// msk — время по Москве: сервер живёт в UTC, а продавец в карточке заказа
// ждёт своё местное время.
func msk(ms int64) time.Time { return time.UnixMilli(ms).In(moscow) }

// ------------------------------------------------------------ поиск номера

// findPhone достаёт из сообщения первый российский номер.
//
// Ищем цепочки цифр, разрешая внутри разделители из настоящих телефонов:
// «8 (900) 123-45-67», «+7.900.1234567», «89001234567» — это один номер.
// Буквы, запятые и переводы строки цепочку обрывают, поэтому «5 кг» и
// «заказ на 20» номером не станут.
func findPhone(text string) string {
	var digits strings.Builder
	var found string

	check := func() bool {
		p := phoneFromDigits(digits.String())
		digits.Reset()
		if p != "" {
			found = p
			return true
		}
		return false
	}

	for _, r := range text {
		switch {
		case r >= '0' && r <= '9':
			digits.WriteRune(r)
		case isPhoneSep(r):
			// Разделитель цепочку не рвёт: «8 900 12 34 567» — один номер.
		default:
			if check() {
				return found
			}
		}
	}
	check()
	return found
}

// isPhoneSep — знак, который встречается внутри записанного номера.
// Запятая и перевод строки сюда не входят: после них идёт уже другое число.
// 0x00a0 — неразрывный пробел: его подставляют телефоны и почтовые клиенты.
func isPhoneSep(r rune) bool {
	switch r {
	case ' ', 0x00a0, '-', '–', '—', '(', ')', '+', '.', '/':
		return true
	}
	return false
}

// phoneFromDigits — цепочка цифр, если она похожа на телефон.
//
// Номер часто слипается с соседним числом: «Строительная 28. 89378118853» —
// точка и пробел внутри номера разделители, поэтому в цепочке оказывается
// тринадцать цифр. Пробуем ещё начало и конец цепочки: телефон почти всегда
// стоит с краю. Середину не трогаем — иначе номером станет кусок карты.
func phoneFromDigits(d string) string {
	if p := exactPhone(d); p != "" {
		return p
	}
	for _, n := range []int{11, 10} {
		if len(d) <= n {
			continue
		}
		if p := exactPhone(d[:n]); p != "" {
			return p
		}
		if p := exactPhone(d[len(d)-n:]); p != "" {
			return p
		}
	}
	return ""
}

// exactPhone — цепочка ровно в телефон: одиннадцать цифр с 7 или 8 в начале
// (номер с кодом страны) или десять с девятки (мобильный без кода). Всё
// остальное — годы, суммы, номера домов — телефоном не считаем.
func exactPhone(d string) string {
	switch {
	case len(d) == 11 && (d[0] == '7' || d[0] == '8'):
		return "+7" + d[1:]
	case len(d) == 10 && d[0] == '9':
		return "+7" + d
	}
	return ""
}

// rememberOrderClient — продавец связал заявку с карточкой клиента.
//
// Бот запоминает, чей это MAX, и со следующего сообщения узнаёт человека по
// его ID: без имён, подсказок и догадок. Так каждая правка продавца делает
// опознание точнее — исправлять одно и то же второй раз не придётся.
//
// Ищем по bot_leads в обратную сторону: в какой заявке ждали этого человека.
// Заявка не бота или уже забытая — просто ничего не запоминаем.
func rememberOrderClient(o Order, clientID int64) {
	if o.Source != sourceMax || clientID == 0 {
		return
	}
	var userID int64
	if err := db.QueryRow(`SELECT user_id FROM bot_leads WHERE order_id = ?`,
		o.ID).Scan(&userID); err != nil {
		return
	}
	rememberLeadClient(userID, clientID)
	log.Printf("бот MAX: покупатель из заявки %s связан с карточкой клиента",
		o.Number())
}
