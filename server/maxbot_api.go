package main

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"time"
)

// Настройка бота MAX из приложения: токен, включение, группа для новостей.
// Токен наружу не отдаём — только маску вида «••••3f9a»: увидеть его целиком
// нельзя, а заменить можно в любой момент.

type botJSON struct {
	Enabled     bool   `json:"enabled"`
	CallWords   string `json:"callWords"`
	CatchPhones bool   `json:"catchPhones"`
	// IgnorePhones — по номеру в строке, в человеческом виде: приложение
	// показывает их списком, который правят руками.
	IgnorePhones string `json:"ignorePhones"`
	// OrderWords — слова, по которым бот заводит заявку без телефона.
	// Работают всегда, когда разбор моделью выключен или недоступен.
	OrderWords string `json:"orderWords"`
	// AIEnabled — пользоваться ли разбором сообщений моделью.
	// AIReady — есть ли на сервере ключ к модели: без него переключатель
	// показывать бессмысленно, включать нечего.
	AIEnabled bool `json:"aiEnabled"`
	// AntiSpam — удалять ли спам в группе. Работает только вместе с
	// разбором ИИ: без модели решать, что спам, нечем.
	AntiSpam bool   `json:"antiSpam"`
	AIReady  bool   `json:"aiReady"`
	AIModel  string `json:"aiModel"`
	// AIURL — куда ходим за разбором, AIKeySet — задан ли ключ. Сам ключ
	// приложению не отдаём, как и токен бота.
	AIURL     string `json:"aiUrl"`
	AIKeySet  bool   `json:"aiKeySet"`
	HasToken  bool   `json:"hasToken"`
	TokenMask string `json:"tokenMask"`
	ChatID    int64  `json:"chatId"`
	ShopURL   string `json:"shopUrl"`
	Status    string `json:"status"`
	Name      string `json:"name"`
	Link      string `json:"link"`
	Running   bool   `json:"running"`
	// RestartDaily/RestartHour — ночной перезапуск опроса, StartedAt и
	// NextRestart — когда опрос поднялся и когда поднимется снова, мс.
	RestartDaily bool   `json:"restartDaily"`
	RestartHour  int    `json:"restartHour"`
	StartedAt    int64  `json:"startedAt"`
	LastRestart  int64  `json:"lastRestart"`
	RestartWhy   string `json:"restartReason"`
	NextRestart  int64  `json:"nextRestart"`
}

func currentBotJSON() botJSON {
	c := loadBotConfig()
	status, name, link, running := bot.state()
	started, last, why := bot.restartState()
	// Следующий перезапуск показываем, только пока он включён и опрос жив.
	var next int64
	if c.RestartDaily && running {
		next = nextRestartAt(time.Now().In(moscow), c.RestartHour).UnixMilli()
	}
	return botJSON{
		Enabled: c.Enabled, CallWords: strings.Join(currentCallWords(), ", "),
		CatchPhones: c.CatchPhones, IgnorePhones: prettyPhoneList(currentIgnorePhones()),
		OrderWords: strings.Join(currentOrderWords(), ", "),
		AIEnabled:  c.AIEnabled, AIReady: aiConfigured(), AIModel: aiConf().Model,
		AIURL: aiConf().URL, AIKeySet: aiConf().Key != "",
		AntiSpam: c.AntiSpam,
		HasToken: c.Token != "", TokenMask: maskToken(c.Token),
		ChatID: c.ChatID, ShopURL: c.ShopURL,
		Status: status, Name: name, Link: link, Running: running,
		RestartDaily: c.RestartDaily, RestartHour: normalizeRestartHour(c.RestartHour),
		StartedAt: msOrZero(started), LastRestart: msOrZero(last), RestartWhy: why,
		NextRestart: next,
	}
}

// msOrZero — время в миллисекундах или 0, если его нет: приложению проще
// проверить ноль, чем разбирать пустую строку.
func msOrZero(t time.Time) int64 {
	if t.IsZero() {
		return 0
	}
	return t.UnixMilli()
}

func apiCrmBot(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "bot": currentBotJSON()})
}

// apiCrmSaveBot сохраняет настройки и перезапускает опрос.
// Пустой token означает «оставить прежний» — приложение его не знает.
func apiCrmSaveBot(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Token   *string `json:"token"`
		Enabled bool    `json:"enabled"`
		ChatID  int64   `json:"chatId"`
		ShopURL string  `json:"shopUrl"`
		// CatchPhones — указателем: приложения, которые про перехват не знают,
		// поля не пришлют, и выключать его из-за этого нельзя.
		CatchPhones *bool `json:"catchPhones"`
		// IgnorePhones тоже указателем: пустая строка — это «список очистить»,
		// и отличить её от «поля не прислали» иначе нельзя.
		IgnorePhones *string `json:"ignorePhones"`
		// OrderWords — тоже указателем: пустая строка вернёт список
		// к словам по умолчанию, а «поля не прислали» ничего не меняет.
		OrderWords *string `json:"orderWords"`
		// AIEnabled — тоже указателем: приложение постарее про разбор
		// моделью не знает и поля не шлёт, выключать его из-за этого нельзя.
		AIEnabled *bool `json:"aiEnabled"`
		// AntiSpam — указателем по той же причине: приложение, которое про
		// защиту от спама не знает, поля не шлёт, и выключать её нельзя.
		AntiSpam  *bool  `json:"antiSpam"`
		CallWords string `json:"callWords"`
		// RestartDaily и RestartHour — указателями по той же причине:
		// приложение постарее про ночной перезапуск не знает и полей не шлёт,
		// выключать его из-за этого нельзя.
		RestartDaily *bool `json:"restartDaily"`
		RestartHour  *int  `json:"restartHour"`
		// AIKey/AIURL/AIModel — настройки модели. nil — не трогать; пустая
		// строка — стереть и вернуться к crmshop.env или значению по умолчанию.
		AIKey   *string `json:"aiKey"`
		AIURL   *string `json:"aiUrl"`
		AIModel *string `json:"aiModel"`
	}
	if !readJSON(w, r, &in) {
		return
	}

	c := loadBotConfig()
	if in.Token != nil && strings.TrimSpace(*in.Token) != "" {
		c.Token = strings.TrimSpace(*in.Token)
	}
	c.Enabled = in.Enabled
	c.ChatID = in.ChatID
	if u := strings.TrimSpace(in.ShopURL); u != "" {
		c.ShopURL = u
	}
	c.CallWords = strings.TrimSpace(in.CallWords)
	if in.CatchPhones != nil {
		c.CatchPhones = *in.CatchPhones
	}
	if in.OrderWords != nil {
		c.OrderWords = strings.TrimSpace(*in.OrderWords)
	}
	if in.AIEnabled != nil {
		c.AIEnabled = *in.AIEnabled
	}
	if in.AntiSpam != nil {
		c.AntiSpam = *in.AntiSpam
	}
	if in.RestartDaily != nil {
		c.RestartDaily = *in.RestartDaily
	}
	if in.RestartHour != nil {
		c.RestartHour = normalizeRestartHour(*in.RestartHour)
	}
	if in.IgnorePhones != nil {
		// Складываем уже приведёнными к +7XXXXXXXXXX: сравнивать с телефоном
		// из сообщения нужно одинаково записанные номера.
		c.IgnorePhones = strings.Join(parsePhoneList(*in.IgnorePhones), ",")
	}
	if c.Enabled && c.Token == "" {
		apiErr(w, http.StatusBadRequest, "Сначала впишите токен бота")
		return
	}
	if err := saveBotConfig(c); err != nil {
		apiErr(w, http.StatusInternalServerError, "Не удалось сохранить настройки бота")
		return
	}
	if in.AIKey != nil || in.AIURL != nil || in.AIModel != nil {
		for _, kv := range []struct {
			key      string
			v        *string
			fallback string
		}{
			{keyAIKey, in.AIKey, envOr("SHOP_AI_KEY", "")},
			{keyAIURL, in.AIURL, envOr("SHOP_AI_URL", aiDefaultURL)},
			{keyAIModel, in.AIModel, envOr("SHOP_AI_MODEL", aiDefaultModel)},
		} {
			if kv.v == nil {
				continue
			}
			v := strings.TrimSpace(*kv.v)
			// Приложение присылает модель и адрес всегда, даже нетронутые.
			// Значение «как без базы» не храним: иначе оно намертво перекроет
			// crmshop.env и смену значения по умолчанию в новой версии.
			if v == kv.fallback {
				v = ""
			}
			setSetting(kv.key, v)
		}
		setupAI()
	}

	bot.apply()
	// Даём опросу мгновение представиться, чтобы приложение сразу показало имя бота.
	time.Sleep(700 * time.Millisecond)
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "bot": currentBotJSON()})
}

// apiCrmBotRestart поднимает опрос заново — кнопка «Перезапустить бота».
// То же самое, что делает ночной сторож, только руками и сейчас.
func apiCrmBotRestart(w http.ResponseWriter, r *http.Request) {
	c := loadBotConfig()
	if !c.Enabled || c.Token == "" {
		apiErr(w, http.StatusBadRequest, "Бот выключен — перезапускать нечего")
		return
	}
	bot.restart("вручную из приложения")
	// Даём опросу мгновение представиться, чтобы приложение сразу показало имя бота.
	time.Sleep(700 * time.Millisecond)
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "bot": currentBotJSON()})
}

// apiCrmBotCheck спрашивает у MAX, чей это токен, — кнопка «Проверить связь».
func apiCrmBotCheck(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Token string `json:"token"`
	}
	// Тело необязательно: без него проверяем уже сохранённый токен.
	if r.ContentLength > 0 && !readJSON(w, r, &in) {
		return
	}
	token := strings.TrimSpace(in.Token)
	if token == "" {
		token = loadBotConfig().Token
	}
	if token == "" {
		apiErr(w, http.StatusBadRequest, "Токен не задан")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 20*time.Second)
	defer cancel()
	info, err := newMaxAPI(token).me(ctx)
	if err != nil {
		if errors.Is(err, errBotToken) {
			apiErr(w, http.StatusUnauthorized, "MAX не принял этот токен")
			return
		}
		apiErr(w, http.StatusBadGateway, "Нет связи с MAX: "+err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok": true, "name": info.Name, "username": info.Username, "title": botTitle(info),
	})
}

// apiCrmBotChats — список чатов, где состоит бот: в приложении из него
// выбирают группу для новостей, чтобы не искать её номер вручную.
func apiCrmBotChats(w http.ResponseWriter, r *http.Request) {
	c := loadBotConfig()
	if c.Token == "" {
		apiErr(w, http.StatusBadRequest, "Сначала впишите токен бота")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 20*time.Second)
	defer cancel()

	chats, err := newMaxAPI(c.Token).chats(ctx)
	if err != nil {
		apiErr(w, http.StatusBadGateway, "Не удалось получить список чатов: "+err.Error())
		return
	}
	out := make([]map[string]any, 0, len(chats))
	for _, ch := range chats {
		if ch.Type == "dialog" {
			continue // личные переписки в списке групп ни к чему
		}
		title := ch.Title
		if title == "" {
			title = "Группа " + i64(ch.ChatID)
		}
		out = append(out, map[string]any{"chatId": ch.ChatID, "title": title, "type": ch.Type})
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "chats": out})
}

// apiCrmBotMenu присылает в группу карточку с кнопками — её закрепляют,
// и дальше покупателям не нужно вспоминать слова.
func apiCrmBotMenu(w http.ResponseWriter, r *http.Request) {
	c := loadBotConfig()
	if c.Token == "" {
		apiErr(w, http.StatusBadRequest, "Сначала впишите токен бота")
		return
	}
	if c.ChatID == 0 {
		apiErr(w, http.StatusBadRequest, "Не выбрана группа")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 20*time.Second)
	defer cancel()
	if err := sendGroupMenu(ctx, newMaxAPI(c.Token), c.ChatID); err != nil {
		apiErr(w, http.StatusBadGateway, "Не удалось отправить меню: "+err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

// apiCrmBotAnnounce отправляет новость в группу MAX — кнопка у новости
// в приложении. Под сообщением идут кнопки «Каталог» и «Открыть магазин».
func apiCrmBotAnnounce(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Text   string `json:"text"`
		NewsID int64  `json:"newsId"`
	}
	if !readJSON(w, r, &in) {
		return
	}
	text := strings.TrimSpace(in.Text)
	if text == "" && in.NewsID > 0 {
		news, err := listNews(false)
		if err == nil {
			for _, n := range news {
				if n.ID == in.NewsID {
					text = n.Text
					break
				}
			}
		}
	}
	if text == "" {
		apiErr(w, http.StatusBadRequest, "Пустая новость")
		return
	}

	c := loadBotConfig()
	if c.Token == "" {
		apiErr(w, http.StatusBadRequest, "Сначала впишите токен бота")
		return
	}
	if c.ChatID == 0 {
		apiErr(w, http.StatusBadRequest, "Не выбрана группа для новостей")
		return
	}

	rows := [][]botButton{{callbackButton("🛍 Каталог", "catalog")}}
	var links []botButton
	if link := botLink(); link != "" {
		links = append(links, linkButton("Написать боту лично", link))
	}
	if u := strings.TrimRight(c.ShopURL, "/"); u != "" {
		links = append(links, linkButton("Открыть магазин", u))
	}
	if len(links) > 0 {
		rows = append(rows, links)
	}

	ctx, cancel := context.WithTimeout(r.Context(), 20*time.Second)
	defer cancel()
	if err := newMaxAPI(c.Token).send(ctx, sendTo{ChatID: c.ChatID}, text, rows); err != nil {
		apiErr(w, http.StatusBadGateway, "Не удалось отправить в группу: "+err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}
