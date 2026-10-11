// Команда crmshop — универсальный интернет-магазин: витрина, панель управления,
// REST API для Android-приложения CRM SISTEMS и бот MAX. Один бинарь на магазин;
// название, тексты, токен бота и ключ ИИ задаются из приложения и лежат в базе.
package main

import (
	"context"
	"crypto/sha256"
	"embed"
	"encoding/hex"
	"errors"
	"flag"
	"fmt"
	"html/template"
	"io/fs"
	"log"
	"math"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"
)

//go:embed templates/*.html templates/admin/*.html
var tplFS embed.FS

//go:embed static
var staticFS embed.FS

// version — версия сервера; при сборке подставляется через
// -ldflags "-X main.version=…". Приложение сверяет её, чтобы предложить обновление.
var version = "dev"

var (
	// moscow — часовой пояс продавца. Значение по умолчанию задано сразу:
	// время печатается и до того, как main успеет прочитать базу зон.
	moscow        = time.FixedZone("MSK", 3*3600)
	secureCookies = true
	uploadDir     string
	apiKey        string
	// dbFile — где лежит база: рядом с ней сохраняются копии удалённых заказов.
	dbFile string
	// publicURL — адрес витрины для кнопок в боте MAX. Приложение присылает
	// его вместе с настройками бота, поэтому вручную задавать не нужно.
	publicURL string
)

// tpl хранит по одному готовому шаблону на страницу.
var tpl = map[string]*template.Template{}

var funcs = template.FuncMap{
	"money":      money,
	"qty":        formatQty,
	"phone":      formatPhone,
	"statusName": statusName,
	"statusSlug": statusSlug,
	"statuses":   func() []string { return StatusNames },
	"add":        func(a, b int) int { return a + b },
	"pct": func(a, b float64) float64 {
		if b == 0 {
			return 0
		}
		return a / b * 100
	},
	"initial":     firstLetter,
	"dateStr":     func(ms int64) string { return time.UnixMilli(ms).In(moscow).Format("02.01.2006") },
	"dateTimeStr": func(ms int64) string { return time.UnixMilli(ms).In(moscow).Format("02.01.2006 15:04") },
	"hasPrefix":   strings.HasPrefix,
	"ver":         func() string { return assetVer },
	"card":        cardOf,
}

// cardOf собирает данные карточки товара: {{template "productcard" card . $}}.
func cardOf(p Product, pg page) cardView {
	return cardView{P: p, InCart: pg.CartQty[p.ID], Back: pg.Path}
}

// money — «1 250,50 ₽» с неразрывными пробелами в разрядах.
func money(v float64) string {
	neg := v < 0
	if neg {
		v = -v
	}
	whole := int64(math.Floor(v))
	cents := int64(math.Round((v - float64(whole)) * 100))
	if cents == 100 {
		whole++
		cents = 0
	}
	s := strconv.FormatInt(whole, 10)
	var b strings.Builder
	for i, r := range s {
		if i > 0 && (len(s)-i)%3 == 0 {
			b.WriteString(" ")
		}
		b.WriteRune(r)
	}
	out := b.String()
	if cents > 0 {
		out += fmt.Sprintf(",%02d", cents)
	}
	if neg {
		out = "−" + out
	}
	return out + " ₽"
}

// formatQty печатает количество без хвостовых нулей: 1,5 / 2 / 0,25.
func formatQty(v float64) string {
	s := strconv.FormatFloat(v, 'f', 3, 64)
	s = strings.TrimRight(s, "0")
	s = strings.TrimSuffix(s, ".")
	if s == "" || s == "-" {
		s = "0"
	}
	return strings.ReplaceAll(s, ".", ",")
}

func firstLetter(s string) string {
	r := []rune(strings.TrimSpace(s))
	if len(r) == 0 {
		return "?"
	}
	return strings.ToUpper(string(r[0]))
}

// loadTemplates собирает каждую страницу вместе с её раскладкой.
func loadTemplates() error {
	pages, err := fs.Glob(tplFS, "templates/*.html")
	if err != nil {
		return err
	}
	adminPages, err := fs.Glob(tplFS, "templates/admin/*.html")
	if err != nil {
		return err
	}
	for _, p := range append(pages, adminPages...) {
		name := strings.TrimPrefix(p, "templates/")
		if name == "layout.html" || name == "admin/layout.html" {
			continue
		}
		layout := "templates/layout.html"
		if strings.HasPrefix(name, "admin/") {
			layout = "templates/admin/layout.html"
		}
		t, err := template.New("").Funcs(funcs).ParseFS(tplFS, layout, p)
		if err != nil {
			return fmt.Errorf("%s: %w", name, err)
		}
		tpl[name] = t
	}
	return nil
}

// page — общие данные, которые нужны каждому шаблону.
type page struct {
	Title     string
	Client    *Client
	Admin     *Admin
	CartCount int
	// CartQty — сколько какого товара уже в корзине: карточка каталога показывает
	// это прямо под кнопкой, чтобы при наборе заказа подряд было видно, что взято.
	CartQty map[int64]float64
	// Path — адрес текущей страницы: куда вернуть покупателя после «В корзину».
	Path string
	// Site — тексты витрины (контакты, верхняя полоска), правятся из приложения.
	Site  SiteText
	Flash string
	Error string
	Nav   string
	Data  any
}

func render(w http.ResponseWriter, r *http.Request, name string, p page) {
	t, ok := tpl[name]
	if !ok {
		log.Printf("нет шаблона %s", name)
		http.Error(w, "Шаблон не найден", http.StatusInternalServerError)
		return
	}
	if p.Client == nil {
		if c, ok := currentClient(r); ok {
			p.Client = &c
		}
	}
	if p.CartQty == nil {
		entries := readCart(r)
		p.CartQty = make(map[int64]float64, len(entries))
		for _, e := range entries {
			p.CartQty[e.ID] = e.Qty
		}
		if p.CartCount == 0 {
			p.CartCount = len(entries)
		}
	}
	if p.Path == "" {
		p.Path = r.URL.RequestURI()
	}
	p.Site = siteText()
	if p.Flash == "" {
		p.Flash = flashAdded(r)
	}
	if p.Title == "" {
		p.Title = p.Site.Title
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	if err := t.ExecuteTemplate(w, "layout", p); err != nil {
		log.Printf("шаблон %s: %v", name, err)
	}
}

func main() {
	addr := flag.String("addr", envOr("SHOP_ADDR", "127.0.0.1:8071"), "адрес прослушивания")
	dbPath := flag.String("db", envOr("SHOP_DB", "./data/shop.db"), "файл базы")
	uploads := flag.String("uploads", envOr("SHOP_UPLOADS", "./data/uploads"), "папка картинок")
	adminLogin := flag.String("admin-login", envOr("SHOP_ADMIN_LOGIN", "admin"), "логин админа")
	adminPass := flag.String("admin-pass", envOr("SHOP_ADMIN_PASS", ""), "пароль админа при первом запуске")
	insecure := flag.Bool("insecure-cookies", envOr("SHOP_INSECURE", "") != "", "не требовать HTTPS для cookie")
	// На сервере ключ приходит из crmshop.env; флаг нужен для запуска на своей
	// машине, где переменные окружения задавать неудобно.
	key := flag.String("api-key", os.Getenv("SHOP_API_KEY"), "ключ CRM-API для приложения")
	showVersion := flag.Bool("version", false, "напечатать версию и выйти")
	flag.Parse()
	if *showVersion {
		fmt.Println(version)
		return
	}

	secureCookies = !*insecure
	uploadDir = *uploads
	dbFile = *dbPath
	apiKey = *key

	if loc, err := time.LoadLocation("Europe/Moscow"); err == nil {
		moscow = loc
	}

	if err := os.MkdirAll(uploadDir, 0o755); err != nil {
		log.Fatalf("папка картинок: %v", err)
	}
	if err := openDB(*dbPath); err != nil {
		log.Fatalf("база: %v", err)
	}
	publicURL = siteSetting(keyBotShopURL)
	setCallWords(siteSetting(keyBotCall))
	setupAI()
	computeAssetVer()
	if err := loadTemplates(); err != nil {
		log.Fatalf("шаблоны: %v", err)
	}

	if *adminPass != "" {
		if err := ensureAdmin(*adminLogin, *adminPass); err != nil {
			log.Fatalf("создание админа: %v", err)
		}
	}

	// Раз в час чистим протухшие сессии — и покупателей сайта, и разговоры бота.
	go func() {
		for {
			purgeExpiredSessions()
			purgeOldSessions()
			purgeSeenMessages()
			time.Sleep(time.Hour)
		}
	}()

	// Раз в час убираем картинки, на которые больше не ссылается ни один товар.
	startPhotoCleanup(uploadDir)

	// Бот MAX поднимается сам, если в приложении задан токен.
	bot.apply()
	// …и раз в сутки поднимается заново: см. maxbot_restart.go.
	startBotWatch()
	// Сверка заявок с перепиской: см. maxbot_recheck.go.
	startRecheck()

	mux := http.NewServeMux()
	registerShop(mux)
	registerAdmin(mux)
	registerAPI(mux)

	sub, _ := fs.Sub(staticFS, "static")
	mux.Handle("GET /static/", http.StripPrefix("/static/",
		cacheControl(http.FileServer(http.FS(sub)))))
	// Путь именно /photo/, а не /uploads/: на этом же nginx живёт чужой
	// медиа-сервис с блоком «location /upload», а он матчится по префиксу
	// и перехватывал бы наши /uploads/... себе.
	mux.Handle("GET /photo/", http.StripPrefix("/photo/",
		cacheControl(http.FileServer(http.Dir(uploadDir)))))

	srv := &http.Server{
		Addr:              *addr,
		Handler:           logRequests(mux),
		ReadHeaderTimeout: 15 * time.Second,
		WriteTimeout:      60 * time.Second,
		IdleTimeout:       90 * time.Second,
	}
	log.Printf("магазин слушает %s, база %s", *addr, *dbPath)
	go func() {
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatal(err)
		}
	}()

	// Останавливаемся по сигналу от systemctl stop: сервер дослуживает запросы,
	// а база закрывается явно. Это важно для резервных копий — при закрытии
	// SQLite сбрасывает журнал WAL в сам файл базы, иначе скопированный shop.db
	// без соседнего shop.db-wal оказывается пустым.
	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	<-stop
	log.Printf("останавливаюсь…")

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := srv.Shutdown(ctx); err != nil {
		log.Printf("остановка сервера: %v", err)
	}
	if err := db.Close(); err != nil {
		log.Printf("закрытие базы: %v", err)
	}
	log.Printf("остановлен")
}

// logErr пишет ошибку в журнал, не роняя запрос.
func logErr(what string, err error) {
	if err != nil {
		log.Printf("%s: %v", what, err)
	}
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func cacheControl(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "public, max-age=86400")
		h.ServeHTTP(w, r)
	})
}

// assetVer — короткий отпечаток стилей и скриптов. Подставляется в ссылки
// (?v=...), поэтому после обновления сайта браузер берёт свежий файл, а не
// держит сутки старый из кэша.
var assetVer = "dev"

func computeAssetVer() {
	h := sha256.New()
	err := fs.WalkDir(staticFS, "static", func(path string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return err
		}
		data, err := staticFS.ReadFile(path)
		if err != nil {
			return err
		}
		h.Write([]byte(path))
		h.Write(data)
		return nil
	})
	if err != nil {
		log.Printf("отпечаток статики: %v", err)
		return
	}
	assetVer = hex.EncodeToString(h.Sum(nil))[:10]
}

func logRequests(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		sw := &statusWriter{ResponseWriter: w, code: 200}
		h.ServeHTTP(sw, r)
		if sw.code >= 400 || r.Method != http.MethodGet {
			log.Printf("%s %s -> %d (%s)", r.Method, r.URL.Path, sw.code,
				time.Since(start).Round(time.Millisecond))
		}
	})
}

type statusWriter struct {
	http.ResponseWriter
	code int
}

func (w *statusWriter) WriteHeader(c int) {
	w.code = c
	w.ResponseWriter.WriteHeader(c)
}

// pathID достаёт числовой параметр маршрута.
func pathID(r *http.Request, name string) (int64, bool) {
	v, err := strconv.ParseInt(r.PathValue(name), 10, 64)
	if err != nil || v <= 0 {
		return 0, false
	}
	return v, true
}

// parseFloat принимает и «1.5», и «1,5» — люди пишут по-разному.
func parseFloat(s string) float64 {
	s = strings.TrimSpace(strings.ReplaceAll(s, ",", "."))
	s = strings.ReplaceAll(s, " ", "")
	s = strings.ReplaceAll(s, " ", "")
	v, err := strconv.ParseFloat(s, 64)
	if err != nil {
		return 0
	}
	return v
}
