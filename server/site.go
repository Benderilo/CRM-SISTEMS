package main

import (
	"database/sql"
	"strings"
	"sync"
)

// Тексты витрины — контакты, описания разделов и бегущая строка новостей.
// Всё лежит в базе и правится из приложения («Контакты и новости»), чтобы
// хозяину магазина не приходилось лезть в код и пересобирать сервер.

// SiteText — набор текстов, которые видит покупатель.
type SiteText struct {
	Title    string `json:"title"`   // название магазина: шапка, вкладка браузера, бот
	Tagline  string `json:"tagline"` // строка под названием и в подвале
	Hero     string `json:"hero"`    // абзац на главной под заголовком
	Phone    string `json:"phone"`
	Hours    string `json:"hours"`
	Email    string `json:"email"`
	TopLeft  string `json:"topLeft"`  // верхняя полоска слева
	TopRight string `json:"topRight"` // верхняя полоска справа
	Delivery string `json:"delivery"` // раздел «Доставка» на странице контактов
	Pay      string `json:"pay"`      // раздел «Оплата»
	About    string `json:"about"`    // раздел «О продукции»
}

// siteDefaults — что показывать, пока в приложении ничего не заполнили.
var siteDefaults = SiteText{
	Title:    "Мой магазин",
	Tagline:  "Заказ с доставкой",
	Hero:     "Выберите товары в каталоге, положите в корзину и оформите заказ — мы перезвоним и привезём.",
	Phone:    "+7 (000) 000-00-00",
	Hours:    "ежедневно, 8:00 – 20:00",
	Email:    "zakaz@example.ru",
	TopLeft:  "Приём заказов ежедневно 8:00–20:00",
	TopRight: "Доставка по городу от 2 000 ₽ — бесплатно",
	Delivery: "Доставляем по городу в день заказа, если заказ оформлен до 16:00. " +
		"Более поздние — на следующий день. Точное время согласуем по телефону.\n" +
		"Самовывоз — бесплатно, по договорённости.",
	Pay: "Наличными или картой при получении. Для постоянных клиентов возможен " +
		"безналичный расчёт — уточняйте по телефону.",
	About: "Подробное описание каждого товара — в его карточке в каталоге.",
}

// PhoneLink — номер для ссылки tel:, только цифры и плюс.
func (s SiteText) PhoneLink() string {
	var b strings.Builder
	for i, r := range s.Phone {
		if r >= '0' && r <= '9' || (i == 0 && r == '+') {
			b.WriteRune(r)
		}
	}
	return b.String()
}

// Initial — буква для значка в шапке: первая буква названия.
func (s SiteText) Initial() string { return firstLetter(s.Title) }

// shopName — название магазина для заголовков страниц и сообщений бота.
func shopName() string { return siteText().Title }

// Lines разбивает многострочный текст на абзацы — в шаблоне удобно пройти range.
func (s SiteText) lines(text string) []string {
	var out []string
	for _, l := range strings.Split(text, "\n") {
		if t := strings.TrimSpace(l); t != "" {
			out = append(out, t)
		}
	}
	return out
}

func (s SiteText) DeliveryLines() []string { return s.lines(s.Delivery) }
func (s SiteText) PayLines() []string      { return s.lines(s.Pay) }
func (s SiteText) AboutLines() []string    { return s.lines(s.About) }

// NewsItem — строка новости для бегущей строки на главной и в каталоге.
type NewsItem struct {
	ID        int64  `json:"id"`
	Text      string `json:"text"`
	Published bool   `json:"published"`
	CreatedAt int64  `json:"createdAt"`
	UpdatedAt int64  `json:"updatedAt"`
}

// ------------------------------------------------------------ хранение

// Тексты читаются на каждой странице, поэтому держим их в памяти и обновляем
// при сохранении — база лишний раз не дёргается.
var (
	siteMu    sync.RWMutex
	siteCache *SiteText
)

func siteText() SiteText {
	siteMu.RLock()
	cached := siteCache
	siteMu.RUnlock()
	if cached != nil {
		return *cached
	}

	s := siteDefaults
	rows, err := db.Query(`SELECT key, value FROM settings`)
	if err != nil {
		logErr("чтение текстов сайта", err)
		return s
	}
	defer rows.Close()
	for rows.Next() {
		var k, v string
		if err := rows.Scan(&k, &v); err != nil {
			continue
		}
		if strings.TrimSpace(v) == "" {
			continue // пустое поле — показываем текст по умолчанию
		}
		switch k {
		case "site.title":
			s.Title = v
		case "site.tagline":
			s.Tagline = v
		case "site.hero":
			s.Hero = v
		case "site.phone":
			s.Phone = v
		case "site.hours":
			s.Hours = v
		case "site.email":
			s.Email = v
		case "site.topLeft":
			s.TopLeft = v
		case "site.topRight":
			s.TopRight = v
		case "site.delivery":
			s.Delivery = v
		case "site.pay":
			s.Pay = v
		case "site.about":
			s.About = v
		}
	}

	siteMu.Lock()
	siteCache = &s
	siteMu.Unlock()
	return s
}

func saveSiteText(s SiteText) error {
	pairs := [][2]string{
		{"site.title", s.Title}, {"site.tagline", s.Tagline}, {"site.hero", s.Hero},
		{"site.phone", s.Phone}, {"site.hours", s.Hours}, {"site.email", s.Email},
		{"site.topLeft", s.TopLeft}, {"site.topRight", s.TopRight},
		{"site.delivery", s.Delivery}, {"site.pay", s.Pay}, {"site.about", s.About},
	}
	tx, err := db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	for _, p := range pairs {
		if _, err := tx.Exec(`INSERT INTO settings (key, value, updated_at) VALUES (?,?,?)
          ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at`,
			p[0], strings.TrimSpace(p[1]), nowMs()); err != nil {
			return err
		}
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	siteMu.Lock()
	siteCache = nil // перечитаем при первом же показе страницы
	siteMu.Unlock()
	return nil
}

// siteSetting — значение одной настройки; пусто, если её нет.
func siteSetting(key string) string {
	var v string
	if err := db.QueryRow(`SELECT value FROM settings WHERE key = ?`, key).Scan(&v); err != nil {
		return ""
	}
	return strings.TrimSpace(v)
}

// setSetting сохраняет одну настройку. Нужна для служебных отметок вроде
// «до какого времени бот разобрал историю» — они не относятся ни к текстам
// витрины, ни к настройкам бота, но живут в той же таблице.
func setSetting(key, value string) {
	_, err := db.Exec(`INSERT INTO settings (key, value, updated_at) VALUES (?,?,?)
      ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at`,
		key, value, nowMs())
	logErr("настройка "+key, err)
}

// listNews отдаёт новости, свежие сверху. onlyPublished — то, что видит покупатель.
func listNews(onlyPublished bool) ([]NewsItem, error) {
	q := `SELECT id, text, published, created_at, updated_at FROM news`
	if onlyPublished {
		q += ` WHERE published = 1`
	}
	q += ` ORDER BY created_at DESC, id DESC`
	rows, err := db.Query(q)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []NewsItem{}
	for rows.Next() {
		var n NewsItem
		if err := rows.Scan(&n.ID, &n.Text, &n.Published, &n.CreatedAt, &n.UpdatedAt); err != nil {
			return nil, err
		}
		out = append(out, n)
	}
	return out, rows.Err()
}

// saveNews создаёт новость (id = 0) или правит существующую.
func saveNews(n *NewsItem) error {
	n.Text = strings.TrimSpace(n.Text)
	n.UpdatedAt = nowMs()
	if n.ID == 0 {
		if n.CreatedAt == 0 {
			n.CreatedAt = nowMs()
		}
		res, err := db.Exec(`INSERT INTO news (text, published, created_at, updated_at)
          VALUES (?,?,?,?)`, n.Text, n.Published, n.CreatedAt, n.UpdatedAt)
		if err != nil {
			return err
		}
		n.ID, err = res.LastInsertId()
		return err
	}
	_, err := db.Exec(`UPDATE news SET text = ?, published = ?, updated_at = ? WHERE id = ?`,
		n.Text, n.Published, n.UpdatedAt, n.ID)
	return err
}

func deleteNews(id int64) error {
	res, err := db.Exec(`DELETE FROM news WHERE id = ?`, id)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return sql.ErrNoRows
	}
	return nil
}
