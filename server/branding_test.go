package main

import (
	"net/http/httptest"
	"strings"
	"testing"
)

// Название магазина задаётся из приложения и попадает на все страницы:
// в заголовок, шапку, подвал и значок. Ни слова от прежнего магазина.
func TestShopTitleFromSettings(t *testing.T) {
	openTestDB(t)
	resetSiteCache()
	loadTemplatesForTest(t)

	s := siteDefaults
	s.Title = "Цветы у Марины"
	s.Tagline = "Букеты с доставкой за 2 часа"
	s.Hero = "Собираем букеты к любому поводу."
	if err := saveSiteText(s); err != nil {
		t.Fatalf("тексты сайта: %v", err)
	}

	w := httptest.NewRecorder()
	handleHome(w, httptest.NewRequest("GET", "/", nil))
	body := w.Body.String()
	for _, want := range []string{"<title>Цветы у Марины</title>", s.Tagline, s.Hero, `logo-mark">Ц<`} {
		if !strings.Contains(body, want) {
			t.Errorf("на главной нет %q", want)
		}
	}
	for _, old := range []string{"мясн", "Мясн", "Домашняя"} {
		if strings.Contains(body, old) {
			t.Errorf("на главной остался текст прежнего магазина: %q", old)
		}
	}
	if got := welcomeText(); !strings.HasPrefix(got, "Цветы у Марины.") {
		t.Errorf("бот здоровается не от имени магазина: %q", got)
	}
}

// Ключ ИИ, заданный из приложения, важнее env-файла и применяется сразу.
func TestAIConfigFromSettings(t *testing.T) {
	openTestDB(t)
	old := aiConf()
	t.Cleanup(func() {
		aiCfgMu.Lock()
		aiCfg = old
		aiCfgMu.Unlock()
	})
	t.Setenv("SHOP_AI_KEY", "")

	setupAI()
	if aiConfigured() {
		t.Fatalf("без ключа ИИ не должен считаться настроенным")
	}

	setSetting(keyAIKey, "ключ-из-приложения")
	setSetting(keyAIModel, "gpt-oss:20b")
	setupAI()
	c := aiConf()
	if c.Key != "ключ-из-приложения" || c.Model != "gpt-oss:20b" || c.URL != aiDefaultURL {
		t.Errorf("настройки ИИ из базы не применились: %+v", c)
	}
	if !aiConfigured() {
		t.Errorf("с ключом ИИ должен считаться настроенным")
	}
}
