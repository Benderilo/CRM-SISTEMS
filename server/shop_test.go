package main

import (
	"net/http"
	"net/http/httptest"
	"net/url"
	"path/filepath"
	"strings"
	"testing"
)

// Набор заказа подряд: каждое «В корзину» кладёт ещё одну единицу и возвращает
// покупателя на ту же страницу каталога, а не уводит в корзину.
func TestCartAddKeepsCollecting(t *testing.T) {
	openTestDB(t)
	p := testProduct(t, "Пельмени", "кг", 500, 3)

	var cart *http.Cookie
	for i := 1; i <= 3; i++ {
		r := postForm("/cart/add", url.Values{
			"id":   {i64(p.ID)},
			"qty":  {"1"},
			"back": {"/catalog?cat=Полуфабрикаты"},
		}, cart)
		w := httptest.NewRecorder()
		handleCartAdd(w, r)

		if w.Code != http.StatusSeeOther {
			t.Fatalf("добавление %d: код %d", i, w.Code)
		}
		loc := w.Header().Get("Location")
		if !strings.HasPrefix(loc, "/catalog?") || !strings.Contains(loc, "cat=") {
			t.Fatalf("добавление %d вернуло на %q, ожидался каталог", i, loc)
		}
		cart = cartCookie(t, w)
	}

	if got := qtyInCart(t, cart, p.ID); got != 3 {
		t.Fatalf("после трёх нажатий в корзине %v, ожидалось 3", got)
	}
}

// Покупатель заказывает целыми: дробное количество в поле округляется,
// меньше одного положить нельзя, а ноль означает то же самое, что «Убрать».
func TestCartUpdateWholeQty(t *testing.T) {
	openTestDB(t)
	meat := testProduct(t, "Фарш", "кг", 400, 10)
	box := testProduct(t, "Котлеты", "шт", 90, 10)

	cart := addToCart(t, nil, meat.ID, 1)
	cart = addToCart(t, cart, box.ID, 1)

	r := postForm("/cart/update", url.Values{
		"qty_" + i64(meat.ID): {"4,5"},
		"qty_" + i64(box.ID):  {"0,5"},
	}, cart)
	w := httptest.NewRecorder()
	handleCartUpdate(w, r)
	cart = cartCookie(t, w)

	if got := qtyInCart(t, cart, meat.ID); got != 5 {
		t.Errorf("фарша в корзине %v, ожидалось 5 — 4,5 округляется", got)
	}
	if got := qtyInCart(t, cart, box.ID); got != 1 {
		t.Errorf("котлет в корзине %v, ожидалось 1 — меньше одной не заказать", got)
	}

	// Ноль в поле убирает позицию, «Оформить заказ» ведёт на оформление.
	r = postForm("/cart/update", url.Values{
		"qty_" + i64(meat.ID): {"0"},
		"qty_" + i64(box.ID):  {"2"},
		"go":                  {"checkout"},
	}, cart)
	w = httptest.NewRecorder()
	handleCartUpdate(w, r)
	if loc := w.Header().Get("Location"); loc != "/checkout" {
		t.Errorf("после «Оформить заказ» ушли на %q", loc)
	}
	cart = cartCookie(t, w)
	if got := qtyInCart(t, cart, meat.ID); got != 0 {
		t.Errorf("позиция с нулём осталась в корзине: %v", got)
	}
	if got := qtyInCart(t, cart, box.ID); got != 2 {
		t.Errorf("котлет в корзине %v, ожидалось 2", got)
	}
}

// Остаток покупателя не ограничивает: заказать можно больше, чем есть на складе.
// Склад уходит в минус — и отмена заказа возвращает ровно столько же.
func TestOrderOverStockAndCancel(t *testing.T) {
	openTestDB(t)
	p := testProduct(t, "Колбаса", "кг", 700, 2)

	cart := addToCart(t, nil, p.ID, 5)
	r := postForm("/cart", nil, cart)
	built := buildCart(r)
	if len(built.Lines) != 1 || built.Lines[0].Qty != 5 {
		t.Fatalf("корзина обрезала количество по остатку: %+v", built.Lines)
	}

	o := Order{ClientName: "Тест", Phone: "+79990001122", Status: StatusNew}
	if err := createOrder(&o, built.Lines); err != nil {
		t.Fatalf("создание заказа: %v", err)
	}
	got, _ := getProduct(p.ID)
	if got.Stock != -3 {
		t.Fatalf("после заказа 5 при остатке 2 склад %v, ожидалось -3", got.Stock)
	}

	if err := setOrderStatus(o.ID, StatusCanceled); err != nil {
		t.Fatalf("отмена заказа: %v", err)
	}
	got, _ = getProduct(p.ID)
	if got.Stock != 2 {
		t.Fatalf("после отмены склад %v, ожидалось 2 — движение должно быть обратимым", got.Stock)
	}
}

// Шаг кнопок «−»/«+» — целая единица для любого товара: килограммы берут
// круглыми числами, а дробное количество вписывают руками.
func TestProductStep(t *testing.T) {
	cases := []struct {
		unit string
		step float64
		min  float64
	}{
		{"кг", 1, 1},
		{"л", 1, 1},
		{"шт", 1, 1},
		{"уп", 1, 1},
	}
	for _, c := range cases {
		p := Product{Unit: c.unit}
		if p.Step() != c.step || p.MinQty() != c.min {
			t.Errorf("%q: шаг %v, минимум %v; ожидалось %v и %v",
				c.unit, p.Step(), p.MinQty(), c.step, c.min)
		}
	}
}

// ------------------------------------------------------------- вспомогательное

// openTestDB даёт тесту свою пустую базу на время работы.
func openTestDB(t *testing.T) {
	t.Helper()
	if err := openDB(filepath.Join(t.TempDir(), "shop.db")); err != nil {
		t.Fatalf("открытие базы: %v", err)
	}
	t.Cleanup(func() { db.Close() })
}

func testProduct(t *testing.T, name, unit string, price, stock float64) Product {
	t.Helper()
	p := Product{Name: name, Unit: unit, Price: price, Cost: price / 2,
		Stock: stock, Published: true}
	if err := insertProduct(&p); err != nil {
		t.Fatalf("вставка товара: %v", err)
	}
	return p
}

func postForm(path string, form url.Values, cart *http.Cookie) *http.Request {
	r := httptest.NewRequest(http.MethodPost, path, strings.NewReader(form.Encode()))
	r.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	if cart != nil {
		r.AddCookie(cart)
	}
	return r
}

func addToCart(t *testing.T, cart *http.Cookie, id int64, qty float64) *http.Cookie {
	t.Helper()
	r := postForm("/cart/add", url.Values{
		"id": {i64(id)}, "qty": {formatQty(qty)},
	}, cart)
	w := httptest.NewRecorder()
	handleCartAdd(w, r)
	return cartCookie(t, w)
}

func cartCookie(t *testing.T, w *httptest.ResponseRecorder) *http.Cookie {
	t.Helper()
	for _, c := range w.Result().Cookies() {
		if c.Name == cookieCart {
			return c
		}
	}
	t.Fatal("сервер не выставил cookie корзины")
	return nil
}

func qtyInCart(t *testing.T, cart *http.Cookie, id int64) float64 {
	t.Helper()
	r := postForm("/cart", nil, cart)
	for _, e := range readCart(r) {
		if e.ID == id {
			return e.Qty
		}
	}
	return 0
}
