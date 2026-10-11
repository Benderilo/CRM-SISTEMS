package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync/atomic"
	"testing"
)

// fakeAI поднимает Ollama-заглушку и подсовывает её разбору сообщений.
// Возвращает счётчик запросов: по нему видно, ходили мы к модели или нет.
func fakeAI(t *testing.T, answer string, status int) *int32 {
	t.Helper()
	var calls int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&calls, 1)
		if status != http.StatusOK {
			w.WriteHeader(status)
			fmt.Fprint(w, "модель занята")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"message": map[string]string{"content": answer},
		})
	}))
	t.Cleanup(srv.Close)

	old := aiCfg
	aiCfg = aiConfig{URL: srv.URL, Model: "тест", Key: "ключ"}
	setAIEnabled(true)
	aiReset()
	// Прайс запоминается на десять минут, а у каждого теста своя база:
	// без сброса в промпт попал бы каталог из предыдущего теста.
	resetCatalog()
	t.Cleanup(func() { aiCfg = old; setAIEnabled(false); aiReset(); resetCatalog() })
	return &calls
}

// Ответ модели разбирается и доходит до вызывающего целиком.
func TestAskAIVerdict(t *testing.T) {
	fakeAI(t, `{"order":true,"confidence":0.98,"name":"Ирина","address":"Ленина 5",
      "items":[{"name":"фарш","qty":2,"unit":"кг"}],"summary":"фарш на Ленина 5"}`,
		http.StatusOK)

	v, ok := askAI("это Ирина, мне бы пару килограмм фарша на Ленина 5")
	if !ok {
		t.Fatal("модель ответила, а разбор не получен")
	}
	if !v.Order || v.Confidence != 0.98 {
		t.Errorf("вердикт разобран неверно: %#v", v)
	}
	if v.Name != "Ирина" || v.Address != "Ленина 5" {
		t.Errorf("имя и адрес не перенесены: %q / %q", v.Name, v.Address)
	}
	if len(v.Items) != 1 || v.Items[0].Qty != 2 {
		t.Errorf("позиции разобраны неверно: %#v", v.Items)
	}
}

// Модель молчит — второе значение false. Это «решай по-старому», а не
// «не заказ»: путать эти смыслы нельзя.
func TestAskAIOfflineSaysNothing(t *testing.T) {
	calls := fakeAI(t, "", http.StatusInternalServerError)

	if _, ok := askAI("привезите 3 кг пельменей"); ok {
		t.Fatal("сервис лежит, а разбор считается полученным")
	}
	if atomic.LoadInt32(calls) != 1 {
		t.Errorf("запросов к модели %d, ожидался один", *calls)
	}
}

// После трёх неудач подряд к модели перестаём ходить: иначе каждое
// сообщение ждало бы таймаута.
func TestAskAIPausesAfterFailures(t *testing.T) {
	calls := fakeAI(t, "", http.StatusInternalServerError)

	for i := 0; i < 6; i++ {
		if _, ok := askAI("привезите 3 кг пельменей"); ok {
			t.Fatal("сервис лежит, а разбор считается полученным")
		}
	}
	if got := atomic.LoadInt32(calls); got != aiFailsToPause {
		t.Errorf("запросов к модели %d, ожидалось %d — дальше должен работать перерыв",
			got, aiFailsToPause)
	}
}

// Без ключа и без своей Ollama разбор выключен и в сеть не ходит.
func TestAskAIOffWithoutKey(t *testing.T) {
	old := aiCfg
	aiCfg = aiConfig{URL: aiDefaultURL, Model: aiDefaultModel}
	setAIEnabled(true)
	aiReset()
	t.Cleanup(func() { aiCfg = old; setAIEnabled(false); aiReset() })

	if _, ok := askAI("привезите 3 кг пельменей"); ok {
		t.Fatal("ключа нет, а разбор состоялся")
	}
}

// Модель иногда обрамляет ответ пояснением — объект всё равно достаём.
func TestJSONObject(t *testing.T) {
	cases := []struct{ in, want string }{
		{"```json\n{\"order\":true}\n```", `{"order":true}`},
		{"Вот ответ: {\"order\":false} — готово", `{"order":false}`},
		{`{"order":true}`, `{"order":true}`},
		{"без объекта", "без объекта"},
	}
	for _, c := range cases {
		if got := jsonObject(c.in); got != c.want {
			t.Errorf("jsonObject(%q) = %q, ожидалось %q", c.in, got, c.want)
		}
	}
}

// Имя и адрес, которых в сообщении не было, в карточку не попадают:
// продавец не должен везти мясо по придуманному моделью адресу.
func TestAICleanDropsInvented(t *testing.T) {
	const text = "это Ирины заказ, привезите фарш на Ленина 5"
	v := aiClean(aiVerdict{
		Confidence: 1.7,
		Name:       "Ирина",      // в тексте «Ирины» — падеж, оставляем
		Address:    "Гагарина 7", // такого в сообщении нет — убираем
		Items:      []aiItem{{Name: "фарш", Qty: 2, Unit: "кг"}, {Name: "  "}},
		Summary:    "фарш\nна Ленина 5",
	}, text)

	if v.Name != "Ирина" {
		t.Errorf("имя в падеже не узнано: %q", v.Name)
	}
	if v.Address != "" {
		t.Errorf("выдуманный адрес попал в заявку: %q", v.Address)
	}
	if v.Confidence != 1 {
		t.Errorf("уверенность не обрезана до единицы: %v", v.Confidence)
	}
	if len(v.Items) != 1 {
		t.Errorf("пустая позиция не выброшена: %#v", v.Items)
	}
	if strings.Contains(v.Summary, "\n") {
		t.Errorf("перенос строки остался в сводке: %q", v.Summary)
	}
}

// Настоящий адрес из сообщения сохраняется.
func TestAICleanKeepsSaid(t *testing.T) {
	v := aiClean(aiVerdict{Address: "Ленина 5"}, "привезите на Ленина 5")
	if v.Address != "Ленина 5" {
		t.Errorf("адрес из сообщения потерян: %q", v.Address)
	}
}

// Разбор в комментарии заявки: внесённое в заказ и оставшееся продавцу
// должны стоять раздельно — по первому товар со склада уже списан.
func TestAINote(t *testing.T) {
	id := int64(1)
	note := aiNote(
		aiVerdict{Summary: "фарш и тушёнка на Ленина 5", Address: "Ленина 5"},
		[]OrderItem{{ProductID: &id, Name: "Фарш домашний", Unit: "кг", Qty: 2}},
		[]aiItem{{Name: "тушёнка"}},
	)
	for _, want := range []string{
		"Разбор ИИ: фарш и тушёнка на Ленина 5",
		"Бот внёс в заказ (проверьте): Фарш домашний — 2 кг",
		"Просили ещё, внесите сами: тушёнка",
		"Адрес из сообщения: Ленина 5",
	} {
		if !strings.Contains(note, want) {
			t.Errorf("в разборе нет строки %q:\n%s", want, note)
		}
	}
	if aiNote(aiVerdict{}, nil, nil) != "" {
		t.Error("пустой разбор не должен ничего дописывать в комментарий")
	}
}

// ------------------------------------------------------- товары из каталога

// В заказ попадает только то, что однозначно опознано в прайсе и названо
// количеством. Всё остальное остаётся продавцу словами покупателя.
func TestAISplitItems(t *testing.T) {
	openTestDB(t)
	pelmeni := testProduct(t, "Пельмени свинина и говядина", "уп", 450, 10)
	testProduct(t, "Котлеты по Киевски", "шт", 120, 10)
	testProduct(t, "Котлеты по Киевски", "шт", 130, 10) // тёзка в прайсе

	ready, rest := aiSplitItems(aiVerdict{Items: []aiItem{
		{Name: "пельмешки", Product: "пельмени свинина и говядина", Qty: 2, Unit: "уп"},
		{Name: "котлеты", Product: "Котлеты по Киевски", Qty: 3},
		{Name: "тушёнка", Product: "Тушёнка домашняя", Qty: 1},
		{Name: "блинчики", Product: "Пельмени свинина и говядина"},
	}})

	if len(ready) != 1 {
		t.Fatalf("в заказ готовы позиции %#v, ожидалась одна", ready)
	}
	if ready[0].ProductID == nil || *ready[0].ProductID != pelmeni.ID {
		t.Errorf("опознан не тот товар: %#v", ready[0])
	}
	if ready[0].UnitPrice != 450 || ready[0].Qty != 2 {
		t.Errorf("цена и количество взяты неверно: %#v", ready[0])
	}
	if len(rest) != 3 {
		t.Errorf("продавцу осталось %d позиций, ожидалось три: %#v", len(rest), rest)
	}
}

// Заказ из сообщения сразу попадает в заявку: позиции, сумма и склад.
func TestCatchLeadFillsOrder(t *testing.T) {
	openTestDB(t)
	resetSiteCache()
	setCatchPhones(true)
	t.Cleanup(func() { setCatchPhones(false) })
	p := testProduct(t, "Блины с мясом", "уп", 300, 10)
	fakeAI(t, `{"order":true,"confidence":0.98,
      "items":[{"name":"блинчики с мясом","product":"Блины с мясом","qty":2,"unit":"уп"},
               {"name":"тушёнка","product":"","qty":0}],
      "summary":"2 упаковки блинов с мясом"}`, http.StatusOK)
	newFakeMax(t)
	api := newMaxAPI("test-token")

	handleBotUpdate(context.Background(), api,
		textUpdate(801, 0, "отложите 2 упаковки блинчиков с мясом, 8 900 123-45-67"))

	orders, err := listOrders(-1, "")
	if err != nil || len(orders) != 1 {
		t.Fatalf("заявок %d (err %v), ожидалась одна", len(orders), err)
	}
	if orders[0].Total != 600 {
		t.Errorf("сумма заявки %v, ожидалось 600", orders[0].Total)
	}
	if !strings.Contains(orders[0].Comment, "Бот внёс в заказ (проверьте): Блины с мясом — 2 уп") {
		t.Errorf("в комментарии нет внесённого состава: %q", orders[0].Comment)
	}
	if !strings.Contains(orders[0].Comment, "Просили ещё, внесите сами: тушёнка") {
		t.Errorf("неопознанное не отдано продавцу: %q", orders[0].Comment)
	}
	got, err := getProduct(p.ID)
	if err != nil {
		t.Fatalf("товар: %v", err)
	}
	if got.Stock != 8 {
		t.Errorf("остаток %v, ожидалось 8 — товар должен списаться со склада", got.Stock)
	}
}

// ------------------------------------------------- разбор решает за перехват

// Благодарность с телефоном заявкой больше не становится: по словам
// «ещё закажем» старая логика завела бы пустой заказ.
func TestCatchLeadAISkipsThanks(t *testing.T) {
	openTestDB(t)
	resetSiteCache()
	setCatchPhones(true)
	t.Cleanup(func() { setCatchPhones(false) })
	fakeAI(t, `{"order":false,"confidence":0.96,"summary":"благодарность"}`,
		http.StatusOK)
	newFakeMax(t)
	api := newMaxAPI("test-token")

	const text = "Спасибо за блинчики, очень вкусные! Обязательно ещё закажем. " +
		"Мой номер 8 900 123-45-67"
	if !hasOrderWord(text) {
		t.Fatal("проверка бессмысленна: старая логика это сообщение и так пропускает")
	}
	handleBotUpdate(context.Background(), api, textUpdate(777, 0, text))

	orders, err := listOrders(-1, "")
	if err != nil {
		t.Fatalf("список заказов: %v", err)
	}
	if len(orders) != 0 {
		t.Fatalf("на благодарность заведено заявок: %d", len(orders))
	}
}

// Заказ без единого слова из списка модель всё равно ловит — раньше такое
// сообщение до CRM не доходило.
func TestCatchLeadAICatchesWithoutOrderWords(t *testing.T) {
	openTestDB(t)
	resetSiteCache()
	setCatchPhones(true)
	t.Cleanup(func() { setCatchPhones(false) })
	fakeAI(t, `{"order":true,"confidence":0.95,"address":"Ленина 5",
      "items":[{"name":"фарш","qty":2,"unit":"кг"}],
      "summary":"2 кг фарша на Ленина 5"}`, http.StatusOK)
	newFakeMax(t)
	api := newMaxAPI("test-token")

	const text = "на неделе бы пару килограмм фарша, я на Ленина 5"
	if hasOrderWord(text) {
		t.Fatal("проверка бессмысленна: старая логика это сообщение и так ловит")
	}
	handleBotUpdate(context.Background(), api, textUpdate(778, 0, text))

	orders, err := listOrders(-1, "")
	if err != nil || len(orders) != 1 {
		t.Fatalf("заявок %d (err %v), ожидалась одна", len(orders), err)
	}
	o := orders[0]
	if o.Address != "Ленина 5" {
		t.Errorf("адрес из сообщения не попал в заявку: %q", o.Address)
	}
	// Товара с таким названием в прайсе нет — состав уходит продавцу словами.
	if !strings.Contains(o.Comment, "Просили ещё, внесите сами: фарш — 2 кг") {
		t.Errorf("состав из разбора не попал в комментарий: %q", o.Comment)
	}
	if o.Total != 0 {
		t.Errorf("позиции из разбора не должны попадать в сумму заказа: %v", o.Total)
	}
}

// Модель недоступна — перехват работает ровно как до неё: по словам заказа.
func TestCatchLeadFallsBackToWords(t *testing.T) {
	openTestDB(t)
	resetSiteCache()
	setCatchPhones(true)
	t.Cleanup(func() { setCatchPhones(false) })
	fakeAI(t, "", http.StatusInternalServerError)
	newFakeMax(t)
	api := newMaxAPI("test-token")

	// Слово заказа есть — заявка появляется, хотя модель не ответила.
	handleBotUpdate(context.Background(), api,
		textUpdate(779, 0, "Привезите 3 кг пельменей, 8 900 123-45-67"))
	orders, err := listOrders(-1, "")
	if err != nil || len(orders) != 1 {
		t.Fatalf("заявок %d (err %v), ожидалась одна", len(orders), err)
	}
	if strings.Contains(orders[0].Comment, "Разбор ИИ") {
		t.Errorf("разбора не было, а в комментарии он есть: %q", orders[0].Comment)
	}

	// Слова заказа нет и модель молчит — как и раньше, заявки нет.
	handleBotUpdate(context.Background(), api,
		textUpdate(780, 0, "на неделе бы пару килограмм фарша"))
	orders, err = listOrders(-1, "")
	if err != nil {
		t.Fatalf("список заказов: %v", err)
	}
	if len(orders) != 1 {
		t.Errorf("заявок стало %d — без модели поведение должно остаться прежним",
			len(orders))
	}
}

// Живая проверка настоящей модели — руками, когда меняли промпт:
//
//	SHOP_AI_KEY=… go test -run TestAskAILive -v
//
// Без ключа пропускается, поэтому обычный прогон тестов в сеть не ходит.
func TestAskAILive(t *testing.T) {
	if os.Getenv("SHOP_AI_KEY") == "" {
		t.Skip("нет SHOP_AI_KEY — живая модель не проверяется")
	}
	// Каталог настоящий, названия — как на витрине: по ним и проверяем,
	// узнаёт ли модель товар в словах покупателя.
	openTestDB(t)
	resetCatalog()
	testProduct(t, "Блины с мясом", "уп", 300, 10)
	testProduct(t, "Пельмени свинина и говядина", "кг", 500, 10)
	testProduct(t, "Котлеты свинина+говядина", "шт", 90, 10)
	testProduct(t, "Копчёный окунь", "шт", 250, 10)

	old := aiCfg
	setupAI()
	setAIEnabled(true)
	aiReset()
	t.Cleanup(func() { aiCfg = old; setAIEnabled(false); aiReset() })

	cases := []struct {
		text  string
		order bool
	}{
		{"Спасибо за блинчики, очень вкусные! Обязательно ещё закажем", false},
		{"А почем у вас сейчас свинина?", false},
		{"Здравствуйте", false},
		{"Девочки, привезли свежую партию тушёнки, налетайте", false},
		{"на неделе бы пару килограмм фарша и десяток котлет, я на Ленина 5, Ирина", true},
		{"привезите 3 кг пельменей на Ленина 5, 8 900 123-45-67", true},
		{"можно на пробу тушёнку и кашу", true},
		{"Добрый день! Блинчики с мясом есть? Если да, отложите 2 упаковки пожалуйста", true},
	}
	for _, c := range cases {
		v, ok := askAI(c.text)
		if !ok {
			t.Fatalf("модель не ответила на %q", c.text)
		}
		if v.Order != c.order {
			t.Errorf("%q: заказ=%v (уверенность %.2f), ожидалось %v — %s",
				c.text, v.Order, v.Confidence, c.order, v.Summary)
			continue
		}
		ready, rest := aiSplitItems(v)
		t.Logf("%q → заказ=%v (%.2f), имя %q, адрес %q\n%s",
			c.text, v.Order, v.Confidence, v.Name, v.Address, aiNote(v, ready, rest))
	}
}

// ------------------------------------------------- опознание по базе клиентов

// testNamedClient — клиент в базе с телефоном и именем.
func testNamedClient(t *testing.T, phone, last, first, middle string) Client {
	t.Helper()
	c := Client{Phone: phone, LastName: last, FirstName: first, MiddleName: middle,
		Segment: "Постоянный", Source: "Приложение"}
	if err := insertClient(&c); err != nil {
		t.Fatalf("вставка клиента: %v", err)
	}
	return c
}

// namedUpdate — сообщение от человека с заданным именем в профиле MAX.
func namedUpdate(userID int64, name, text string) botUpdate {
	u := textUpdate(userID, 0, text)
	u.Message.Sender.Name = name
	return u
}

// По одному совпавшему слову карточку не привязываем: тёзок много.
// Два слова и единственный такой человек в базе — можно.
func TestMatchClientsByName(t *testing.T) {
	openTestDB(t)
	testNamedClient(t, "+79271635214", "", "Александра", "")
	testNamedClient(t, "+79271635215", "Иванова", "Ирина", "")
	testNamedClient(t, "+79271635216", "Петрова", "Ирина", "")

	found, sure := matchClientsByName("Александра Олеговна")
	if sure {
		t.Error("по одному совпавшему слову карточка привязалась автоматически")
	}
	if len(found) != 1 || found[0].Phone != "+79271635214" {
		t.Errorf("Александра не предложена подсказкой: %#v", found)
	}

	if found, sure = matchClientsByName("Иванова Ирина"); !sure {
		t.Errorf("полное совпадение по двум словам не привязалось: %#v", found)
	} else if found[0].Phone != "+79271635215" {
		t.Errorf("привязан не тот клиент: %#v", found[0])
	}

	// Одно слово «Ирина» подходит обеим — только подсказка.
	if found, sure = matchClientsByName("Ирина"); sure || len(found) != 2 {
		t.Errorf("тёзки: привязка=%v, кандидатов %d — ожидалось две подсказки",
			sure, len(found))
	}

	if found, sure = matchClientsByName("Пётр"); sure || len(found) != 0 {
		t.Errorf("незнакомое имя дало совпадения: %#v", found)
	}
}

// Знакомого покупателя без телефона в сообщении находим по имени, и заявка
// сразу ложится в его карточку — ровно то, чего не хватило заявке №0143.
func TestCatchLeadFindsClientByName(t *testing.T) {
	openTestDB(t)
	resetSiteCache()
	setCatchPhones(true)
	t.Cleanup(func() { setCatchPhones(false) })
	c := testNamedClient(t, "+79271635214", "", "Александра", "Олеговна")
	fakeAI(t, `{"order":true,"confidence":0.97,"summary":"просит привезти"}`,
		http.StatusOK)
	newFakeMax(t)
	api := newMaxAPI("test-token")

	handleBotUpdate(context.Background(), api,
		namedUpdate(802, "Александра Олеговна", "привезите пожалуйста как обычно"))

	orders, err := listOrders(-1, "")
	if err != nil || len(orders) != 1 {
		t.Fatalf("заявок %d (err %v), ожидалась одна", len(orders), err)
	}
	if orders[0].Phone != c.Phone {
		t.Errorf("телефон из карточки не подставлен: %q", orders[0].Phone)
	}
	if orders[0].ClientID == nil || *orders[0].ClientID != c.ID {
		t.Errorf("заявка не привязана к карточке: %#v", orders[0].ClientID)
	}

	// И запомнили, чей это MAX: в следующий раз ищем по ID, а не по имени.
	got, ok := knownLeadClient(802)
	if !ok || got.ID != c.ID {
		t.Errorf("покупатель не связан со своим MAX: %#v (найден=%v)", got, ok)
	}
	// А в «постоянные покупатели» перехват не лезет: там данные, которые
	// человек подтвердил сам при оформлении.
	if loadCustomer(802).known() {
		t.Error("перехват записал покупателя как постоянного — бот перестанет " +
			"спрашивать телефон при оформлении")
	}
}

// Совпало одно слово — карточку не трогаем, но подсказываем продавцу.
func TestCatchLeadHintsClient(t *testing.T) {
	openTestDB(t)
	resetSiteCache()
	setCatchPhones(true)
	t.Cleanup(func() { setCatchPhones(false) })
	testNamedClient(t, "+79271635214", "", "Александра", "")
	fakeAI(t, `{"order":true,"confidence":0.97,"summary":"просит привезти"}`,
		http.StatusOK)
	newFakeMax(t)
	api := newMaxAPI("test-token")

	handleBotUpdate(context.Background(), api,
		namedUpdate(803, "Александра Олеговна", "привезите пожалуйста как обычно"))

	orders, err := listOrders(-1, "")
	if err != nil || len(orders) != 1 {
		t.Fatalf("заявок %d (err %v), ожидалась одна", len(orders), err)
	}
	if orders[0].Phone != "" {
		t.Errorf("по одному слову подставлен телефон: %q", orders[0].Phone)
	}
	if !strings.Contains(orders[0].Comment, "Возможно, это клиент из базы") {
		t.Errorf("подсказки о клиенте нет в комментарии: %q", orders[0].Comment)
	}
	if !strings.Contains(orders[0].Comment, "+7 (927) 163-52-14") {
		t.Errorf("в подсказке нет телефона кандидата: %q", orders[0].Comment)
	}
}

// ------------------------------------------------------- правка сообщения

// Покупатель исправил заказ прямо в сообщении — правка дописывается в ту же
// заявку с пометкой, а не теряется и не заводит вторую.
func TestCatchLeadEdited(t *testing.T) {
	openTestDB(t)
	resetSiteCache()
	setCatchPhones(true)
	t.Cleanup(func() { setCatchPhones(false) })
	testProduct(t, "Пельмени свинина и говядина", "кг", 500, 10)
	fakeAI(t, `{"order":true,"confidence":0.98,
      "items":[{"name":"пельмени","product":"Пельмени свинина и говядина","qty":1,"unit":"кг"}],
      "summary":"пельмени"}`, http.StatusOK)
	newFakeMax(t)
	api := newMaxAPI("test-token")
	ctx := context.Background()

	handleBotUpdate(ctx, api,
		textUpdate(804, 0, "привезите 3 кг пельменей, 8 900 123-45-67"))

	edited := textUpdate(804, 0, "привезите 2 кг пельменей, 8 900 123-45-67")
	edited.Type = "message_edited"
	handleBotUpdate(ctx, api, edited)

	orders, err := listOrders(-1, "")
	if err != nil || len(orders) != 1 {
		t.Fatalf("заявок %d (err %v) — правка не должна заводить вторую", len(orders), err)
	}
	o := orders[0]
	if !strings.Contains(o.Comment, "Покупатель исправил сообщение") {
		t.Errorf("правка не отмечена в комментарии: %q", o.Comment)
	}
	if !strings.Contains(o.Comment, "привезите 2 кг пельменей") {
		t.Errorf("исправленного текста нет в комментарии: %q", o.Comment)
	}
	if !strings.Contains(o.Comment, "привезите 3 кг пельменей") {
		t.Errorf("прежний текст пропал — продавцу нечего сравнить: %q", o.Comment)
	}
}

// Выключатель из приложения: ключ на сервере есть, но продавец разбор
// выключил — бот не ходит к модели и работает по словам заказа.
func TestAISwitchedOffInApp(t *testing.T) {
	calls := fakeAI(t, `{"order":true,"confidence":0.99}`, http.StatusOK)
	setAIEnabled(false)

	if _, ok := askAI("привезите 3 кг пельменей"); ok {
		t.Fatal("разбор выключен, а модель всё равно спросили")
	}
	if atomic.LoadInt32(calls) != 0 {
		t.Errorf("запросов к модели %d, ожидалось ноль", *calls)
	}

	// Включили обратно — снова спрашиваем.
	setAIEnabled(true)
	if _, ok := askAI("привезите 3 кг пельменей"); !ok {
		t.Error("разбор включили, а модель не спросили")
	}
}

// Настройка сервера и выключатель приложения — разные вещи: без ключа
// включать нечего, и приложение должно это показать, а не рисовать
// мёртвый переключатель.
func TestAIConfiguredIsNotEnabled(t *testing.T) {
	old := aiCfg
	aiCfg = aiConfig{URL: aiDefaultURL, Model: aiDefaultModel} // ключа нет
	setAIEnabled(true)
	t.Cleanup(func() { aiCfg = old; setAIEnabled(false) })

	if aiConfigured() {
		t.Error("без ключа разбор считается настроенным")
	}
	if aiReady() {
		t.Error("без ключа разбор считается готовым")
	}
}

// ------------------------------------------------- переключатель из приложения

// Настройка живёт в базе рядом с остальными настройками бота и переживает
// перезапуск. По умолчанию — включено: ключ на сервере задают затем, чтобы
// разбор работал.
func TestAISettingSaved(t *testing.T) {
	openTestDB(t)

	if c := loadBotConfig(); !c.AIEnabled {
		t.Error("на чистой базе разбор выключен, ожидалось включён")
	}

	c := loadBotConfig()
	c.AIEnabled = false
	if err := saveBotConfig(c); err != nil {
		t.Fatalf("сохранение настроек: %v", err)
	}
	if loadBotConfig().AIEnabled {
		t.Error("выключенный разбор не сохранился")
	}
}

// Приложение постарее поля aiEnabled не шлёт — разбор от этого выключаться
// не должен. Та же причина, по которой указателями сделаны перехват и
// ночной перезапуск.
func TestAISettingSurvivesOldApp(t *testing.T) {
	openTestDB(t)
	c := loadBotConfig()
	c.Token, c.AIEnabled = "токен", true
	if err := saveBotConfig(c); err != nil {
		t.Fatalf("сохранение настроек: %v", err)
	}

	r := httptest.NewRequest(http.MethodPost, "/api/v1/crm/bot",
		strings.NewReader(`{"enabled":false,"callWords":"бот"}`))
	apiCrmSaveBot(httptest.NewRecorder(), r)

	if !loadBotConfig().AIEnabled {
		t.Error("приложение без поля aiEnabled выключило разбор сообщений")
	}
}

// ------------------------------------------ привязка клиента продавцом

// Продавец связал заявку из MAX с карточкой — бот запомнил, чей это MAX.
// Со следующего сообщения человек опознаётся по своему ID, а не по имени.
func TestOrderClientRemembersMax(t *testing.T) {
	openTestDB(t)
	c := testNamedClient(t, "+79271635214", "", "Александра", "Олеговна")
	o := Order{ClientName: "Александра Олеговна", Status: StatusNew, Source: sourceMax}
	if err := createOrderItems(&o, nil, true); err != nil {
		t.Fatalf("заявка: %v", err)
	}
	rememberLead(555, o.ID)

	body := `{"orderId":` + i64(o.ID) + `,"clientId":` + i64(c.ID) + `}`
	w := httptest.NewRecorder()
	apiCrmOrderClient(w, httptest.NewRequest(http.MethodPost,
		"/api/v1/crm/orders/client", strings.NewReader(body)))
	if w.Code != http.StatusOK {
		t.Fatalf("привязка не прошла: %d %s", w.Code, w.Body.String())
	}

	got, err := getOrder(o.ID)
	if err != nil {
		t.Fatalf("заказ: %v", err)
	}
	if got.ClientID == nil || *got.ClientID != c.ID {
		t.Errorf("клиент не привязан к заявке: %#v", got.ClientID)
	}
	if got.Phone != c.Phone {
		t.Errorf("телефон из карточки не подтянулся: %q", got.Phone)
	}

	// Главное: бот теперь знает этот MAX.
	known, ok := knownLeadClient(555)
	if !ok || known.ID != c.ID {
		t.Errorf("связка MAX с карточкой не запомнена: %#v (найден=%v)", known, ok)
	}
}

// Заявка не из MAX — запоминать нечего, и падать тоже не на чем.
func TestOrderClientIgnoresNonMax(t *testing.T) {
	openTestDB(t)
	c := testNamedClient(t, "+79271635215", "", "Ирина", "")
	o := Order{ClientName: "Ирина", Status: StatusNew, Source: sourceApp}
	if err := createOrderItems(&o, nil, true); err != nil {
		t.Fatalf("продажа: %v", err)
	}

	rememberOrderClient(o, c.ID)

	if _, ok := knownLeadClient(0); ok {
		t.Error("для продажи из приложения запомнилась связка с MAX")
	}
}

// Свой адрес в заявке не затираем адресом из карточки: покупатель мог
// назвать другой именно для этого заказа.
func TestOrderClientKeepsOrderAddress(t *testing.T) {
	openTestDB(t)
	c := testNamedClient(t, "+79271635216", "", "Ольга", "")
	c.Address = "Ленина 5"
	if err := updateClient(&c); err != nil {
		t.Fatalf("клиент: %v", err)
	}
	o := Order{ClientName: "Ольга", Status: StatusNew, Source: sourceMax,
		Address: "Садовая 12, на работу"}
	if err := createOrderItems(&o, nil, true); err != nil {
		t.Fatalf("заявка: %v", err)
	}

	body := `{"orderId":` + i64(o.ID) + `,"clientId":` + i64(c.ID) + `}`
	w := httptest.NewRecorder()
	apiCrmOrderClient(w, httptest.NewRequest(http.MethodPost,
		"/api/v1/crm/orders/client", strings.NewReader(body)))
	if w.Code != http.StatusOK {
		t.Fatalf("привязка не прошла: %d %s", w.Code, w.Body.String())
	}

	got, _ := getOrder(o.ID)
	if got.Address != "Садовая 12, на работу" {
		t.Errorf("адрес заявки затёрт адресом из карточки: %q", got.Address)
	}
}
