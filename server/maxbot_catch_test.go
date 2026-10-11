package main

import (
	"context"
	"strings"
	"testing"
)

// Какие числа в переписке считаем телефоном, а какие нет.
func TestFindPhone(t *testing.T) {
	cases := []struct{ text, want string }{
		{"привезите 3 кг пельменей, 8 900 123-45-67", "+79001234567"},
		{"мой номер +7 (900) 123-45-67", "+79001234567"},
		{"89001234567", "+79001234567"},
		{"звоните 9001234567", "+79001234567"},
		{"тел. 8-900-123-45-67, домой к 18:00", "+79001234567"},
		{"городской 84957891234", "+74957891234"},
		// Номер слипся с номером дома — так пишут адрес с телефоном подряд.
		{"с. Натальино Строительная 28. 89378118853", "+79378118853"},
		{"89378118853 дом 28", "+79378118853"},
		// Числа из обычного разговора телефоном не считаем.
		{"нужно 5 кг фарша и 2 палки колбасы", ""},
		{"буду 20.08.2026 в 15:30", ""},
		{"заказ на 1200 рублей", ""},
		{"карта 4276 1234 5678 9012", ""},
		{"", ""},
	}
	for _, c := range cases {
		if got := findPhone(c.text); got != c.want {
			t.Errorf("findPhone(%q) = %q, ожидалось %q", c.text, got, c.want)
		}
	}
}

// Человек написал заказ одной строкой, бота не позвал. Бот молчит,
// но в CRM появляются клиент и заявка с текстом сообщения в комментарии.
func TestCatchLeadFromPrivate(t *testing.T) {
	openTestDB(t)
	resetSiteCache()
	setCatchPhones(true)
	t.Cleanup(func() { setCatchPhones(false) })
	f := newFakeMax(t)
	api := newMaxAPI("test-token")
	ctx := context.Background()

	const text = "Здравствуйте! Привезите 3 кг пельменей на Ленина 5, " +
		"телефон 8 900 123-45-67"
	handleBotUpdate(ctx, api, textUpdate(777, 0, text))

	c, err := getClientByPhone("+79001234567")
	if err != nil {
		t.Fatalf("клиент не заведён: %v", err)
	}
	if c.Source != "MAX" || c.Segment != "Новый" {
		t.Errorf("карточка клиента заполнена неверно: %#v", c)
	}
	if c.FirstName != "Покупатель" {
		t.Errorf("имя из профиля MAX не перенесено: %q", c.FirstName)
	}

	orders, err := listOrders(-1, "")
	if err != nil || len(orders) != 1 {
		t.Fatalf("заказов %d (err %v), ожидался один", len(orders), err)
	}
	o := orders[0]
	if o.ClientID == nil || *o.ClientID != c.ID {
		t.Errorf("заявка не привязана к клиенту: %#v", o.ClientID)
	}
	if o.Status != StatusNew || o.Source != sourceMax || o.Total != 0 {
		t.Errorf("заявка заведена неверно: %#v", o)
	}
	if !strings.Contains(o.Comment, "Привезите 3 кг пельменей") {
		t.Errorf("сообщение не попало в комментарий: %q", o.Comment)
	}
	if !strings.Contains(o.Comment, "Сообщение из MAX") {
		t.Errorf("в комментарии нет пометки о перехвате: %q", o.Comment)
	}

	// Главное: про заявку бот покупателю не сказал ни слова. Обычный ответ
	// в личке остаётся — там бот отвечает на всё, но заказ в нём не всплывает.
	for _, m := range f.messages {
		if strings.Contains(m.Text, o.Number()) || strings.Contains(m.Text, "заявк") {
			t.Fatalf("бот проговорился о перехвате: %q", m.Text)
		}
	}
}

// В группе перехват работает так же и так же молча, а второе сообщение
// того же человека дописывается в уже заведённую заявку.
func TestCatchLeadInGroupAndRepeat(t *testing.T) {
	openTestDB(t)
	resetSiteCache()
	setCatchPhones(true)
	t.Cleanup(func() { setCatchPhones(false) })
	f := newFakeMax(t)
	api := newMaxAPI("test-token")
	ctx := context.Background()

	handleBotUpdate(ctx, api, textUpdate(12, 99, "Нужна колбаса, 8 900 111-22-33"))
	handleBotUpdate(ctx, api, textUpdate(12, 99, "И ещё сало, 89001112233"))

	orders, err := listOrders(-1, "")
	if err != nil || len(orders) != 1 {
		t.Fatalf("заказов %d (err %v), ожидался один", len(orders), err)
	}
	o := orders[0]
	if !strings.Contains(o.Comment, "Нужна колбаса") || !strings.Contains(o.Comment, "И ещё сало") {
		t.Errorf("второе сообщение не дописано: %q", o.Comment)
	}
	if len(f.messages) != 0 {
		t.Fatalf("бот заговорил в группе: %#v", f.messages)
	}
}

// Знакомого покупателя вторым клиентом не заводим, а его имя из CRM
// остаётся как есть — продавец мог поправить его руками.
func TestCatchLeadKnownClient(t *testing.T) {
	openTestDB(t)
	resetSiteCache()
	setCatchPhones(true)
	t.Cleanup(func() { setCatchPhones(false) })
	newFakeMax(t)
	api := newMaxAPI("test-token")
	ctx := context.Background()

	c := Client{Phone: "+79002223344", LastName: "Петров", FirstName: "Иван"}
	if err := insertClient(&c); err != nil {
		t.Fatalf("вставка клиента: %v", err)
	}

	handleBotUpdate(ctx, api, textUpdate(13, 0, "Повторите как в прошлый раз, 9002223344"))

	clients, err := listClients("")
	if err != nil || len(clients) != 1 {
		t.Fatalf("клиентов %d (err %v), ожидался один", len(clients), err)
	}
	if clients[0].FirstName != "Иван" || clients[0].LastName != "Петров" {
		t.Errorf("имя клиента затёрто: %#v", clients[0])
	}
	orders, _ := listOrders(-1, "")
	if len(orders) != 1 || orders[0].ClientName != "Петров Иван" {
		t.Fatalf("заявка заведена неверно: %#v", orders)
	}
}

// Заблокированному клиенту заявки не заводим, свой же номер из контактов
// магазина в переписке заявкой тоже не считаем.
func TestCatchLeadSkips(t *testing.T) {
	openTestDB(t)
	resetSiteCache()
	setCatchPhones(true)
	t.Cleanup(func() { setCatchPhones(false) })
	newFakeMax(t)
	api := newMaxAPI("test-token")
	ctx := context.Background()

	blocked := Client{Phone: "+79003334455", FirstName: "Сергей", Blocked: true}
	if err := insertClient(&blocked); err != nil {
		t.Fatalf("вставка клиента: %v", err)
	}

	handleBotUpdate(ctx, api, textUpdate(14, 0, "Привезите мяса, 89003334455"))
	handleBotUpdate(ctx, api, textUpdate(15, 0,
		"Звоните им сами: "+siteText().Phone))

	orders, err := listOrders(-1, "")
	if err != nil || len(orders) != 0 {
		t.Fatalf("заявок %d (err %v), ожидалось ноль", len(orders), err)
	}
}

// Посреди оформления перехват не вмешивается: телефон там спрашивает сам
// бот, и заказ должен собраться обычным путём — один, с товаром внутри.
func TestCatchLeadSkipsCheckout(t *testing.T) {
	openTestDB(t)
	resetSiteCache()
	setCatchPhones(true)
	t.Cleanup(func() { setCatchPhones(false) })
	newFakeMax(t)
	api := newMaxAPI("test-token")
	ctx := context.Background()

	p := testProduct(t, "Колбаса", "кг", 800, 5)
	const user = int64(21)

	handleBotUpdate(ctx, api, callbackUpdate(user, 0, "add:"+i64(p.ID)+":1"))
	handleBotUpdate(ctx, api, callbackUpdate(user, 0, "checkout"))
	handleBotUpdate(ctx, api, textUpdate(user, 0, "Иван"))
	handleBotUpdate(ctx, api, textUpdate(user, 0, "8 900 444-55-66"))
	handleBotUpdate(ctx, api, textUpdate(user, 0, "Ленина 5"))

	orders, err := listOrders(-1, "")
	if err != nil || len(orders) != 1 {
		t.Fatalf("заказов %d (err %v), ожидался один", len(orders), err)
	}
	if orders[0].Total != 800 {
		t.Errorf("сумма заказа %v, ожидалось 800", orders[0].Total)
	}
}

// Выключенный перехват не трогает ни клиентов, ни заказы.
func TestCatchLeadOff(t *testing.T) {
	openTestDB(t)
	resetSiteCache()
	setCatchPhones(false)
	newFakeMax(t)
	api := newMaxAPI("test-token")

	handleBotUpdate(context.Background(), api,
		textUpdate(31, 0, "Привезите мяса, 89005556677"))

	orders, _ := listOrders(-1, "")
	clients, _ := listClients("")
	if len(orders) != 0 || len(clients) != 0 {
		t.Fatalf("выключенный перехват что-то завёл: заказов %d, клиентов %d",
			len(orders), len(clients))
	}
}

// Человек сначала написал заказ словами, а потом собрал его же кнопками:
// второй заказ не появляется — товар дописывается в перехваченную заявку,
// и продавец звонит один раз.
func TestCatchLeadThenCheckout(t *testing.T) {
	openTestDB(t)
	resetSiteCache()
	setCatchPhones(true)
	t.Cleanup(func() { setCatchPhones(false) })
	newFakeMax(t)
	api := newMaxAPI("test-token")
	ctx := context.Background()

	p := testProduct(t, "Пельмени", "кг", 450, 10)
	const user = int64(41)

	handleBotUpdate(ctx, api, textUpdate(user, 0, "Нужны пельмени, 8 900 777-88-99"))
	handleBotUpdate(ctx, api, callbackUpdate(user, 0, "add:"+i64(p.ID)+":2"))
	handleBotUpdate(ctx, api, callbackUpdate(user, 0, "checkout"))
	handleBotUpdate(ctx, api, textUpdate(user, 0, "Иван"))
	handleBotUpdate(ctx, api, textUpdate(user, 0, "8 900 777-88-99"))
	handleBotUpdate(ctx, api, textUpdate(user, 0, "Ленина 5"))

	orders, err := listOrders(-1, "")
	if err != nil || len(orders) != 1 {
		t.Fatalf("заказов %d (err %v), ожидался один", len(orders), err)
	}
	o, err := getOrder(orders[0].ID)
	if err != nil {
		t.Fatalf("заказ не читается: %v", err)
	}
	if o.Total != 900 || len(o.Items) != 1 {
		t.Errorf("товар не дописан в заявку: сумма %v, позиций %d", o.Total, len(o.Items))
	}
	if !strings.Contains(o.Comment, "Нужны пельмени") {
		t.Errorf("перехваченное сообщение потерялось: %q", o.Comment)
	}
	// Склад списан ровно один раз.
	if got, _ := getProduct(p.ID); got.Stock != 8 {
		t.Errorf("остаток %v, ожидалось 8", got.Stock)
	}
}

// Номера из списка исключений перехват пропускает: продавцы и админы пишут
// в группе свои телефоны, и заявка на самих себя никому не нужна.
func TestCatchLeadIgnoresPhones(t *testing.T) {
	openTestDB(t)
	resetSiteCache()
	setCatchPhones(true)
	setIgnorePhones("8 900 111-22-33\n+7 (900) 444-55-66, не телефон, 8 900 111 22 33")
	t.Cleanup(func() {
		setCatchPhones(false)
		setIgnorePhones("")
	})
	newFakeMax(t)
	api := newMaxAPI("test-token")
	ctx := context.Background()

	// Список чистится и от мусора, и от повторов, а в приложение уходит
	// по номеру в строке.
	want := "+7 (900) 111-22-33\n+7 (900) 444-55-66"
	if got := prettyPhoneList(currentIgnorePhones()); got != want {
		t.Errorf("список исключений %q, ожидался %q", got, want)
	}

	handleBotUpdate(ctx, api, textUpdate(51, 77, "Звоните мне: 8 900 111-22-33"))
	handleBotUpdate(ctx, api, textUpdate(52, 77, "Или мне, +7 900 444 55 66"))

	orders, err := listOrders(-1, "")
	clients, _ := listClients("")
	if err != nil || len(orders) != 0 || len(clients) != 0 {
		t.Fatalf("свои номера попали в CRM: заказов %d, клиентов %d", len(orders), len(clients))
	}

	// Чужой номер по-прежнему ловится.
	handleBotUpdate(ctx, api, textUpdate(53, 77, "Привезите мяса, 8 900 999-88-77"))
	if orders, _ = listOrders(-1, ""); len(orders) != 1 {
		t.Fatalf("заявок %d, ожидалась одна", len(orders))
	}
}

// Продавец пересылает в группу сообщение покупателя. Текст такого сообщения
// лежит не в нём самом, а во вложенном — именно на этом заявки и терялись.
// Заявка должна получиться на покупателя, а не на продавца.
func TestCatchLeadFromForward(t *testing.T) {
	openTestDB(t)
	resetSiteCache()
	setCatchPhones(true)
	t.Cleanup(func() { setCatchPhones(false) })
	f := newFakeMax(t)
	api := newMaxAPI("test-token")

	u := textUpdate(11, fakeChat, "")
	u.Message.Body.MID = "mid.1"
	u.Message.Sender = botUser{UserID: 11, Name: "Алена"}
	u.Message.Link = &msgLink{Type: "forward",
		Sender: botUser{UserID: 22, Name: "Наташа", Username: "natasha"}}
	u.Message.Link.Message.MID = "mid.orig"
	u.Message.Link.Message.Text = "с. Натальино Строительная 28. 89378118853"
	handleBotUpdate(context.Background(), api, u)

	orders, err := listOrders(-1, "")
	if err != nil || len(orders) != 1 {
		t.Fatalf("заказов %d (err %v), ожидалась одна заявка", len(orders), err)
	}
	o := orders[0]
	if o.Phone != "+79378118853" {
		t.Errorf("телефон из пересланного не взят: %q", o.Phone)
	}
	if o.ClientName != "Наташа" {
		t.Errorf("заявка не на покупателя, а на %q", o.ClientName)
	}
	if !strings.Contains(o.Comment, "Строительная 28") ||
		!strings.Contains(o.Comment, "переслал(а) Алена") {
		t.Errorf("комментарий заявки: %q", o.Comment)
	}
	if len(f.messages) != 0 {
		t.Fatalf("бот заговорил в группе: %#v", f.messages)
	}
}

// Заказ без телефона: узнаём по словам и по тому, кто написал. Второе
// сообщение того же человека дописывается в ту же заявку.
func TestCatchLeadWithoutPhone(t *testing.T) {
	openTestDB(t)
	resetSiteCache()
	setCatchPhones(true)
	setOrderWords("")
	setChatStaff([]int64{77})
	t.Cleanup(func() {
		setCatchPhones(false)
		setChatStaff(nil)
	})
	newFakeMax(t)
	api := newMaxAPI("test-token")
	ctx := context.Background()

	// Болтовня без слов заказа заявкой не становится.
	handleBotUpdate(ctx, api, textUpdate(31, fakeChat, "спасибо, всё было вкусно"))
	// Объявление продавца — тоже: он из списка администраторов группы.
	handleBotUpdate(ctx, api, textUpdate(77, fakeChat, "девочки, привезли свежую партию"))
	if orders, _ := listOrders(-1, ""); len(orders) != 0 {
		t.Fatalf("заявки на пустом месте: %#v", orders)
	}

	handleBotUpdate(ctx, api, textUpdate(31, fakeChat,
		"Можно пожалуйста на пробу тушёнку куриную 1 и кашу гречневую 1"))
	handleBotUpdate(ctx, api, textUpdate(31, fakeChat, "И ещё возьму скумбрию"))

	orders, err := listOrders(-1, "")
	if err != nil || len(orders) != 1 {
		t.Fatalf("заказов %d (err %v), ожидалась одна заявка", len(orders), err)
	}
	o := orders[0]
	if o.Phone != "" || o.ClientID != nil {
		t.Errorf("заявка без телефона не должна быть привязана к клиенту: %#v", o)
	}
	if o.ClientName != "Покупатель" {
		t.Errorf("в заявке нет имени из MAX: %q", o.ClientName)
	}
	if !strings.Contains(o.Comment, "тушёнку куриную") ||
		!strings.Contains(o.Comment, "скумбрию") {
		t.Errorf("второе сообщение не дописано: %q", o.Comment)
	}
	if !strings.Contains(o.Comment, "Телефона в сообщении нет") {
		t.Errorf("продавцу не сказано, что телефона нет: %q", o.Comment)
	}
	if clients, _ := listClients(""); len(clients) != 0 {
		t.Errorf("клиент без телефона заводиться не должен: %#v", clients)
	}
}

// Человек сначала написал без телефона, потом прислал номер — заявка одна,
// и телефон с карточкой клиента дописываются в неё.
func TestCatchLeadPhoneComesLater(t *testing.T) {
	openTestDB(t)
	resetSiteCache()
	setCatchPhones(true)
	setOrderWords("")
	t.Cleanup(func() { setCatchPhones(false) })
	newFakeMax(t)
	api := newMaxAPI("test-token")
	ctx := context.Background()

	handleBotUpdate(ctx, api, textUpdate(41, fakeChat, "хочу заказать пельмени"))
	handleBotUpdate(ctx, api, textUpdate(41, fakeChat, "мой номер 8 900 555-66-77"))

	orders, err := listOrders(-1, "")
	if err != nil || len(orders) != 1 {
		t.Fatalf("заказов %d (err %v), ожидалась одна заявка", len(orders), err)
	}
	o := orders[0]
	if o.Phone != "+79005556677" {
		t.Errorf("телефон не дописан в заявку: %q", o.Phone)
	}
	if o.ClientID == nil {
		t.Errorf("клиент не привязан к заявке")
	}
	if c, err := getClientByPhone("+79005556677"); err != nil || c.FirstName != "Покупатель" {
		t.Errorf("клиент по телефону не заведён: %v %#v", err, c)
	}
}

// Слова заказа берутся из настроек: оставили только «заказ» — «хочу» больше
// заявкой не считается.
func TestOrderWordsFromSettings(t *testing.T) {
	setOrderWords("заказ, привезите")
	t.Cleanup(func() { setOrderWords("") })
	if !hasOrderWord("оформите заказ на завтра") {
		t.Errorf("слово из списка не сработало")
	}
	if hasOrderWord("хочу спросить про доставку") {
		t.Errorf("слово не из списка сработало")
	}
	setOrderWords("")
	if !hasOrderWord("хочу спросить про доставку") {
		t.Errorf("пустой список должен вернуть слова по умолчанию")
	}
}

// Не было связи — сообщение прошло мимо опроса. Догон истории заводит по нему
// заявку, а то, что опрос уже разобрал, второй раз не берёт.
func TestCatchUpHistory(t *testing.T) {
	openTestDB(t)
	resetSiteCache()
	setCatchPhones(true)
	setOrderWords("")
	t.Cleanup(func() {
		setCatchPhones(false)
		setChatStaff(nil)
	})
	f := newFakeMax(t)
	api := newMaxAPI("test-token")
	ctx := context.Background()

	// Продавцов бот узнаёт из списка администраторов группы.
	f.mu.Lock()
	f.admins = []int64{77}
	f.mu.Unlock()
	refreshStaff(ctx, api, fakeChat)
	if !isChatStaff(77) || isChatStaff(51) {
		t.Fatalf("список продавцов не прочитан")
	}

	// Это сообщение бот разобрал живьём.
	live := textUpdate(51, fakeChat, "заказ на завтра, 8 900 111-22-33")
	live.Message.Body.MID = "mid.live"
	live.Message.Timestamp = nowMs()
	handleBotUpdate(ctx, api, live)

	// А это прошло мимо. В историю MAX отдаёт оба.
	missed := newMessage(52, fakeChat, "привезите 2 кг колбасы, 89004445566")
	missed.Body.MID = "mid.missed"
	missed.Timestamp = nowMs()
	f.mu.Lock()
	f.history = []botMessage{*live.Message, *missed}
	f.mu.Unlock()

	catchUpHistory(ctx, api)
	orders, err := listOrders(-1, "")
	if err != nil || len(orders) != 2 {
		t.Fatalf("заявок %d (err %v), ожидалось две: живая и догнанная", len(orders), err)
	}

	// Повторный догон ничего не задваивает.
	catchUpHistory(ctx, api)
	if orders, _ = listOrders(-1, ""); len(orders) != 2 {
		t.Fatalf("догон задвоил заявки: %d", len(orders))
	}
	if _, err := getClientByPhone("+79004445566"); err != nil {
		t.Errorf("клиент по догнанному сообщению не заведён: %v", err)
	}
}
