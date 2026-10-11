package main

import "testing"

// Пока в приложении ничего не заполнили, витрина показывает тексты по умолчанию;
// сохранённое значение их вытесняет, а очищенное поле возвращает умолчание.
func TestSiteTextDefaultsAndSave(t *testing.T) {
	openTestDB(t)
	resetSiteCache()

	if got := siteText().Phone; got != siteDefaults.Phone {
		t.Fatalf("телефон по умолчанию %q, ожидался %q", got, siteDefaults.Phone)
	}

	s := siteDefaults
	s.Phone = "+7 (952) 111-22-33"
	s.About = "Готовим сами.\n\nБез сои."
	if err := saveSiteText(s); err != nil {
		t.Fatalf("сохранение текстов: %v", err)
	}
	got := siteText()
	if got.Phone != s.Phone {
		t.Errorf("телефон %q, ожидался %q", got.Phone, s.Phone)
	}
	if got.PhoneLink() != "+79521112233" {
		t.Errorf("ссылка tel: %q", got.PhoneLink())
	}
	// Пустые строки в абзацы не превращаются — иначе на странице зияли бы дыры.
	if lines := got.AboutLines(); len(lines) != 2 || lines[1] != "Без сои." {
		t.Errorf("абзацы «о продукции»: %#v", lines)
	}

	s.Phone = "   "
	if err := saveSiteText(s); err != nil {
		t.Fatalf("сохранение пустого телефона: %v", err)
	}
	if got := siteText().Phone; got != siteDefaults.Phone {
		t.Errorf("после очистки телефон %q, ожидалось умолчание %q", got, siteDefaults.Phone)
	}
}

// Покупателю видны только опубликованные новости, в приложении — все.
func TestNewsPublishing(t *testing.T) {
	openTestDB(t)
	resetSiteCache()

	shown := NewsItem{Text: "Свежая партия по вторникам", Published: true}
	hidden := NewsItem{Text: "Черновик", Published: false}
	for _, n := range []*NewsItem{&shown, &hidden} {
		if err := saveNews(n); err != nil {
			t.Fatalf("сохранение новости: %v", err)
		}
	}

	all, err := listNews(false)
	if err != nil || len(all) != 2 {
		t.Fatalf("в приложении новостей %d (err %v), ожидалось 2", len(all), err)
	}
	pub, err := listNews(true)
	if err != nil || len(pub) != 1 || pub[0].Text != shown.Text {
		t.Fatalf("на витрине новости %#v (err %v)", pub, err)
	}

	// Снятие с показа и удаление.
	shown.Published = false
	if err := saveNews(&shown); err != nil {
		t.Fatalf("правка новости: %v", err)
	}
	if pub, _ := listNews(true); len(pub) != 0 {
		t.Errorf("снятая с показа новость осталась на витрине: %#v", pub)
	}
	if err := deleteNews(hidden.ID); err != nil {
		t.Fatalf("удаление: %v", err)
	}
	if err := deleteNews(hidden.ID); err == nil {
		t.Error("повторное удаление должно вернуть ошибку")
	}
}

// resetSiteCache — в тестах база каждый раз новая, а кэш текстов общий.
func resetSiteCache() {
	siteMu.Lock()
	siteCache = nil
	siteMu.Unlock()
}
