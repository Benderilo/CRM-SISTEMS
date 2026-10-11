package main

import (
	"context"
	"net/http"
	"os"
	"testing"
)

// spamUpdate — сообщение в группе с заданным id.
func spamUpdate(userID int64, mid, text string) botUpdate {
	u := textUpdate(userID, fakeChat, text)
	u.Message.Body.MID = mid
	u.Message.Timestamp = nowMs()
	return u
}

// deletedCount — сколько сообщений бот попросил удалить.
func deletedCount(f *fakeMax) int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.deleted)
}

// setupSpam — группа с включённой защитой и подставной моделью.
func setupSpam(t *testing.T, answer string) *fakeMax {
	t.Helper()
	openTestDB(t)
	resetSiteCache()
	setCatchPhones(true)
	setAntiSpam(true)
	t.Cleanup(func() { setCatchPhones(false); setAntiSpam(false); setChatStaff(nil) })
	fakeAI(t, answer, http.StatusOK)
	return newFakeMax(t)
}

// Спам из группы удаляется, и заявки из него не заводится.
func TestSpamDeleted(t *testing.T) {
	f := setupSpam(t, `{"spam":true,"confidence":0.97,"why":"предлагает заработок"}`)
	api := newMaxAPI("test-token")

	handleBotUpdate(context.Background(), api, spamUpdate(600, "mid.spam",
		"Ищу людей в команду, доход от 100000 в месяц, пишите в личку"))

	f.mu.Lock()
	deleted := append([]string(nil), f.deleted...)
	f.mu.Unlock()
	if len(deleted) != 1 || deleted[0] != "mid.spam" {
		t.Fatalf("спам не удалён: %#v", deleted)
	}
	orders, err := listOrders(-1, "")
	if err != nil || len(orders) != 0 {
		t.Errorf("по спаму завелась заявка: %d (err %v)", len(orders), err)
	}
}

// Обычное сообщение не трогаем, даже когда защита включена.
func TestSpamKeepsNormalMessage(t *testing.T) {
	f := setupSpam(t, `{"spam":false,"confidence":0.98,"why":"обычный заказ"}`)
	api := newMaxAPI("test-token")

	handleBotUpdate(context.Background(), api, spamUpdate(601, "mid.ok",
		"привезите 2 кг пельменей, 8 900 123-45-67"))

	if n := deletedCount(f); n != 0 {
		t.Errorf("удалено обычных сообщений: %d", n)
	}
}

// Модель сомневается — не удаляем. Удалённое сообщение не вернуть,
// поэтому порог здесь выше, чем у разбора заказов.
func TestSpamNeedsConfidence(t *testing.T) {
	f := setupSpam(t, `{"spam":true,"confidence":0.6,"why":"возможно реклама"}`)
	api := newMaxAPI("test-token")

	handleBotUpdate(context.Background(), api, spamUpdate(602, "mid.maybe",
		"а у нас в соседнем селе тоже мясо продают"))

	if n := deletedCount(f); n != 0 {
		t.Errorf("удалено при слабой уверенности: %d", n)
	}
}

// Сообщения продавцов и администраторов группы не удаляем никогда:
// объявление «привезли свежую партию» выглядит как реклама, но это магазин.
func TestSpamNeverTouchesStaff(t *testing.T) {
	f := setupSpam(t, `{"spam":true,"confidence":0.99,"why":"реклама"}`)
	api := newMaxAPI("test-token")
	setChatStaff([]int64{603})

	handleBotUpdate(context.Background(), api, spamUpdate(603, "mid.staff",
		"Девочки, привезли свежую партию, налетайте, пишите в личку"))

	if n := deletedCount(f); n != 0 {
		t.Errorf("удалено сообщение продавца: %d", n)
	}
}

// В личной переписке ничего не удаляем: там бот один на один с покупателем.
func TestSpamOnlyInGroups(t *testing.T) {
	f := setupSpam(t, `{"spam":true,"confidence":0.99,"why":"заработок"}`)
	api := newMaxAPI("test-token")

	u := textUpdate(604, 0, "Ищу людей в команду, доход от 100000, пишите")
	u.Message.Body.MID = "mid.private"
	handleBotUpdate(context.Background(), api, u)

	if n := deletedCount(f); n != 0 {
		t.Errorf("удалено сообщение из лички: %d", n)
	}
}

// Защита выключена — не удаляем и к модели за этим не ходим.
func TestSpamOffBySwitch(t *testing.T) {
	f := setupSpam(t, `{"spam":true,"confidence":0.99,"why":"заработок"}`)
	api := newMaxAPI("test-token")
	setAntiSpam(false)

	handleBotUpdate(context.Background(), api, spamUpdate(605, "mid.off",
		"Ищу людей в команду, доход от 100000, пишите в личку"))

	if n := deletedCount(f); n != 0 {
		t.Errorf("удалено при выключенной защите: %d", n)
	}
}

// Модель недоступна — не удаляем ничего. Здесь «не знаю» значит «не трогай»:
// удалять чужие сообщения по совпадению слов нельзя.
func TestSpamSilentWithoutAI(t *testing.T) {
	openTestDB(t)
	resetSiteCache()
	setCatchPhones(true)
	setAntiSpam(true)
	t.Cleanup(func() { setCatchPhones(false); setAntiSpam(false) })
	fakeAI(t, "", http.StatusInternalServerError)
	f := newFakeMax(t)
	api := newMaxAPI("test-token")

	handleBotUpdate(context.Background(), api, spamUpdate(606, "mid.noai",
		"Ищу людей в команду, доход от 100000, пишите в личку"))

	if n := deletedCount(f); n != 0 {
		t.Errorf("удалено без ответа модели: %d", n)
	}
}

// Настройка сохраняется и по умолчанию выключена: удаление не отменить,
// включать должен человек.
func TestAntiSpamSettingDefaultsOff(t *testing.T) {
	openTestDB(t)

	if loadBotConfig().AntiSpam {
		t.Error("на чистой базе защита от спама включена — удаление должно быть по решению человека")
	}
	c := loadBotConfig()
	c.AntiSpam = true
	if err := saveBotConfig(c); err != nil {
		t.Fatalf("сохранение настроек: %v", err)
	}
	if !loadBotConfig().AntiSpam {
		t.Error("включённая защита не сохранилась")
	}
}

// Живая проверка настоящей модели на спаме — руками, при правке промпта:
//
//	SHOP_AI_KEY=… go test -run TestSpamLive -v
func TestSpamLive(t *testing.T) {
	if os.Getenv("SHOP_AI_KEY") == "" {
		t.Skip("нет SHOP_AI_KEY — живая модель не проверяется")
	}
	openTestDB(t)
	old := aiCfg
	setupAI()
	setAIEnabled(true)
	aiReset()
	t.Cleanup(func() { aiCfg = old; setAIEnabled(false); aiReset() })

	cases := []struct {
		text string
		spam bool
	}{
		{"Ищу людей в команду, доход от 100 000 в месяц, пишите в личку", true},
		{"Заработок на криптовалюте без вложений, переходи по ссылке t.me/xxx", true},
		{"Розыгрыш айфона! Ставь + в комментарии и подпишись", true},
		{"Требуются сотрудники на удалёнку, 5000 в день, писать в telegram", true},
		{"привезите 2 кг пельменей на Ленина 5, 8 900 123-45-67", false},
		{"Спасибо за блинчики, очень вкусные!", false},
		{"А почем свинина сейчас?", false},
		{"Девочки, привезли свежую партию тушёнки, налетайте", false},
		{"вот рецепт, который я делала с вашим фаршем https://eda.ru/recept/123", false},
		{"а кто-нибудь пробовал копчёного леща? стоит брать?", false},
	}
	for _, c := range cases {
		v, ok := askSpam(c.text)
		if !ok {
			t.Fatalf("модель не ответила на %q", c.text)
		}
		// Удаляем только при уверенности выше порога — её и проверяем.
		got := v.Spam && v.Confidence >= spamSure
		if got != c.spam {
			t.Errorf("%q: удалили бы=%v (спам=%v, уверенность %.2f, %s), ожидалось %v",
				c.text, got, v.Spam, v.Confidence, v.Why, c.spam)
			continue
		}
		t.Logf("%q → удалили бы=%v (%.2f) — %s", c.text, got, v.Confidence, v.Why)
	}
}
