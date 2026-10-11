package main

import (
	"net/http/httptest"
	"strings"
	"testing"
)

// Покупатель заказывает целыми: дробное округляется, меньше одного не бывает.
func TestBuyerQtyWholeOnly(t *testing.T) {
	cases := []struct{ in, want float64 }{
		{0, 1}, {-3, 1}, {0.5, 1}, {0.9, 1}, {1, 1},
		{1.4, 1}, {1.5, 2}, {2.7, 3}, {4, 4}, {5000, 1000},
	}
	for _, c := range cases {
		if got := buyerQty(c.in); got != c.want {
			t.Errorf("buyerQty(%v) = %v, ожидалось %v", c.in, got, c.want)
		}
	}
}

// Бегущая строка с новостями идёт и на главной, и в каталоге: покупатель чаще
// всего заходит сразу в каталог, и объявление там нужнее.
func TestTickerOnHomeAndCatalog(t *testing.T) {
	openTestDB(t)
	resetSiteCache()
	loadTemplatesForTest(t)

	news := NewsItem{Text: "Свежая партия пельменей по пятницам", Published: true}
	if err := saveNews(&news); err != nil {
		t.Fatalf("новость: %v", err)
	}
	testProduct(t, "Пельмени", "кг", 500, 5)

	for _, path := range []string{"/", "/catalog"} {
		w := httptest.NewRecorder()
		r := httptest.NewRequest("GET", path, nil)
		if path == "/" {
			handleHome(w, r)
		} else {
			handleCatalog(w, r)
		}
		body := w.Body.String()
		if !strings.Contains(body, "ticker-track") {
			t.Errorf("%s: нет бегущей строки", path)
		}
		if !strings.Contains(body, news.Text) {
			t.Errorf("%s: в бегущей строке нет новости", path)
		}
	}
}

// Условия доставки в оформлении заказа — те же, что в разделе «Доставка и
// оплата»: их правят из приложения, и дублировать их в шаблоне нельзя,
// иначе витрина и оформление начнут обещать разное.
func TestCheckoutShowsSiteDelivery(t *testing.T) {
	openTestDB(t)
	resetSiteCache()
	loadTemplatesForTest(t)

	s := siteDefaults
	s.Delivery = "Развозим по вторникам и пятницам."
	if err := saveSiteText(s); err != nil {
		t.Fatalf("тексты сайта: %v", err)
	}

	p := testProduct(t, "Колбаса", "кг", 700, 5)
	cart := addToCart(t, nil, p.ID, 1)

	w := httptest.NewRecorder()
	r := httptest.NewRequest("GET", "/checkout", nil)
	r.AddCookie(cart)
	handleCheckoutForm(w, r)

	body := w.Body.String()
	if !strings.Contains(body, s.Delivery) {
		t.Errorf("в оформлении нет условий доставки с сайта")
	}
	if !strings.Contains(body, "Согласование по телефону") {
		t.Errorf("у самовывоза нет подписи про согласование")
	}
}

// loadTemplatesForTest — шаблоны собираются один раз на весь запуск тестов.
func loadTemplatesForTest(t *testing.T) {
	t.Helper()
	if len(tpl) > 0 {
		return
	}
	if err := loadTemplates(); err != nil {
		t.Fatalf("шаблоны: %v", err)
	}
}
