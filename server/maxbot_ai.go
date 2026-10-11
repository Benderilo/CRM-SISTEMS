package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// Разбор сообщений моделью: бот читает как человек, а не по списку слов.
//
// Раньше заявка заводилась по двум приметам: в сообщении нашёлся телефон
// или слово из списка «заказ, привезите, хочу…» (см. maxbot_catch.go).
// Список нельзя сделать одновременно широким и узким: с широким заявкой
// становилось «спасибо за блинчики, ещё закажем», с узким терялось
// «на неделе бы пару килограмм фарша и десяток котлет».
//
// Теперь сообщение сначала читает модель и говорит: заказ это или нет, как
// зовут человека, какой адрес, что именно просят. Пока модель отвечает —
// решает она. Как только она молчит (нет сети, кончились токены, упал
// сервис) — работают прежние правила по словам, ровно как до неё. Поэтому
// перехват не может сломаться из-за модели: худшее, что бывает, — он
// становится таким, каким был раньше.
//
// Ключ, адрес и модель задаются из приложения (экран «Бот MAX», раздел ИИ)
// и лежат в базе (ai.key, ai.url, ai.model). Если там пусто — берутся из
// crmshop.env:
//
//	SHOP_AI_KEY   — ключ Ollama (без него облако не отвечает)
//	SHOP_AI_URL   — свой адрес, если Ollama стоит рядом
//	SHOP_AI_MODEL — другая модель
//
// Сам ключ приложению обратно не отдаётся — только отметка «задан».
// Пользоваться ли разбором, решает продавец переключателем на том же экране.

const (
	// aiDefaultURL — облако Ollama: на сервере магазина модель держать
	// негде, 120B в память VPS не поместится.
	aiDefaultURL   = "https://ollama.com/api/chat"
	aiDefaultModel = "gpt-oss:120b"

	// aiTimeout — сколько ждём ответа. Разбор занимает 1–3 секунды, но
	// сообщения разбираются по очереди в опросе бота, поэтому ждать долго
	// нельзя: всё это время бот не отвечает остальным.
	aiTimeout = 20 * time.Second

	// aiFailsToPause, aiCooldown — когда сервис лёг, перестаём ходить к нему
	// на каждое сообщение: три ошибки подряд — и пять минут работаем по
	// словам. Иначе каждое сообщение ждало бы таймаута.
	aiFailsToPause = 3
	aiCooldown     = 5 * time.Minute

	// aiMaxText — сколько букв сообщения показываем модели. Простыню
	// разбирать незачем: просьба всегда в начале, а токены не бесплатные.
	aiMaxText = 1500

	// aiSureNo — насколько уверенно модель должна сказать «не заказ», чтобы
	// мы ей поверили и выбросили сообщение. Сомневается — пусть решают
	// старые правила: лишняя заявка лучше потерянного покупателя.
	aiSureNo = 0.75
)

// aiPrompt — что модель должна сделать. Просим строгий JSON и прямо
// запрещаем домысливать: выдуманный адрес в карточке хуже пустого.
//
// Поле product — название из каталога, который дописывается ниже (aiCatalog).
// Сверять «фарш свиной» из сообщения с «Фарш домашний» в прайсе по буквам
// бесполезно, а модель это делает сама; нам остаётся проверить, что она
// назвала товар, который в каталоге действительно есть.
const aiPrompt = `Ты — помощник продавца интернет-магазина. Читаешь сообщения из мессенджера и решаешь, есть ли в сообщении заказ.
Заказ — просьба привезти, купить, отложить, доставить товар, в том числе без слова «заказ».
НЕ заказ: благодарность, отзыв, вопрос о цене или наличии без просьбы, приветствие, болтовня, объявление продавца.
Ответь ТОЛЬКО JSON без пояснений:
{"order": true|false, "confidence": 0.0-1.0, "name": "имя покупателя, если назвал", "address": "адрес доставки, если назвал", "items": [{"name":"как назвал покупатель","product":"точное название из каталога или пусто","qty":число,"unit":"кг|шт|уп"}], "summary": "суть одной строкой"}
Ничего не придумывай: чего в сообщении нет — оставь пустым.
Покупатель называет товар по-своему: сокращённо, ласково, с ошибками. Найди в каталоге подходящий товар и скопируй в product ЕГО название буква в букву.
Подходящего товара в каталоге нет или их несколько — оставь product пустым.
Количество ставь только то, которое покупатель назвал. Не назвал — пиши 0.`

// aiCatalogTTL — как часто перечитываем прайс для подсказки модели.
// Товары меняются редко, а список в 99 наименований дёргать из базы на
// каждое сообщение незачем.
const aiCatalogTTL = 10 * time.Minute

var (
	catalogMu   sync.Mutex
	catalogText string
	catalogAt   time.Time
)

// resetCatalog сбрасывает запомненный прайс — нужен тестам.
func resetCatalog() {
	catalogMu.Lock()
	catalogText, catalogAt = "", time.Time{}
	catalogMu.Unlock()
}

// aiCatalog — список товаров для промпта. Пустая строка, если прайс не
// прочитался: тогда модель просто не заполнит product, а состав заказа
// по-прежнему уйдёт в комментарий словами покупателя.
func aiCatalog() string {
	if db == nil {
		return ""
	}
	catalogMu.Lock()
	defer catalogMu.Unlock()
	if time.Since(catalogAt) < aiCatalogTTL && catalogText != "" {
		return catalogText
	}
	products, err := listPublishedProducts("", "")
	if err != nil || len(products) == 0 {
		logErr("каталог для разбора ИИ", err)
		return ""
	}
	var b strings.Builder
	b.WriteString("\n\nКаталог магазина (название — единица):\n")
	for _, p := range products {
		b.WriteString(p.Name + " — " + aiUnit(p.Unit) + "\n")
	}
	catalogText, catalogAt = b.String(), time.Now()
	return catalogText
}

// aiShop — строка о самом магазине перед заданием: модели проще понять,
// о чём речь, когда она знает, чем магазин торгует.
func aiShop() string {
	if db == nil {
		return ""
	}
	s := siteText()
	return "Магазин «" + s.Title + "». " + s.Tagline + "\n"
}

// aiConfig — куда ходить за разбором.
type aiConfig struct {
	URL   string
	Model string
	Key   string
}

var (
	aiCfgMu sync.RWMutex
	aiCfg   aiConfig
)

// aiConf — текущие настройки модели. Их меняют из приложения на ходу,
// поэтому читаем копию под замком.
func aiConf() aiConfig {
	aiCfgMu.RLock()
	defer aiCfgMu.RUnlock()
	return aiCfg
}

// Ключи настроек модели в таблице settings.
const (
	keyAIKey   = "ai.key"
	keyAIURL   = "ai.url"
	keyAIModel = "ai.model"
)

// setupAI собирает настройки: сначала база (их задают из приложения),
// потом crmshop.env, потом значения по умолчанию. Зовётся при старте и
// после каждого сохранения из приложения.
func setupAI() {
	pick := func(key, env, def string) string {
		if db != nil {
			if v := siteSetting(key); v != "" {
				return v
			}
		}
		return envOr(env, def)
	}
	cfg := aiConfig{
		URL:   pick(keyAIURL, "SHOP_AI_URL", aiDefaultURL),
		Model: pick(keyAIModel, "SHOP_AI_MODEL", aiDefaultModel),
		Key:   strings.TrimSpace(pick(keyAIKey, "SHOP_AI_KEY", "")),
	}
	aiCfgMu.Lock()
	aiCfg = cfg
	aiCfgMu.Unlock()
	if !aiConfigured() {
		log.Printf("разбор сообщений ИИ недоступен: ключ модели не задан")
		return
	}
	log.Printf("разбор сообщений ИИ: модель %s на %s", cfg.Model, cfg.URL)
}

// aiOn — переключатель из приложения, экран «Бот MAX». Ключ и адрес модели
// задаёт сервер, а продавец решает, пользоваться ли ими: выключил — бот
// сразу работает по словам заказа, включил — снова думает.
var aiOn atomic.Bool

func setAIEnabled(v bool) {
	was := aiOn.Swap(v)
	if was != v && aiConfigured() {
		if v {
			log.Printf("разбор сообщений ИИ включён из приложения")
		} else {
			log.Printf("разбор сообщений ИИ выключен из приложения")
		}
	}
}

// aiConfigured — есть ли вообще чем разбирать: ключ к облаку или своя
// Ollama по соседству. Это настройка сервера, из приложения её не изменить.
func aiConfigured() bool {
	cfg := aiConf()
	if cfg.Model == "" || cfg.URL == "" {
		return false
	}
	return cfg.Key != "" || aiLocalURL(cfg.URL)
}

// aiReady — и настроено, и включено.
func aiReady() bool { return aiConfigured() && aiOn.Load() }

func aiLocalURL(u string) bool {
	return strings.Contains(u, "localhost") || strings.Contains(u, "127.0.0.1")
}

// ------------------------------------------------------------ разбор

// aiItem — позиция, которую модель разглядела в сообщении.
//
// Name — слова покупателя («блинчики с мясом»), Product — то же самое,
// опознанное в каталоге. Пустой Product значит «в прайсе такого не нашлось»:
// такая позиция в заказ не попадает, продавец разбирается сам.
type aiItem struct {
	Name    string  `json:"name"`
	Product string  `json:"product"`
	Qty     float64 `json:"qty"`
	Unit    string  `json:"unit"`
}

// aiVerdict — что модель поняла из сообщения.
type aiVerdict struct {
	Order      bool     `json:"order"`
	Confidence float64  `json:"confidence"`
	Name       string   `json:"name"`
	Address    string   `json:"address"`
	Items      []aiItem `json:"items"`
	Summary    string   `json:"summary"`
}

// askAI отдаёт разбор сообщения.
//
// Второе значение — ответила ли модель. false означает «решай по старым
// правилам», а не «это не заказ»: перепутать эти два смысла значит молча
// потерять все заявки, пока сервис недоступен.
func askAI(text string) (aiVerdict, bool) {
	text = strings.TrimSpace(text)
	if !aiReady() || len([]rune(text)) < 4 || aiPaused() {
		return aiVerdict{}, false
	}
	if r := []rune(text); len(r) > aiMaxText {
		text = string(r[:aiMaxText])
	}

	v, err := aiRequest(text)
	if err != nil {
		aiFailed(err)
		return aiVerdict{}, false
	}
	aiWorked()
	return aiClean(v, text), true
}

// aiRequest — один заход в Ollama.
func aiRequest(text string) (aiVerdict, error) {
	ctx, cancel := context.WithTimeout(context.Background(), aiTimeout)
	defer cancel()

	raw, err := aiChat(ctx, aiShop()+aiPrompt+aiCatalog(), text, true)
	// Не всякая модель умеет «думать», и Ollama отвечает на это ошибкой.
	// Пробуем ещё раз без этого поля — пусть работает любая.
	if err != nil && strings.Contains(strings.ToLower(err.Error()), "think") {
		raw, err = aiChat(ctx, aiShop()+aiPrompt+aiCatalog(), text, false)
	}
	if err != nil {
		return aiVerdict{}, err
	}

	var v aiVerdict
	if err := json.Unmarshal([]byte(jsonObject(raw)), &v); err != nil {
		return aiVerdict{}, fmt.Errorf("ответ модели не разобран: %w", err)
	}
	return v, nil
}

// aiChat отправляет сообщение и возвращает текст ответа модели.
// system — задание: разобрать новое сообщение (aiPrompt) или сверить заказ
// с перепиской (recheckPrompt, см. maxbot_recheck.go).
func aiChat(ctx context.Context, system, text string, think bool) (string, error) {
	cfg := aiConf()
	body := map[string]any{
		"model":   cfg.Model,
		"stream":  false,
		"format":  "json",
		"options": map[string]any{"temperature": 0},
		"messages": []map[string]string{
			{"role": "system", "content": system},
			{"role": "user", "content": text},
		},
	}
	if think {
		// Рассуждения модели нам не нужны — нужен только JSON.
		body["think"] = false
	}
	buf, err := json.Marshal(body)
	if err != nil {
		return "", err
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, cfg.URL,
		bytes.NewReader(buf))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/json")
	if cfg.Key != "" {
		req.Header.Set("Authorization", "Bearer "+cfg.Key)
	}

	res, err := http.DefaultClient.Do(req)
	if err != nil {
		return "", err
	}
	defer res.Body.Close()

	answer, err := io.ReadAll(io.LimitReader(res.Body, 1<<20))
	if err != nil {
		return "", err
	}
	if res.StatusCode != http.StatusOK {
		return "", fmt.Errorf("ollama %d: %s", res.StatusCode,
			strings.TrimSpace(string(answer)))
	}

	var out struct {
		Message struct {
			Content string `json:"content"`
		} `json:"message"`
	}
	if err := json.Unmarshal(answer, &out); err != nil {
		return "", err
	}
	if strings.TrimSpace(out.Message.Content) == "" {
		return "", fmt.Errorf("модель ответила пусто")
	}
	return out.Message.Content, nil
}

// jsonObject вырезает из ответа сам объект: модель иногда добавляет по краям
// пояснение или обрамляет ответ тройными кавычками.
func jsonObject(s string) string {
	start := strings.Index(s, "{")
	end := strings.LastIndex(s, "}")
	if start < 0 || end <= start {
		return s
	}
	return s[start : end+1]
}

// aiClean выбрасывает из ответа всё, чего в сообщении не было.
//
// Модель сильна в понимании смысла, но имя и адрес может «додумать» — а они
// попадают в карточку клиента, и потом продавец повезёт мясо по выдуманному
// адресу. Поэтому оставляем только то, что в тексте действительно есть.
func aiClean(v aiVerdict, text string) aiVerdict {
	switch {
	case v.Confidence < 0:
		v.Confidence = 0
	case v.Confidence > 1:
		v.Confidence = 1
	}
	v.Name = aiKeepIfSaid(v.Name, text)
	v.Address = aiKeepIfSaid(v.Address, text)
	v.Summary = oneLine(v.Summary)

	items := make([]aiItem, 0, len(v.Items))
	for _, it := range v.Items {
		it.Name, it.Unit = oneLine(it.Name), oneLine(it.Unit)
		it.Product = oneLine(it.Product)
		if it.Name == "" {
			it.Name = it.Product
		}
		if it.Name != "" {
			items = append(items, it)
		}
	}
	v.Items = items
	return v
}

// aiProduct — товар из каталога по названию, которое вернула модель.
//
// Название должно совпасть буква в букву (регистр не в счёт) и быть
// единственным: в прайсе встречаются тёзки — два разных «Котлеты по
// Киевски», — и угадывать, который из них имели в виду, мы не беремся.
func aiProduct(name string) (Product, bool) {
	name = strings.TrimSpace(strings.ToLower(name))
	if name == "" {
		return Product{}, false
	}
	products, err := listPublishedProducts("", "")
	if err != nil {
		logErr("товар из разбора ИИ", err)
		return Product{}, false
	}
	var found Product
	hits := 0
	for _, p := range products {
		if strings.TrimSpace(strings.ToLower(p.Name)) == name {
			found, hits = p, hits+1
		}
	}
	return found, hits == 1
}

// aiSplitItems делит разобранный состав на то, что можно положить в заказ,
// и то, что остаётся продавцу словами.
//
// В заказ попадает позиция, у которой товар однозначно опознан в каталоге
// и названо количество. Без количества не берём: поставить «одну штуку» за
// покупателя значит списать со склада то, чего он не просил, а цифра в
// заявке выглядела бы как его слова.
func aiSplitItems(v aiVerdict) (ready []OrderItem, rest []aiItem) {
	for _, it := range v.Items {
		p, ok := aiProduct(it.Product)
		if !ok || it.Qty <= 0 {
			rest = append(rest, it)
			continue
		}
		id := p.ID
		ready = append(ready, OrderItem{
			ProductID: &id, Name: p.Name, Unit: p.Unit, Qty: it.Qty,
			UnitPrice: p.Price, UnitCost: p.Cost,
		})
	}
	return ready, rest
}

// aiKeepIfSaid — значение, если человек его действительно написал.
//
// Сверяем по началу слов: «Ленина 5» из «на Ленина 5» проходит, а адрес из
// соседнего сообщения или придуманный моделью — нет. Начало слова, а не
// слово целиком, потому что падежи: модель отдаёт «Ирина», в тексте «Ирины».
func aiKeepIfSaid(value, text string) string {
	value = oneLine(value)
	if value == "" {
		return ""
	}
	low := strings.ToLower(text)
	for _, w := range splitWords(value) {
		if r := []rune(w); len(r) > 4 {
			w = string(r[:4])
		}
		if !strings.Contains(low, w) {
			return ""
		}
	}
	return value
}

// oneLine складывает перенос строки в пробел: всё это идёт в комментарий
// заказа, где лишние переводы строк только мешают.
func oneLine(s string) string {
	return strings.Join(strings.Fields(strings.ReplaceAll(s, "\n", " ")), " ")
}

// ------------------------------------------------------- когда сервис лёг

var (
	aiMu     sync.Mutex
	aiFails  int
	aiUntil  time.Time
	aiSilent bool // об уходе в офлайн уже писали в журнал
)

// aiPaused — сейчас модель не трогаем: она только что не отвечала.
func aiPaused() bool {
	aiMu.Lock()
	defer aiMu.Unlock()
	return time.Now().Before(aiUntil)
}

// aiFailed считает неудачи. Три подряд — уходим на словарь и ждём.
func aiFailed(err error) {
	aiMu.Lock()
	aiFails++
	first := aiFails == 1
	pause := aiFails >= aiFailsToPause && !aiSilent
	if aiFails >= aiFailsToPause {
		aiUntil = time.Now().Add(aiCooldown)
		aiSilent = true
	}
	aiMu.Unlock()

	if first {
		log.Printf("разбор сообщений ИИ: %v", err)
	}
	if pause {
		log.Printf("разбор сообщений ИИ недоступен — %s работаем по словам заказа",
			aiCooldown)
	}
}

// aiWorked сбрасывает счётчик: модель снова на связи.
func aiWorked() {
	aiMu.Lock()
	back := aiSilent
	aiFails, aiUntil, aiSilent = 0, time.Time{}, false
	aiMu.Unlock()

	if back {
		log.Printf("разбор сообщений ИИ снова работает")
	}
}

// aiReset возвращает счётчики в исходное — нужен тестам.
func aiReset() {
	aiMu.Lock()
	aiFails, aiUntil, aiSilent = 0, time.Time{}, false
	aiMu.Unlock()
}

// ------------------------------------------------------- в карточку заказа

// aiNote — что из разбора увидит продавец в комментарии заявки.
//
// Отдельно то, что бот уже положил в заказ (это надо проверить), и отдельно
// то, что он положить не смог (это надо доделать руками). Перепутать их
// нельзя: в первом случае товар со склада уже списан.
func aiNote(v aiVerdict, ready []OrderItem, rest []aiItem) string {
	var out []string
	if v.Summary != "" {
		out = append(out, "Разбор ИИ: "+v.Summary)
	}
	if len(ready) > 0 {
		parts := make([]string, 0, len(ready))
		for _, it := range ready {
			parts = append(parts, it.Name+" — "+formatQty(it.Qty)+" "+aiUnit(it.Unit))
		}
		out = append(out, "Бот внёс в заказ (проверьте): "+strings.Join(parts, "; "))
	}
	if len(rest) > 0 {
		parts := make([]string, 0, len(rest))
		for _, it := range rest {
			parts = append(parts, aiItemText(it))
		}
		out = append(out, "Просили ещё, внесите сами: "+strings.Join(parts, "; "))
	}
	if v.Address != "" {
		out = append(out, "Адрес из сообщения: "+v.Address)
	}
	return strings.Join(out, "\n")
}

// aiItemText — позиция одной строкой: «пельмени — 3 кг», а без количества
// просто «тушёнка».
func aiItemText(it aiItem) string {
	if it.Qty <= 0 {
		return it.Name
	}
	return it.Name + " — " + formatQty(it.Qty) + " " + aiUnit(it.Unit)
}

// aiUnit — единица измерения, по умолчанию штуки.
func aiUnit(u string) string {
	if u == "" {
		return "шт"
	}
	return u
}
