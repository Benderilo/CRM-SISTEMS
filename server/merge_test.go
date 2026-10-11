package main

import (
	"errors"
	"testing"
)

// Второй заказ с того же телефона не заводится заново: товары дописываются
// в открытый заказ, одинаковый товар складывается в одну строку,
// а склад списывается ровно один раз за каждый килограмм.
func TestSecondOrderMergesIntoOpenOne(t *testing.T) {
	openTestDB(t)
	meat := testProduct(t, "Фарш", "кг", 400, 10)
	buns := testProduct(t, "Булочки", "шт", 30, 100)

	first := Order{ClientName: "Петров", Phone: "+79990001122", Address: "Мира 1",
		Delivery: "Доставка", Comment: "позвонить за час", Status: StatusNew}
	if err := createOrder(&first, []CartLine{{Product: meat, Qty: 2}}); err != nil {
		t.Fatalf("первый заказ: %v", err)
	}

	// Тот же человек, но телефон записан по-другому — это один покупатель.
	second := Order{ClientName: "Петров", Phone: "8 (999) 000-11-22", Address: "Мира 2",
		Delivery: "Самовывоз", Comment: "без лука", Status: StatusNew}
	if err := createOrder(&second, []CartLine{
		{Product: meat, Qty: 1},
		{Product: buns, Qty: 5},
	}); err != nil {
		t.Fatalf("второй заказ: %v", err)
	}

	if !second.Merged {
		t.Fatalf("заказ создан заново, а не дописан: %#v", second)
	}
	if second.ID != first.ID {
		t.Fatalf("дописали не в тот заказ: %d вместо %d", second.ID, first.ID)
	}

	orders, _ := listOrders(-1, "")
	if len(orders) != 1 {
		t.Fatalf("заказов на сайте %d, ожидался один", len(orders))
	}

	got, err := getOrder(first.ID)
	if err != nil {
		t.Fatalf("чтение заказа: %v", err)
	}
	if len(got.Items) != 2 {
		t.Fatalf("позиций %d, ожидалось 2: %#v", len(got.Items), got.Items)
	}
	if got.Items[0].Qty != 3 {
		t.Errorf("фарша в заказе %v, ожидалось 3", got.Items[0].Qty)
	}
	if got.Total != 3*400+5*30 {
		t.Errorf("сумма заказа %v", got.Total)
	}

	// Свежие пожелания перебивают старые, а комментарии копятся оба.
	if got.Address != "Мира 2" || got.Delivery != "Самовывоз" {
		t.Errorf("адрес %q, получение %q — ожидались данные из последнего заказа",
			got.Address, got.Delivery)
	}
	if got.Comment != "позвонить за час\nбез лука" {
		t.Errorf("комментарий %q", got.Comment)
	}

	m, _ := getProduct(meat.ID)
	b, _ := getProduct(buns.ID)
	if m.Stock != 7 || b.Stock != 95 {
		t.Errorf("склад: фарш %v (ожидалось 7), булочки %v (ожидалось 95)", m.Stock, b.Stock)
	}
}

// Заказ уже приняли, а покупатель дозаказал — заказ возвращается в «Новый»:
// состав стал другим, и продавцу нужно взглянуть на него заново.
func TestMergeReopensAcceptedOrder(t *testing.T) {
	openTestDB(t)
	p := testProduct(t, "Рёбра", "кг", 600, 20)

	first := Order{Phone: "+79990001122", Status: StatusNew}
	if err := createOrder(&first, []CartLine{{Product: p, Qty: 1}}); err != nil {
		t.Fatalf("первый заказ: %v", err)
	}
	if err := setOrderStatus(first.ID, StatusAccepted); err != nil {
		t.Fatalf("подтверждение заказа: %v", err)
	}

	second := Order{Phone: "+79990001122", Status: StatusNew}
	if err := createOrder(&second, []CartLine{{Product: p, Qty: 2}}); err != nil {
		t.Fatalf("дозаказ: %v", err)
	}
	if !second.Merged {
		t.Fatalf("дозаказ завёлся отдельным заказом")
	}

	got, err := getOrder(first.ID)
	if err != nil {
		t.Fatalf("чтение заказа: %v", err)
	}
	if got.Status != StatusNew {
		t.Errorf("статус %q, ожидался «Новый»", got.StatusName())
	}
	if got.Total != 1800 {
		t.Errorf("сумма заказа %v, ожидалось 1800", got.Total)
	}
	// Склад двигается только на дозаказанное: 20 − 1 − 2.
	if after, _ := getProduct(p.ID); after.Stock != 17 {
		t.Errorf("склад %v, ожидалось 17", after.Stock)
	}
}

// Закрытый заказ дописывать нельзя: выполненный — это уже выручка,
// отменённый — возвращённый на склад товар. В обоих случаях заводится новый.
func TestClosedOrdersAreNotMerged(t *testing.T) {
	openTestDB(t)
	p := testProduct(t, "Рёбра", "кг", 600, 20)

	done := Order{Phone: "+79990001122", Status: StatusDone}
	if err := createOrder(&done, []CartLine{{Product: p, Qty: 1}}); err != nil {
		t.Fatalf("выполненный заказ: %v", err)
	}
	canceled := Order{Phone: "+79990001122", Status: StatusCanceled}
	if err := createOrder(&canceled, []CartLine{{Product: p, Qty: 1}}); err != nil {
		t.Fatalf("отменённый заказ: %v", err)
	}
	fresh := Order{Phone: "+79990001122", Status: StatusNew}
	if err := createOrder(&fresh, []CartLine{{Product: p, Qty: 1}}); err != nil {
		t.Fatalf("новый заказ: %v", err)
	}
	if fresh.Merged || fresh.ID == done.ID || fresh.ID == canceled.ID {
		t.Fatalf("новый заказ подшили к закрытому: %#v", fresh)
	}

	// Продажа из приложения — архив сделки, к ней тоже ничего не дописывается.
	sale := Order{Phone: "+79995550000", Status: StatusNew, Source: sourceApp}
	if err := createOrderItems(&sale, []OrderItem{{Name: "Сало", Unit: "кг",
		Qty: 1, UnitPrice: 300}}, false); err != nil {
		t.Fatalf("продажа из приложения: %v", err)
	}
	site := Order{Phone: "+79995550000", Status: StatusNew}
	if err := createOrder(&site, []CartLine{{Product: p, Qty: 2}}); err != nil {
		t.Fatalf("заказ с сайта: %v", err)
	}
	if site.Merged {
		t.Errorf("заказ с сайта дописали в продажу из приложения")
	}
}

// Старые дубли сводятся вручную: приложение присылает id, сервер оставляет
// самый ранний заказ, переносит в него состав и удаляет остальные.
// Склад при переносе не двигается — товар никуда не делся.
func TestMergeOrdersJoinsExisting(t *testing.T) {
	openTestDB(t)
	p := testProduct(t, "Колбаса", "кг", 500, 10)

	// Заказы заводим напрямую, минуя createOrder: имитируем дубли,
	// накопившиеся до того, как объединение заработало само.
	first := Order{Phone: "+79990001122", Status: StatusNew, CreatedAt: 1000}
	if err := createOrderItems(&first, []OrderItem{{ProductID: &p.ID, Name: p.Name,
		Unit: p.Unit, Qty: 1, UnitPrice: p.Price, UnitCost: p.Cost}}, true); err != nil {
		t.Fatalf("первый заказ: %v", err)
	}
	second := Order{Phone: "+79990001122", Status: StatusAccepted, CreatedAt: 2000}
	if err := createOrderItems(&second, []OrderItem{{ProductID: &p.ID, Name: p.Name,
		Unit: p.Unit, Qty: 2, UnitPrice: p.Price, UnitCost: p.Cost}}, true); err != nil {
		t.Fatalf("второй заказ: %v", err)
	}

	stockBefore, _ := getProduct(p.ID)

	merged, err := mergeOrders([]int64{second.ID, first.ID})
	if err != nil {
		t.Fatalf("объединение: %v", err)
	}
	if merged.ID != first.ID {
		t.Errorf("остался заказ %d, ожидался самый ранний %d", merged.ID, first.ID)
	}
	if len(merged.Items) != 1 || merged.Items[0].Qty != 3 || merged.Total != 1500 {
		t.Errorf("состав объединённого заказа: %#v, сумма %v", merged.Items, merged.Total)
	}
	if merged.Status != StatusNew {
		t.Errorf("статус объединённого заказа %q, ожидался «Новый»", merged.StatusName())
	}
	if orders, _ := listOrders(-1, ""); len(orders) != 1 {
		t.Errorf("заказов осталось %d, ожидался один", len(orders))
	}
	if after, _ := getProduct(p.ID); after.Stock != stockBefore.Stock {
		t.Errorf("склад изменился при объединении: было %v, стало %v",
			stockBefore.Stock, after.Stock)
	}
}

// Заказы разных людей и закрытые заказы объединять нельзя — приложение
// получит понятный отказ, а не молча слепленный чужой состав.
func TestMergeOrdersRejectsMismatch(t *testing.T) {
	openTestDB(t)
	p := testProduct(t, "Сало", "кг", 300, 10)

	mine := Order{Phone: "+79990001122", Status: StatusNew}
	if err := createOrder(&mine, []CartLine{{Product: p, Qty: 1}}); err != nil {
		t.Fatalf("заказ: %v", err)
	}
	other := Order{Phone: "+79993334455", Status: StatusNew}
	if err := createOrder(&other, []CartLine{{Product: p, Qty: 1}}); err != nil {
		t.Fatalf("чужой заказ: %v", err)
	}
	closed := Order{Phone: "+79990001122", Status: StatusDone}
	if err := createOrder(&closed, []CartLine{{Product: p, Qty: 1}}); err != nil {
		t.Fatalf("выполненный заказ: %v", err)
	}

	if _, err := mergeOrders([]int64{mine.ID, other.ID}); !errors.Is(err, errMergeNotAllowed) {
		t.Errorf("разные телефоны объединились: %v", err)
	}
	if _, err := mergeOrders([]int64{mine.ID, closed.ID}); !errors.Is(err, errMergeNotAllowed) {
		t.Errorf("выполненный заказ объединился: %v", err)
	}
	if _, err := mergeOrders([]int64{mine.ID}); !errors.Is(err, errMergeNotAllowed) {
		t.Errorf("объединение одного заказа: %v", err)
	}
}
