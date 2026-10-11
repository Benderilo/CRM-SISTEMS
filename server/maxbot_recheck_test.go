package main

import (
	"context"
	"net/http"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// msAgo — время столько-то назад в миллисекундах.
func msAgo(now time.Time, d time.Duration) int64 { return now.Add(-d).UnixMilli() }

// Затухающее расписание: свежую заявку сверяем часто, суточную реже,
// старую раз в день, а совсем старую не трогаем.
func TestRecheckDue(t *testing.T) {
	// Среда: обычный день, прохода по дню сборки нет.
	now := time.Date(2026, 9, 16, 12, 0, 0, 0, moscow)
	if now.Weekday() != time.Wednesday {
		t.Fatalf("день проверки выбран неверно: %v", now.Weekday())
	}

	cases := []struct {
		name      string
		age, last time.Duration // возраст заявки и когда сверяли
		want      bool
	}{
		{"свежая, сверяли только что", time.Hour, 10 * time.Minute, false},
		{"свежая, час не сверяли", time.Hour, 70 * time.Minute, true},
		{"вчерашняя, три часа не сверяли", 12 * time.Hour, 3 * time.Hour, false},
		{"вчерашняя, семь часов не сверяли", 12 * time.Hour, 7 * time.Hour, true},
		{"трёхдневная, полдня не сверяли", 72 * time.Hour, 12 * time.Hour, false},
		{"трёхдневная, сутки не сверяли", 72 * time.Hour, 25 * time.Hour, true},
		{"брошенная месяц назад", 30 * 24 * time.Hour, 30 * 24 * time.Hour, false},
	}
	for _, c := range cases {
		got := recheckDue(msAgo(now, c.age), msAgo(now, c.last), now)
		if got != c.want {
			t.Errorf("%s: пора сверять = %v, ожидалось %v", c.name, got, c.want)
		}
	}
}

// В день сборки бот проходит по заявкам утром, даже если сверял недавно:
// после сборки состав менять уже поздно.
func TestRecheckAssemblyDay(t *testing.T) {
	friday := time.Date(2026, 9, 18, 9, 0, 0, 0, moscow)
	if friday.Weekday() != time.Friday {
		t.Fatalf("день выбран неверно: %v", friday.Weekday())
	}
	age := 72 * time.Hour // по обычному расписанию — раз в сутки

	// Сверяли час назад, но ещё до утреннего часа сборки — идём.
	early := time.Date(2026, 9, 18, recheckHour-1, 0, 0, 0, moscow)
	if !recheckDue(msAgo(friday, age), early.UnixMilli(), friday) {
		t.Error("в день сборки утренний проход не сработал")
	}

	// А если уже прошли этим утром — второй раз не идём.
	done := time.Date(2026, 9, 18, recheckHour+10, 0, 0, 0, moscow)
	later := time.Date(2026, 9, 18, 20, 0, 0, 0, moscow)
	if recheckDue(msAgo(later, age), done.UnixMilli(), later) {
		t.Error("в день сборки сверка повторилась второй раз за утро")
	}

	// В обычный день утренний проход не срабатывает.
	wed := time.Date(2026, 9, 16, 9, 0, 0, 0, moscow)
	if recheckDue(msAgo(wed, age), msAgo(wed, time.Hour), wed) {
		t.Error("в обычный день сработал проход дня сборки")
	}
}

// testLeadOrder — заявка бота, взятая под сверку.
func testLeadOrder(t *testing.T, comment string, items []OrderItem) Order {
	t.Helper()
	o := Order{ClientName: "Покупатель", Status: StatusNew, Source: sourceMax,
		Comment: comment}
	if err := createOrderItems(&o, items, true); err != nil {
		t.Fatalf("заявка: %v", err)
	}
	watchOrder(o.ID, o.UpdatedAt)
	// Сдвигаем создание в прошлое, иначе по расписанию сверять ещё рано.
	if _, err := db.Exec(`UPDATE orders SET created_at = ? WHERE id = ?`,
		time.Now().Add(-2*time.Hour).UnixMilli(), o.ID); err != nil {
		t.Fatalf("возраст заявки: %v", err)
	}
	return o
}

// Расхождение состава с перепиской дописывается в комментарий.
func TestRecheckFindsMismatch(t *testing.T) {
	openTestDB(t)
	fakeAI(t, `{"ok":false,"note":"покупатель просил 2 кг, в заказе 3 кг"}`,
		http.StatusOK)
	p := testProduct(t, "Пельмени свинина и говядина", "кг", 500, 10)
	id := p.ID
	o := testLeadOrder(t, "Сообщение из MAX: привезите 2 кг пельменей",
		[]OrderItem{{ProductID: &id, Name: p.Name, Unit: "кг", Qty: 3, UnitPrice: 500}})

	runRecheck(time.Now().In(moscow))

	got, err := getOrder(o.ID)
	if err != nil {
		t.Fatalf("заказ: %v", err)
	}
	if !strings.Contains(got.Comment, "Сверка с перепиской") {
		t.Errorf("расхождение не попало в комментарий: %q", got.Comment)
	}
	if !strings.Contains(got.Comment, "покупатель просил 2 кг") {
		t.Errorf("в комментарии нет сути расхождения: %q", got.Comment)
	}
	// Количество бот не трогает — решает продавец.
	if got.Items[0].Qty != 3 {
		t.Errorf("бот сам исправил количество: %v", got.Items[0].Qty)
	}
}

// Забытый товар бот дописывает в заказ сам и отмечает это в комментарии.
// Неопознанный или без количества — только словами, а товар, который в
// заказе уже есть, не удваивается.
func TestRecheckAddsMissingItems(t *testing.T) {
	openTestDB(t)
	resetCatalog()
	fakeAI(t, `{"ok":false,"note":"нет сочней с творогом",
      "add":[{"name":"сочни с творогом","product":"Сочни с творогом","qty":6,"unit":"шт"},
             {"name":"блины","product":"Блины с творогом","qty":2,"unit":"кг"},
             {"name":"тушёнка","product":"","qty":1,"unit":"шт"},
             {"name":"фарш","product":"Фарш домашний","qty":0,"unit":"кг"}]}`,
		http.StatusOK)
	blini := testProduct(t, "Блины с творогом", "кг", 400, 10)
	sochni := testProduct(t, "Сочни с творогом", "шт", 60, 20)
	testProduct(t, "Фарш домашний", "кг", 450, 10)
	id := blini.ID
	o := testLeadOrder(t, "Сообщение из MAX: 1 кг блинов с творогом и 6 сочней с творогом",
		[]OrderItem{{ProductID: &id, Name: blini.Name, Unit: "кг", Qty: 1, UnitPrice: 400}})

	now := time.Now().In(moscow)
	runRecheck(now)

	got, err := getOrder(o.ID)
	if err != nil {
		t.Fatalf("заказ: %v", err)
	}
	if len(got.Items) != 2 {
		t.Fatalf("позиций %d, ожидалось 2 (блины + сочни): %+v", len(got.Items), got.Items)
	}
	for _, it := range got.Items {
		switch *it.ProductID {
		case blini.ID:
			if it.Qty != 1 {
				t.Errorf("бот изменил количество блинов, которые уже были: %v", it.Qty)
			}
		case sochni.ID:
			if it.Qty != 6 || it.UnitPrice != 60 {
				t.Errorf("сочни дописаны неверно: %v шт по %v", it.Qty, it.UnitPrice)
			}
		default:
			t.Errorf("бот дописал лишнее: %s", it.Name)
		}
	}
	if got.Total != 400+6*60 {
		t.Errorf("сумма заказа %v, ожидалось %v", got.Total, 400+6*60)
	}
	if !strings.Contains(got.Comment, "Бот дописал в заказ (проверьте): Сочни с творогом — 6 шт") {
		t.Errorf("в комментарии нет отметки о дописанном: %q", got.Comment)
	}
	if p, _ := getProduct(sochni.ID); p.Stock != 14 {
		t.Errorf("остаток сочней %v, ожидалось 14: заказ уже списан со склада", p.Stock)
	}

	// Следующая сверка видит сочни в заказе и второй раз их не дописывает,
	// даже если модель повторит свой ответ.
	runRecheck(now.Add(26 * time.Hour))
	again, _ := getOrder(o.ID)
	if len(again.Items) != 2 {
		t.Errorf("повторная сверка удвоила позиции: %+v", again.Items)
	}
	for _, it := range again.Items {
		if *it.ProductID == sochni.ID && it.Qty != 6 {
			t.Errorf("повторная сверка сложила количество сочней: %v", it.Qty)
		}
	}
}

// Состав совпал — в комментарий ничего не пишем: поток «всё в порядке»
// продавцу не нужен.
func TestRecheckSilentWhenFine(t *testing.T) {
	openTestDB(t)
	fakeAI(t, `{"ok":true,"note":""}`, http.StatusOK)
	o := testLeadOrder(t, "Сообщение из MAX: привезите 2 кг пельменей", nil)

	runRecheck(time.Now().In(moscow))

	got, err := getOrder(o.ID)
	if err != nil {
		t.Fatalf("заказ: %v", err)
	}
	if strings.Contains(got.Comment, "Сверка") {
		t.Errorf("при совпадении состава бот всё равно написал: %q", got.Comment)
	}
}

// Заказ правили руками — бот к нему больше не возвращается.
func TestRecheckStopsAfterManualEdit(t *testing.T) {
	openTestDB(t)
	calls := fakeAI(t, `{"ok":false,"note":"чего-то не хватает"}`, http.StatusOK)
	o := testLeadOrder(t, "Сообщение из MAX: привезите 2 кг пельменей", nil)

	// Продавец открыл заявку и поправил её в приложении.
	if _, err := db.Exec(`UPDATE orders SET comment = ?, updated_at = ? WHERE id = ?`,
		"поправлено вручную", nowMs()+1000, o.ID); err != nil {
		t.Fatalf("правка заказа: %v", err)
	}

	runRecheck(time.Now().In(moscow))

	if atomic.LoadInt32(calls) != 0 {
		t.Errorf("к модели обратились %d раз — заказ правили вручную", *calls)
	}
	got, err := getOrder(o.ID)
	if err != nil {
		t.Fatalf("заказ: %v", err)
	}
	if strings.Contains(got.Comment, "Сверка") {
		t.Errorf("бот дописал замечание в заказ, который ведёт продавец: %q", got.Comment)
	}

	// И дальше молчит навсегда, даже когда расписание снова подойдёт.
	runRecheck(time.Now().In(moscow).Add(48 * time.Hour))
	if atomic.LoadInt32(calls) != 0 {
		t.Errorf("сверка возобновилась после ручной правки: обращений %d", *calls)
	}
}

// Свою же запись бот за ручную правку не принимает: иначе первая сверка
// прекращала бы все последующие.
func TestRecheckContinuesAfterOwnNote(t *testing.T) {
	openTestDB(t)
	calls := fakeAI(t, `{"ok":false,"note":"не хватает фарша"}`, http.StatusOK)
	testLeadOrder(t, "Сообщение из MAX: привезите фарш", nil)

	now := time.Now().In(moscow)
	runRecheck(now)
	if atomic.LoadInt32(calls) != 1 {
		t.Fatalf("первая сверка не состоялась: обращений %d", *calls)
	}
	// Сутки спустя расписание снова подошло — сверка должна идти.
	runRecheck(now.Add(26 * time.Hour))
	if atomic.LoadInt32(calls) != 2 {
		t.Errorf("сверка прекратилась после собственной записи: обращений %d", *calls)
	}
}

// Закрытую заявку не сверяем: заказ уже собран.
func TestRecheckSkipsClosed(t *testing.T) {
	openTestDB(t)
	calls := fakeAI(t, `{"ok":false,"note":"что-то не так"}`, http.StatusOK)
	o := testLeadOrder(t, "Сообщение из MAX: привезите фарш", nil)

	if _, err := db.Exec(`UPDATE orders SET status = ? WHERE id = ?`,
		StatusDone, o.ID); err != nil {
		t.Fatalf("статус заказа: %v", err)
	}
	runRecheck(time.Now().In(moscow))

	if atomic.LoadInt32(calls) != 0 {
		t.Errorf("сверяли закрытую заявку: обращений %d", *calls)
	}
}

// Разбор выключен переключателем — сверка тоже молчит.
func TestRecheckOffWithAI(t *testing.T) {
	openTestDB(t)
	calls := fakeAI(t, `{"ok":false,"note":"что-то не так"}`, http.StatusOK)
	testLeadOrder(t, "Сообщение из MAX: привезите фарш", nil)
	setAIEnabled(false)

	runRecheck(time.Now().In(moscow))

	if atomic.LoadInt32(calls) != 0 {
		t.Errorf("сверка работала при выключенном разборе: обращений %d", *calls)
	}
}

// ------------------------------------------------ удалённая заявка

// Продавец удалил заявку, а покупатель потом исправил своё сообщение.
// Заявка не воскресает: удалили — значит так решили.
func TestEditedMessageDoesNotResurrectDeleted(t *testing.T) {
	openTestDB(t)
	resetSiteCache()
	setCatchPhones(true)
	t.Cleanup(func() { setCatchPhones(false) })
	fakeAI(t, `{"order":true,"confidence":0.98,"summary":"пельмени"}`, http.StatusOK)
	newFakeMax(t)
	api := newMaxAPI("test-token")
	ctx := context.Background()

	handleBotUpdate(ctx, api, textUpdate(911, 0, "привезите 3 кг пельменей"))
	orders, err := listOrders(-1, "")
	if err != nil || len(orders) != 1 {
		t.Fatalf("заявок %d (err %v), ожидалась одна", len(orders), err)
	}
	if err := removeOrder(orders[0].ID, t.TempDir()); err != nil {
		t.Fatalf("удаление заявки: %v", err)
	}

	edited := textUpdate(911, 0, "привезите 2 кг пельменей")
	edited.Type = "message_edited"
	handleBotUpdate(ctx, api, edited)

	orders, err = listOrders(-1, "")
	if err != nil {
		t.Fatalf("список заказов: %v", err)
	}
	if len(orders) != 0 {
		t.Errorf("удалённая заявка воскресла после правки сообщения: %d", len(orders))
	}
}

// А новое сообщение того же человека заявку заводит заново — это не
// воскрешение удалённой, а новая просьба.
func TestNewMessageAfterDeleteCreatesOrder(t *testing.T) {
	openTestDB(t)
	resetSiteCache()
	setCatchPhones(true)
	t.Cleanup(func() { setCatchPhones(false) })
	fakeAI(t, `{"order":true,"confidence":0.98,"summary":"пельмени"}`, http.StatusOK)
	newFakeMax(t)
	api := newMaxAPI("test-token")
	ctx := context.Background()

	handleBotUpdate(ctx, api, textUpdate(912, 0, "привезите 3 кг пельменей"))
	orders, _ := listOrders(-1, "")
	if len(orders) != 1 {
		t.Fatalf("заявок %d, ожидалась одна", len(orders))
	}
	if err := removeOrder(orders[0].ID, t.TempDir()); err != nil {
		t.Fatalf("удаление заявки: %v", err)
	}

	handleBotUpdate(ctx, api, textUpdate(912, 0, "а теперь привезите 2 кг фарша"))
	orders, _ = listOrders(-1, "")
	if len(orders) != 1 {
		t.Errorf("новое сообщение после удаления не завело заявку: %d", len(orders))
	}
}
