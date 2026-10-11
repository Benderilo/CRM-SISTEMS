package main

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// Покупатель передумал: одну позицию берёт меньше, от другой отказывается.
// Сумма заказа пересчитывается, а разница возвращается на склад.
func TestSetOrderItemQty(t *testing.T) {
	openTestDB(t)
	meat := testProduct(t, "Фарш", "кг", 400, 10)
	buns := testProduct(t, "Булочки", "шт", 30, 100)

	o := Order{ClientName: "Тест", Phone: "+79990001122", Status: StatusNew}
	if err := createOrder(&o, []CartLine{
		{Product: meat, Qty: 3},
		{Product: buns, Qty: 10},
	}); err != nil {
		t.Fatalf("создание заказа: %v", err)
	}
	if o.Total != 3*400+10*30 {
		t.Fatalf("сумма заказа %v", o.Total)
	}
	items, _ := listOrderItems(o.ID)
	if len(items) != 2 {
		t.Fatalf("позиций %d", len(items))
	}

	// Фарша берут 1,5 вместо 3 — полтора килограмма возвращаются на склад.
	if err := setOrderItemQty(o.ID, items[0].ID, 1.5); err != nil {
		t.Fatalf("правка количества: %v", err)
	}
	p, _ := getProduct(meat.ID)
	if p.Stock != 8.5 {
		t.Errorf("склад фарша %v, ожидалось 8.5", p.Stock)
	}
	got, _ := getOrder(o.ID)
	if got.Total != 1.5*400+10*30 {
		t.Errorf("сумма после правки %v", got.Total)
	}

	// От булочек отказались совсем — все 10 вернулись.
	if err := setOrderItemQty(o.ID, items[1].ID, 0); err != nil {
		t.Fatalf("удаление позиции: %v", err)
	}
	b, _ := getProduct(buns.ID)
	if b.Stock != 100 {
		t.Errorf("склад булочек %v, ожидалось 100", b.Stock)
	}
	got, _ = getOrder(o.ID)
	if len(got.Items) != 1 || got.Total != 1.5*400 {
		t.Errorf("после удаления позиций %d, сумма %v", len(got.Items), got.Total)
	}

	// Последнюю позицию убрать нельзя — это уже отмена заказа.
	if err := setOrderItemQty(o.ID, items[0].ID, 0); !errors.Is(err, errLastItem) {
		t.Errorf("удаление последней позиции вернуло %v", err)
	}

	// У отменённого заказа состав не правим: товар уже вернулся на склад.
	if err := setOrderStatus(o.ID, StatusCanceled); err != nil {
		t.Fatalf("отмена: %v", err)
	}
	if err := setOrderItemQty(o.ID, items[0].ID, 1); !errors.Is(err, errOrderCanceled) {
		t.Errorf("правка отменённого заказа вернула %v", err)
	}
	p, _ = getProduct(meat.ID)
	if p.Stock != 10 {
		t.Errorf("после отмены склад фарша %v, ожидалось 10 — вернуться должно ровно то,"+
			" что осталось в заказе", p.Stock)
	}
}

// Продажу из приложения склад не двигает — правка её состава тоже не должна.
func TestSetOrderItemQtyWithoutStockWriteOff(t *testing.T) {
	openTestDB(t)
	p := testProduct(t, "Колбаса", "кг", 700, 5)

	o := Order{ClientName: "Продавец", Status: StatusDone, Source: sourceApp}
	pid := p.ID
	if err := createOrderItems(&o, []OrderItem{
		{ProductID: &pid, Name: p.Name, Unit: p.Unit, Qty: 2, UnitPrice: p.Price},
		{Name: "Пакет", Unit: "шт", Qty: 1, UnitPrice: 5},
	}, false); err != nil {
		t.Fatalf("создание продажи: %v", err)
	}
	items, _ := listOrderItems(o.ID)

	if err := setOrderItemQty(o.ID, items[0].ID, 1); err != nil {
		t.Fatalf("правка количества: %v", err)
	}
	got, _ := getProduct(p.ID)
	if got.Stock != 5 {
		t.Errorf("склад %v — у непросписанного заказа он двигаться не должен", got.Stock)
	}
	order, _ := getOrder(o.ID)
	if order.Total != 700+5 {
		t.Errorf("сумма %v, ожидалось 705", order.Total)
	}
}

// Дозаказ: покупатель просит добавить товар. Цена берётся текущая,
// склад списывается, а повторное добавление складывает количество.
func TestAddOrderItem(t *testing.T) {
	openTestDB(t)
	meat := testProduct(t, "Фарш", "кг", 400, 10)
	buns := testProduct(t, "Булочки", "шт", 30, 100)

	o := Order{ClientName: "Тест", Status: StatusAccepted}
	if err := createOrder(&o, []CartLine{{Product: meat, Qty: 2}}); err != nil {
		t.Fatalf("создание заказа: %v", err)
	}

	if err := addOrderItem(o.ID, buns.ID, 5); err != nil {
		t.Fatalf("добавление товара: %v", err)
	}
	got, _ := getOrder(o.ID)
	if len(got.Items) != 2 || got.Total != 2*400+5*30 {
		t.Fatalf("после дозаказа позиций %d, сумма %v", len(got.Items), got.Total)
	}
	b, _ := getProduct(buns.ID)
	if b.Stock != 95 {
		t.Errorf("склад булочек %v, ожидалось 95", b.Stock)
	}

	// Тот же товар ещё раз — не вторая строка, а плюс к количеству.
	if err := addOrderItem(o.ID, buns.ID, 2.5); err != nil {
		t.Fatalf("повторное добавление: %v", err)
	}
	got, _ = getOrder(o.ID)
	if len(got.Items) != 2 {
		t.Errorf("позиций %d — товар должен был слиться в одну строку", len(got.Items))
	}
	if got.Total != 2*400+7.5*30 {
		t.Errorf("сумма %v", got.Total)
	}

	// В отменённый заказ товар не дописывается.
	if err := setOrderStatus(o.ID, StatusCanceled); err != nil {
		t.Fatalf("отмена: %v", err)
	}
	if err := addOrderItem(o.ID, buns.ID, 1); !errors.Is(err, errOrderCanceled) {
		t.Errorf("добавление в отменённый заказ вернуло %v", err)
	}
}

// Чистка архива: сначала убираем выполненные, потом все. Склад при этом
// не двигаем — товар покупателям уже отдан.
func TestPurgeOrders(t *testing.T) {
	openTestDB(t)
	p := testProduct(t, "Пельмени", "кг", 400, 10)
	dir := t.TempDir()

	done := Order{ClientName: "Первый", Status: StatusDone}
	if err := createOrder(&done, []CartLine{{Product: p, Qty: 1}}); err != nil {
		t.Fatalf("заказ: %v", err)
	}
	active := Order{ClientName: "Второй", Status: StatusAccepted}
	if err := createOrder(&active, []CartLine{{Product: p, Qty: 2}}); err != nil {
		t.Fatalf("заказ: %v", err)
	}
	stockBefore, _ := getProduct(p.ID)

	n, err := purgeOrders(true, dir)
	if err != nil || n != 1 {
		t.Fatalf("удалено выполненных %d (err %v), ожидался один", n, err)
	}
	left, _ := listOrders(-1, "")
	if len(left) != 1 || left[0].ID != active.ID {
		t.Fatalf("после чистки осталось %#v", left)
	}
	// Позиции удалённого заказа тоже ушли.
	items, _ := listOrderItems(done.ID)
	if len(items) != 0 {
		t.Errorf("позиции удалённого заказа остались: %d", len(items))
	}
	if got, _ := getProduct(p.ID); got.Stock != stockBefore.Stock {
		t.Errorf("склад изменился при чистке: %v → %v", stockBefore.Stock, got.Stock)
	}

	// Копия удалённого лежит рядом с базой.
	files, _ := filepath.Glob(filepath.Join(dir, "orders-deleted-*.json"))
	if len(files) != 1 {
		t.Fatalf("копия удалённых заказов не сохранена: %#v", files)
	}
	data, _ := os.ReadFile(files[0])
	if !strings.Contains(string(data), "Первый") {
		t.Errorf("в копии нет удалённого заказа")
	}

	n, err = purgeOrders(false, dir)
	if err != nil || n != 1 {
		t.Fatalf("удаление всех: %d (err %v)", n, err)
	}
	if left, _ := listOrders(-1, ""); len(left) != 0 {
		t.Errorf("заказы остались: %d", len(left))
	}
}

// Удаление одного заказа: копия ложится рядом с базой, а списанный товар
// возвращается на склад — заказа больше нет, значит и остаток за ним не числится.
func TestRemoveOrder(t *testing.T) {
	openTestDB(t)
	dir := t.TempDir()
	p := testProduct(t, "Колбаса", "кг", 800, 10)

	o := Order{ClientName: "Иван", Phone: "+79001112233", Status: StatusNew}
	if err := createOrder(&o, []CartLine{{Product: p, Qty: 3}}); err != nil {
		t.Fatalf("создание заказа: %v", err)
	}
	if got, _ := getProduct(p.ID); got.Stock != 7 {
		t.Fatalf("остаток после заказа %v, ожидалось 7", got.Stock)
	}

	if err := removeOrder(o.ID, dir); err != nil {
		t.Fatalf("удаление заказа: %v", err)
	}
	if _, err := getOrder(o.ID); err == nil {
		t.Errorf("заказ остался в базе")
	}
	if got, _ := getProduct(p.ID); got.Stock != 10 {
		t.Errorf("остаток после удаления %v, ожидалось 10", got.Stock)
	}
	files, _ := filepath.Glob(filepath.Join(dir, "orders-deleted-*.json"))
	if len(files) != 1 {
		t.Errorf("копия удалённого заказа не сохранена: %v", files)
	}

	// Отменённый заказ склад уже вернул — второй раз не возвращаем.
	o2 := Order{ClientName: "Пётр", Phone: "+79004445566", Status: StatusNew}
	if err := createOrder(&o2, []CartLine{{Product: p, Qty: 2}}); err != nil {
		t.Fatalf("создание заказа: %v", err)
	}
	if err := setOrderStatus(o2.ID, StatusCanceled); err != nil {
		t.Fatalf("отмена заказа: %v", err)
	}
	if err := removeOrder(o2.ID, dir); err != nil {
		t.Fatalf("удаление отменённого: %v", err)
	}
	if got, _ := getProduct(p.ID); got.Stock != 10 {
		t.Errorf("остаток после удаления отменённого %v, ожидалось 10", got.Stock)
	}
}
