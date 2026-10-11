package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Бот мессенджера MAX: витрина и приём заказов прямо в переписке.
//
// Работаем длинным опросом — сервис сам ходит на botapi.max.ru за обновлениями
// (GET /updates?marker=...), поэтому ни webhook, ни внешний адрес не нужны:
// достаточно исходящего интернета. Токен лежит в таблице settings и меняется
// из приложения, а опрос перезапускается на лету — так одного и того же бота
// можно перенастроить на другой магазин.

// botAPIBase вынесен в переменную, чтобы тесты подставляли свой сервер.
var botAPIBase = "https://botapi.max.ru"

// botConfig — настройки бота, живут рядом с текстами витрины.
type botConfig struct {
	Token   string
	Enabled bool
	ChatID  int64  // группа, куда уходят новости
	ShopURL string // адрес витрины для кнопки «Открыть магазин»
	// CallWords — на какие слова бот откликается в группе: «бот», «маруся»…
	// Задаются из приложения через запятую, чтобы одного и того же бота
	// можно было назвать по-своему в разных магазинах.
	CallWords string
	// CatchPhones — ловить ли в переписке номера телефонов и заводить по ним
	// заявки (см. maxbot_catch.go). Включено, пока не выключили: заявка,
	// написанная одной строкой, иначе просто пропадает.
	CatchPhones bool
	// IgnorePhones — номера, которые перехват пропускает: свои же телефоны
	// продавцов и админов группы. Хранятся приведёнными к +7XXXXXXXXXX
	// через запятую, список задаётся из приложения.
	IgnorePhones string
	// OrderWords — слова, по которым бот узнаёт заказ, если телефона в
	// сообщении нет: «заказ», «привезите», «можно». Тоже из приложения.
	OrderWords string
	// AIEnabled — разбирать ли сообщения моделью (см. maxbot_ai.go). Ключ
	// к модели хранится отдельно (ai.key) и в приложение не отдаётся: здесь
	// только «пользоваться или нет». Выключишь — бот работает по словам.
	AIEnabled bool
	// AntiSpam — удалять ли в группе спам про заработок и ссылки
	// (см. maxbot_spam.go). По умолчанию выключено: удаление не отменить,
	// поэтому включать должен человек осознанно.
	AntiSpam bool
	// RestartDaily — перезапускать ли опрос раз в сутки (см. maxbot_restart.go).
	// Включено, пока не выключили: MAX иногда молча перестаёт отдавать
	// обновления, и ночной перезапуск возвращает бота в строй сам.
	RestartDaily bool
	// RestartHour — час ночного перезапуска по Москве, 0–23.
	RestartHour int
}

const (
	keyBotToken   = "bot.token"
	keyBotEnabled = "bot.enabled"
	keyBotChat    = "bot.chatId"
	keyBotShopURL = "bot.shopUrl"
	keyBotCall    = "bot.callWords"
	keyBotCatch   = "bot.catchPhones"
	keyBotIgnore  = "bot.ignorePhones"
	keyBotOrder   = "bot.orderWords"
	keyBotRestart = "bot.restartDaily"
	keyBotHour    = "bot.restartHour"
	keyBotAI      = "bot.aiEnabled"
	keyBotSpam    = "bot.antiSpam"
)

func loadBotConfig() botConfig {
	// Перехват включён по умолчанию: на серверах, где настройки бота
	// сохранили до его появления, строки с ключом просто нет.
	// ИИ тоже включён по умолчанию: ключ на сервере задают осознанно, и
	// задают именно для того, чтобы он работал.
	c := botConfig{CatchPhones: true, RestartDaily: true,
		RestartHour: defaultRestartHour, AIEnabled: true}
	rows, err := db.Query(`SELECT key, value FROM settings WHERE key LIKE 'bot.%'`)
	if err != nil {
		logErr("настройки бота", err)
		return c
	}
	defer rows.Close()
	for rows.Next() {
		var k, v string
		if err := rows.Scan(&k, &v); err != nil {
			continue
		}
		switch k {
		case keyBotToken:
			c.Token = strings.TrimSpace(v)
		case keyBotEnabled:
			c.Enabled = v == "1"
		case keyBotChat:
			c.ChatID, _ = strconv.ParseInt(v, 10, 64)
		case keyBotShopURL:
			c.ShopURL = strings.TrimSpace(v)
		case keyBotCall:
			c.CallWords = strings.TrimSpace(v)
		case keyBotCatch:
			c.CatchPhones = v != "0"
		case keyBotAI:
			c.AIEnabled = v != "0"
		case keyBotSpam:
			c.AntiSpam = v == "1"
		case keyBotIgnore:
			c.IgnorePhones = strings.TrimSpace(v)
		case keyBotOrder:
			c.OrderWords = strings.TrimSpace(v)
		case keyBotRestart:
			c.RestartDaily = v != "0"
		case keyBotHour:
			h, err := strconv.Atoi(strings.TrimSpace(v))
			if err == nil {
				c.RestartHour = normalizeRestartHour(h)
			}
		}
	}
	return c
}

func saveBotConfig(c botConfig) error {
	enabled := "0"
	if c.Enabled {
		enabled = "1"
	}
	catch := "0"
	if c.CatchPhones {
		catch = "1"
	}
	restart := "0"
	if c.RestartDaily {
		restart = "1"
	}
	useAI := "0"
	if c.AIEnabled {
		useAI = "1"
	}
	spam := "0"
	if c.AntiSpam {
		spam = "1"
	}
	pairs := [][2]string{
		{keyBotToken, strings.TrimSpace(c.Token)},
		{keyBotEnabled, enabled},
		{keyBotChat, strconv.FormatInt(c.ChatID, 10)},
		{keyBotShopURL, strings.TrimSpace(c.ShopURL)},
		{keyBotCall, strings.TrimSpace(c.CallWords)},
		{keyBotCatch, catch},
		{keyBotIgnore, strings.TrimSpace(c.IgnorePhones)},
		{keyBotOrder, strings.TrimSpace(c.OrderWords)},
		{keyBotRestart, restart},
		{keyBotHour, strconv.Itoa(normalizeRestartHour(c.RestartHour))},
		{keyBotAI, useAI},
		{keyBotSpam, spam},
	}
	tx, err := db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	for _, p := range pairs {
		if _, err := tx.Exec(`INSERT INTO settings (key, value, updated_at) VALUES (?,?,?)
          ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at`,
			p[0], p[1], nowMs()); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// maskToken — что показать в приложении вместо самого токена.
func maskToken(token string) string {
	t := strings.TrimSpace(token)
	if t == "" {
		return ""
	}
	r := []rune(t)
	if len(r) <= 4 {
		return "••••"
	}
	return "••••" + string(r[len(r)-4:])
}

// ------------------------------------------------------------ транспорт

type maxAPI struct {
	token  string
	client *http.Client
}

func newMaxAPI(token string) *maxAPI {
	return &maxAPI{token: token, client: &http.Client{Timeout: 65 * time.Second}}
}

var errBotToken = errors.New("MAX не принял токен")

// call — общий запрос к API.
//
// Токен уходит заголовком Authorization: параметр access_token в адресе MAX
// объявил устаревшим и отвечает на него 401 «use Authorization header».
func (m *maxAPI) call(ctx context.Context, method, path string, q url.Values,
	body any, out any) error {
	if q == nil {
		q = url.Values{}
	}

	var reader io.Reader
	if body != nil {
		data, err := json.Marshal(body)
		if err != nil {
			return err
		}
		reader = bytes.NewReader(data)
	}
	req, err := http.NewRequestWithContext(ctx, method, botAPIBase+path+"?"+q.Encode(), reader)
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", m.token)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := m.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	data, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil {
		return err
	}
	if resp.StatusCode == http.StatusUnauthorized {
		return errBotToken
	}
	if resp.StatusCode >= 400 {
		return fmt.Errorf("MAX ответил %d: %s", resp.StatusCode, strings.TrimSpace(string(data)))
	}
	if out == nil {
		return nil
	}
	return json.Unmarshal(data, out)
}

// botInfo — карточка самого бота, по ней проверяем токен.
type botInfo struct {
	UserID    int64  `json:"user_id"`
	Name      string `json:"name"`
	FirstName string `json:"first_name"`
	Username  string `json:"username"`
}

// title — как называть бота в приложении: у части ботов заполнено только
// first_name, у части — name.
func (b botInfo) title() string {
	if strings.TrimSpace(b.Name) != "" {
		return b.Name
	}
	return b.FirstName
}

func (m *maxAPI) me(ctx context.Context) (botInfo, error) {
	var b botInfo
	err := m.call(ctx, http.MethodGet, "/me", nil, nil, &b)
	return b, err
}

// botChat — чат, где состоит бот: из этого списка в приложении выбирают группу.
type botChat struct {
	ChatID int64  `json:"chat_id"`
	Type   string `json:"type"`
	Title  string `json:"title"`
}

func (m *maxAPI) chats(ctx context.Context) ([]botChat, error) {
	var out struct {
		Chats []botChat `json:"chats"`
	}
	err := m.call(ctx, http.MethodGet, "/chats", url.Values{"count": {"100"}}, nil, &out)
	return out.Chats, err
}

// botButton — кнопка под сообщением: callback возвращается событием,
// link просто открывает адрес (сайт магазина или личный чат с ботом).
type botButton struct {
	Type string `json:"type"`
	Text string `json:"text"`
	// Intent — цвет кнопки. MAX знает три: default (обычная),
	// positive (зелёная) и negative (красная); своих цветов нет.
	Intent  string `json:"intent,omitempty"`
	Payload string `json:"payload,omitempty"`
	URL     string `json:"url,omitempty"`
}

func callbackButton(text, payload string) botButton {
	return botButton{Type: "callback", Text: text, Payload: payload}
}

// positiveButton — зелёная: главное действие, ведущее к заказу.
func positiveButton(text, payload string) botButton {
	return botButton{Type: "callback", Text: text, Payload: payload, Intent: "positive"}
}

// negativeButton — красная: отмена и очистка.
func negativeButton(text, payload string) botButton {
	return botButton{Type: "callback", Text: text, Payload: payload, Intent: "negative"}
}

func linkButton(text, link string) botButton {
	return botButton{Type: "link", Text: text, URL: link}
}

// contactButton — «Отправить мой номер»: мессенджер сам пришлёт телефон,
// покупателю не нужно набирать его руками.
func contactButton(text string) botButton {
	return botButton{Type: "request_contact", Text: text}
}

// sendTo — кому шлём: в личку (UserID) или в чат (ChatID).
type sendTo struct {
	UserID int64
	ChatID int64
}

// sendPhoto — то же сообщение, но с картинкой над текстом: покупателю сразу
// видно, что он берёт. Картинка отдаётся ссылкой на сайт магазина.
func (m *maxAPI) sendPhoto(ctx context.Context, to sendTo, text, photo string,
	keyboard [][]botButton) error {
	return m.sendMessage(ctx, to, text, photo, keyboard)
}

func (m *maxAPI) send(ctx context.Context, to sendTo, text string, keyboard [][]botButton) error {
	return m.sendMessage(ctx, to, text, "", keyboard)
}

func (m *maxAPI) sendMessage(ctx context.Context, to sendTo, text, photo string,
	keyboard [][]botButton) error {
	q := url.Values{}
	switch {
	case to.ChatID != 0:
		q.Set("chat_id", strconv.FormatInt(to.ChatID, 10))
	case to.UserID != 0:
		q.Set("user_id", strconv.FormatInt(to.UserID, 10))
	default:
		return errors.New("некому отправлять")
	}

	body := map[string]any{"text": text, "notify": true}
	attachments := []map[string]any{}
	if photo != "" {
		attachments = append(attachments, map[string]any{
			"type": "image", "payload": map[string]any{"url": photo},
		})
	}
	if len(keyboard) > 0 {
		attachments = append(attachments, map[string]any{
			"type":    "inline_keyboard",
			"payload": map[string]any{"buttons": keyboard},
		})
	}
	if len(attachments) > 0 {
		body["attachments"] = attachments
	}
	return m.call(ctx, http.MethodPost, "/messages", q, body, nil)
}

// answer показывает всплывашку над нажатой кнопкой.
//
// Пустой ответ MAX не принимает («message or notification required»), а
// показывать «Готово» на каждое нажатие незачем: следом всё равно уходит
// новое сообщение. Поэтому без текста запрос просто не отправляем.
func (m *maxAPI) answer(ctx context.Context, callbackID, notification string) error {
	if strings.TrimSpace(notification) == "" {
		return nil
	}
	return m.call(ctx, http.MethodPost, "/answers",
		url.Values{"callback_id": {callbackID}},
		map[string]any{"notification": notification}, nil)
}

// ------------------------------------------------------------- обновления

type botUser struct {
	UserID   int64  `json:"user_id"`
	Name     string `json:"name"`
	Username string `json:"username"`
}

// botAttachment — вложение входящего сообщения. Нас интересует контакт:
// им приходит телефон, когда покупатель нажал «Отправить мой номер».
type botAttachment struct {
	Type    string `json:"type"`
	Payload struct {
		VcfInfo string `json:"vcf_info"`
	} `json:"payload"`
}

// msgLink — сообщение, приложенное к этому: пересланное («forward») или
// процитированное в ответе («reply»). У пересланного sender — настоящий автор,
// и текст лежит именно здесь: в самом сообщении он пустой.
type msgLink struct {
	Type    string  `json:"type"`
	Sender  botUser `json:"sender"`
	Message struct {
		MID  string `json:"mid"`
		Text string `json:"text"`
	} `json:"message"`
}

// botMessage — входящее сообщение.
type botMessage struct {
	Sender    botUser `json:"sender"`
	Recipient struct {
		ChatID   int64  `json:"chat_id"`
		UserID   int64  `json:"user_id"`
		ChatType string `json:"chat_type"`
	} `json:"recipient"`
	// Timestamp — когда написано, мс. По нему догон истории отбирает свежее,
	// а перехват ставит в комментарий заказа время самого сообщения.
	Timestamp int64 `json:"timestamp"`
	Body      struct {
		// MID — id сообщения: по нему одно и то же не разбираем дважды.
		MID         string          `json:"mid"`
		Text        string          `json:"text"`
		Attachments []botAttachment `json:"attachments"`
	} `json:"body"`
	Link *msgLink `json:"link"`
}

// botCallback — нажатие кнопки под сообщением.
type botCallback struct {
	CallbackID string  `json:"callback_id"`
	Payload    string  `json:"payload"`
	User       botUser `json:"user"`
}

type botUpdate struct {
	Type     string       `json:"update_type"`
	Message  *botMessage  `json:"message"`
	Callback *botCallback `json:"callback"`
	// bot_started, bot_added и прочие события без сообщения
	User   *botUser `json:"user"`
	ChatID int64    `json:"chat_id"`
	UserID int64    `json:"user_id"`
}

type updatesResponse struct {
	Updates []botUpdate `json:"updates"`
	Marker  *int64      `json:"marker"`
}

func (m *maxAPI) updates(ctx context.Context, marker *int64, timeout int) (updatesResponse, error) {
	q := url.Values{"limit": {"100"}, "timeout": {strconv.Itoa(timeout)}}
	if marker != nil {
		q.Set("marker", strconv.FormatInt(*marker, 10))
	}
	var out updatesResponse
	err := m.call(ctx, http.MethodGet, "/updates", q, nil, &out)
	return out, err
}

// ------------------------------------------------------------- запуск

// botRunner держит один опрос на весь сервис. При смене настроек старый
// опрос отменяется, новый стартует с чистым маркером.
type botRunner struct {
	mu      sync.Mutex
	cancel  context.CancelFunc
	running bool

	status   string // строка состояния для приложения
	name     string // имя бота, если токен принят
	username string // @username — по нему в группе узнают обращение
	link     string // ссылка на личный чат с ботом
	selfID   int64  // свой user_id: по нему узнаём собственные сообщения

	// startedAt — когда стартовал нынешний опрос. По нему сторож понимает,
	// что бот живёт со вчерашнего дня и пора его перезапустить.
	startedAt time.Time
	// lastRestart — когда перезапуск случился в последний раз, и почему:
	// это видно в приложении, чтобы не гадать, сам он ожил или нет.
	lastRestart   time.Time
	restartReason string
}

var bot = &botRunner{status: "Бот выключен"}

func (r *botRunner) state() (status, name, link string, running bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.status, r.name, r.link, r.running
}

func (r *botRunner) setStatus(status string) {
	r.mu.Lock()
	r.status = status
	r.mu.Unlock()
}

// apply перечитывает настройки и приводит опрос в нужное состояние.
func (r *botRunner) apply() {
	c := loadBotConfig()
	publicURL = c.ShopURL
	setCallWords(c.CallWords)
	setCatchPhones(c.CatchPhones)
	setIgnorePhones(c.IgnorePhones)
	setOrderWords(c.OrderWords)
	setAIEnabled(c.AIEnabled)
	setAntiSpam(c.AntiSpam)

	r.mu.Lock()
	if r.cancel != nil {
		r.cancel()
		r.cancel = nil
	}
	r.running = false
	r.name, r.username, r.link = "", "", ""
	if !c.Enabled || c.Token == "" {
		r.startedAt = time.Time{}
		r.status = "Бот выключен"
		r.mu.Unlock()
		return
	}
	ctx, cancel := context.WithCancel(context.Background())
	r.cancel = cancel
	r.running = true
	r.startedAt = time.Now()
	r.status = "Подключаюсь к MAX…"
	r.mu.Unlock()

	go r.loop(ctx, c.Token)
}

func (r *botRunner) loop(ctx context.Context, token string) {
	api := newMaxAPI(token)

	var marker *int64
	fails := 0
	var lastMe time.Time
	var lastCatchUp time.Time

	for {
		if ctx.Err() != nil {
			return
		}

		// Знакомство с ботом повторяем, пока не выйдет, и обновляем раз в
		// десять минут: от /me зависят имя, @username и ссылка на личный чат,
		// а без них в группе пропадает кнопка «Каталог и заказ». Раньше один
		// таймаут при запуске оставлял бота без них до перезапуска.
		if !r.hasIdentity() || time.Since(lastMe) > 10*time.Minute {
			meCtx, cancel := context.WithTimeout(ctx, 15*time.Second)
			info, err := api.me(meCtx)
			cancel()
			switch {
			case err == nil:
				r.setIdentity(info)
				lastMe = time.Now()
			case errors.Is(err, errBotToken):
				r.setStatus("MAX не принял токен — проверьте его в приложении")
				log.Printf("бот MAX: %v", err)
				return
			default:
				log.Printf("бот MAX: %v", err)
			}
		}

		// Пока не было связи, сообщения могли пройти мимо опроса — сразу при
		// запуске и дальше раз в десять минут перечитываем историю чатов.
		// Заодно обновляем список продавцов: их сообщения заявками не станут.
		if time.Since(lastCatchUp) > catchUpEvery {
			lastCatchUp = time.Now()
			refreshStaff(ctx, api, loadBotConfig().ChatID)
			catchUpHistory(ctx, api)
		}

		// Длинный опрос: сервер MAX держит ответ до 30 секунд, поэтому ждём
		// чуть дольше и не даём запросу висеть вечно.
		pollCtx, cancel := context.WithTimeout(ctx, 60*time.Second)
		res, err := api.updates(pollCtx, marker, 30)
		cancel()

		if err != nil {
			if ctx.Err() != nil {
				return
			}
			if errors.Is(err, errBotToken) {
				r.setStatus("MAX не принял токен — проверьте его в приложении")
				return
			}
			fails++
			r.setStatus("Нет связи с MAX: " + err.Error())
			// Пауза растёт до минуты, чтобы не долбить чужой сервер при аварии.
			wait := time.Duration(minInt(fails, 12)) * 5 * time.Second
			select {
			case <-ctx.Done():
				return
			case <-time.After(wait):
			}
			continue
		}

		// Связь вернулась — в приложении это должно быть видно.
		if fails > 0 {
			log.Printf("бот MAX: связь восстановлена")
		}
		fails = 0
		r.setOnline()

		if res.Marker != nil {
			marker = res.Marker
		}
		for _, u := range res.Updates {
			handleBotUpdate(ctx, api, u)
		}
	}
}

// hasIdentity — знаем ли мы, как зовут бота и куда ведёт личный чат.
func (r *botRunner) hasIdentity() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.name != "" && r.link != ""
}

func (r *botRunner) setIdentity(info botInfo) {
	r.mu.Lock()
	r.selfID = info.UserID
	r.name = strings.TrimSpace(info.title())
	r.username = strings.ToLower(strings.TrimSpace(info.Username))
	if info.Username != "" {
		r.link = "https://max.ru/" + info.Username
	}
	r.status = "На связи: " + botTitle(info)
	r.mu.Unlock()
	log.Printf("бот MAX: %s на связи", botTitle(info))
}

// setOnline возвращает бодрый статус после обрыва связи.
func (r *botRunner) setOnline() {
	r.mu.Lock()
	if r.name != "" {
		r.status = "На связи: " + r.name
		if r.username != "" {
			r.status += " (@" + r.username + ")"
		}
	} else {
		r.status = "На связи"
	}
	r.mu.Unlock()
}

func botTitle(info botInfo) string {
	if info.Username != "" {
		return info.title() + " (@" + info.Username + ")"
	}
	return info.title()
}

// botLink — ссылка на личный чат с ботом, если она известна.
func botLink() string {
	_, _, link, _ := bot.state()
	return link
}

// botUsername — @имя бота: по нему в группе распознаём обращение.
func botUsername() string {
	bot.mu.Lock()
	defer bot.mu.Unlock()
	return bot.username
}

// botSelfID — user_id самого бота. Нужен перехвату: собственное сообщение
// с номером магазина не должно превратиться в заявку.
func botSelfID() int64 {
	bot.mu.Lock()
	defer bot.mu.Unlock()
	return bot.selfID
}

func minInt(a, b int) int {
	if a < b {
		return a
	}
	return b
}
