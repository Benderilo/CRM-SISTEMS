package main

import (
	"fmt"
	"strings"
	"time"
)

// Статусы заказа. Значения совпадают с константами в Android-приложении.
const (
	StatusNew      = 0 // Новый — только что оформлен на сайте
	StatusAccepted = 1 // Принят — менеджер подтвердил
	StatusPacking  = 2 // Собирается
	StatusDelivery = 3 // В доставке
	StatusDone     = 4 // Выполнен — попадает в выручку
	StatusCanceled = 5 // Отменён — в аналитике не учитывается
)

var StatusNames = []string{"Новый", "Принят", "Собирается", "В доставке", "Выполнен", "Отменён"}

// StatusSlug — css-класс для бейджа статуса.
var StatusSlugs = []string{"new", "accepted", "packing", "delivery", "done", "canceled"}

func statusName(s int) string {
	if s >= 0 && s < len(StatusNames) {
		return StatusNames[s]
	}
	return "—"
}

func statusSlug(s int) string {
	if s >= 0 && s < len(StatusSlugs) {
		return StatusSlugs[s]
	}
	return "new"
}

// Product — карточка товара. Поля повторяют Room-сущность Product в приложении,
// плюс published/sortOrder/image — они нужны только витрине.
type Product struct {
	ID          int64
	Name        string
	SKU         string
	Category    string
	Unit        string
	Price       float64
	Cost        float64
	Stock       float64
	MinStock    float64
	Barcode     string
	Description string
	Image       string
	Archived    bool
	Published   bool
	SortOrder   int
	CreatedAt   int64
	UpdatedAt   int64
}

func (p Product) Title() string {
	if strings.TrimSpace(p.Name) == "" {
		return "Без названия"
	}
	return p.Name
}

// InStock — есть ли товар на складе. Показываем только в админке: покупателю
// остатки не видны и заказать он может что угодно — магазин доготовит партию.
func (p Product) InStock() bool { return p.Stock > 0 }

func (p Product) IsLowStock() bool { return p.MinStock > 0 && p.Stock <= p.MinStock }

// Step — на сколько меняют количество кнопки «−» и «+» в корзине.
//
// Целая единица и для штучного, и для весового товара: мясо берут
// килограммами, и полкило кнопками только мешали — чтобы набрать три
// килограмма, приходилось жать шесть раз.
func (p Product) Step() float64 { return 1 }

// MinQty — до скольких опускают кнопки «−»: доставка начинается с килограмма.
// Ручной ввод этим не ограничен: если покупателю нужно 0,5 — он впишет 0,5,
// и заказ примется.
func (p Product) MinQty() float64 { return 1 }

// MarginPercent — наценка в процентах (для админки).
func (p Product) MarginPercent() float64 {
	if p.Price <= 0 {
		return 0
	}
	return (p.Price - p.Cost) / p.Price * 100
}

func (p Product) StockValue() float64 { return p.Stock * p.Cost }

// ImageURL — путь к картинке, либо заглушка.
func (p Product) ImageURL() string {
	if strings.TrimSpace(p.Image) == "" {
		return ""
	}
	return p.Image
}

// Client — покупатель. Логин — телефон. ФИО хранится по частям (так просил заказчик),
// а FullName() собирает строку для приложения, где поле name одно.
type Client struct {
	ID           int64
	Phone        string
	PasswordHash string
	Email        string
	LastName     string // фамилия
	FirstName    string // имя
	MiddleName   string // отчество
	Address      string
	Segment      string
	Source       string
	Note         string
	Archived     bool
	Blocked      bool // заблокирован администратором: вход и заказы на сайте запрещены
	CreatedAt    int64
	UpdatedAt    int64
}

// FullName — «Фамилия Имя Отчество» без лишних пробелов.
func (c Client) FullName() string {
	parts := []string{}
	for _, s := range []string{c.LastName, c.FirstName, c.MiddleName} {
		if t := strings.TrimSpace(s); t != "" {
			parts = append(parts, t)
		}
	}
	if len(parts) == 0 {
		return c.Phone
	}
	return strings.Join(parts, " ")
}

// ShortName — «Имя О.» для шапки сайта.
func (c Client) ShortName() string {
	if strings.TrimSpace(c.FirstName) != "" {
		return c.FirstName
	}
	return c.Phone
}

func (c Client) Initials() string {
	src := strings.Fields(c.FullName())
	out := ""
	for i := 0; i < len(src) && len(out) < 2; i++ {
		r := []rune(src[i])
		if len(r) > 0 {
			out += strings.ToUpper(string(r[0]))
		}
	}
	if out == "" {
		return "?"
	}
	return out
}

// sourceApp помечает заказы, заведённые продавцом в приложении CRM,
// чтобы в админке их было видно отдельно от покупок с витрины.
const sourceApp = "app"

// sourceMax помечает заказы, собранные покупателем в мессенджере MAX:
// в остальном это обычный заказ покупателя — со списанием склада и статусами.
const sourceMax = "max"

// Order — заказ с сайта.
type Order struct {
	ID         int64
	ClientID   *int64
	ClientName string
	Phone      string
	Email      string
	Address    string
	Comment    string
	Delivery   string // «Доставка» / «Самовывоз»
	PayMethod  string
	Status     int
	Total      float64
	Source     string // «» — покупка с витрины, «app» — продажа из приложения
	// Лежит ли товар этого заказа списанным со склада прямо сейчас. При отмене
	// заказа товар возвращается и отметка снимается, при возврате в работу —
	// наоборот. По ней же админка показывает кнопку ручного списания.
	StockWritten bool
	CreatedAt    int64
	UpdatedAt    int64

	Items []OrderItem // заполняется только при загрузке одного заказа

	// Merged — заказ не создан заново, а дописан в уже открытый заказ того же
	// покупателя (см. merge.go). В базе не хранится: нужен сразу после
	// оформления, чтобы сказать «добавили к заказу №…», а не «заказ принят».
	Merged bool
}

// Number — человекочитаемый номер заказа.
func (o Order) Number() string { return fmt.Sprintf("№%04d", o.ID) }

// FromApp — заказ завёл продавец в приложении, а не покупатель на сайте.
func (o Order) FromApp() bool { return o.Source == sourceApp }

func (o Order) StatusName() string { return statusName(o.Status) }
func (o Order) StatusSlug() string { return statusSlug(o.Status) }

func (o Order) CreatedTime() time.Time { return time.UnixMilli(o.CreatedAt) }

// CreatedStr — дата в формате «13.08.2026 14:05».
func (o Order) CreatedStr() string {
	return time.UnixMilli(o.CreatedAt).In(moscow).Format("02.01.2006 15:04")
}

// Profit — прибыль по заказу (по снимку себестоимости).
func (o Order) Profit() float64 {
	var p float64
	for _, it := range o.Items {
		p += it.Qty * (it.UnitPrice - it.UnitCost)
	}
	return p
}

// ItemCount — сколько позиций в заказе.
func (o Order) ItemCount() int { return len(o.Items) }

// OrderItem — позиция заказа. Цена и себестоимость — снимок на момент оформления,
// чтобы старые заказы не менялись при правке карточки товара.
type OrderItem struct {
	ID        int64
	OrderID   int64
	ProductID *int64
	Name      string
	Unit      string
	Qty       float64
	UnitPrice float64
	UnitCost  float64
	Image     string
}

func (i OrderItem) Sum() float64 { return i.Qty * i.UnitPrice }

// Profit — прибыль по позиции по снимку себестоимости.
func (i OrderItem) Profit() float64 { return i.Qty * (i.UnitPrice - i.UnitCost) }

// Admin — сотрудник с доступом в панель управления.
type Admin struct {
	ID           int64
	Login        string
	PasswordHash string
	Name         string
	CreatedAt    int64
}

// CartLine — строка корзины, собранная из cookie + актуальных данных товара.
type CartLine struct {
	Product Product
	Qty     float64
}

func (l CartLine) Sum() float64 { return l.Qty * l.Product.Price }

// cardView — то, что нужно карточке товара в каталоге: сам товар, сколько его
// уже лежит в корзине и куда вернуть покупателя после нажатия «В корзину».
type cardView struct {
	P      Product
	InCart float64
	Back   string
}

// Cart — корзина целиком.
type Cart struct {
	Lines []CartLine
}

func (c Cart) Total() float64 {
	var t float64
	for _, l := range c.Lines {
		t += l.Sum()
	}
	return t
}

func (c Cart) Count() int { return len(c.Lines) }

func (c Cart) Empty() bool { return len(c.Lines) == 0 }
