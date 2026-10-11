package main

import (
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"path/filepath"
	"strconv"
	"strings"
)

// REST API для Android-приложения CRM Богодухов.
//
// Покупатель работает по токену (Authorization: Bearer <token>), который выдаётся
// на /api/v1/auth/login. Разделы /api/v1/crm/* — для самого приложения-CRM,
// они закрыты ключом из переменной окружения SHOP_API_KEY (заголовок X-API-Key).

func registerAPI(mux *http.ServeMux) {
	mux.HandleFunc("POST /api/v1/auth/register", apiRegister)
	mux.HandleFunc("POST /api/v1/auth/login", apiLogin)
	mux.HandleFunc("GET /api/v1/me", apiMe)
	mux.HandleFunc("GET /api/v1/products", apiProducts)
	mux.HandleFunc("GET /api/v1/categories", apiCategories)
	mux.HandleFunc("GET /api/v1/orders", apiMyOrders)
	mux.HandleFunc("POST /api/v1/orders", apiCreateOrder)

	// CRM-раздел: приложение видит ровно то же, что админка сайта.
	mux.Handle("GET /api/v1/crm/products", requireKey(apiCrmProducts))
	mux.Handle("POST /api/v1/crm/products", requireKey(apiCrmSaveProduct))
	mux.Handle("GET /api/v1/crm/clients", requireKey(apiCrmClients))
	mux.Handle("GET /api/v1/crm/orders", requireKey(apiCrmOrders))
	mux.Handle("POST /api/v1/crm/orders", requireKey(apiCrmCreateOrder))
	mux.Handle("POST /api/v1/crm/orders/status", requireKey(apiCrmOrderStatus))
	mux.Handle("POST /api/v1/crm/orders/merge", requireKey(apiCrmMergeOrders))
	mux.Handle("POST /api/v1/crm/orders/item", requireKey(apiCrmOrderItem))
	mux.Handle("POST /api/v1/crm/orders/item/add", requireKey(apiCrmOrderItemAdd))
	mux.Handle("POST /api/v1/crm/orders/client", requireKey(apiCrmOrderClient))
	mux.Handle("POST /api/v1/crm/orders/delete", requireKey(apiCrmDeleteOrder))
	mux.Handle("POST /api/v1/crm/orders/purge", requireKey(apiCrmPurgeOrders))
	mux.Handle("GET /api/v1/crm/stats", requireKey(apiCrmStats))
	mux.Handle("POST /api/v1/crm/clients/save", requireKey(apiCrmSaveClient))
	mux.Handle("POST /api/v1/crm/clients/delete", requireKey(apiCrmClientDelete))
	mux.Handle("POST /api/v1/crm/clients/password", requireKey(apiCrmClientPassword))
	mux.Handle("POST /api/v1/crm/products/photo", requireKey(apiCrmUploadPhoto))
	mux.Handle("GET /api/v1/crm/admin", requireKey(apiCrmAdminInfo))
	mux.Handle("POST /api/v1/crm/admin/password", requireKey(apiCrmAdminPassword))
	mux.Handle("GET /api/v1/crm/ping", requireKey(apiCrmPing))
	mux.Handle("GET /api/v1/crm/packed", requireKey(apiCrmPacked))
	mux.Handle("POST /api/v1/crm/packed", requireKey(apiCrmSavePacked))

	// Тексты витрины и новости — правятся из приложения.
	mux.Handle("GET /api/v1/crm/site", requireKey(apiCrmSite))
	mux.Handle("POST /api/v1/crm/site", requireKey(apiCrmSaveSite))
	mux.Handle("POST /api/v1/crm/news", requireKey(apiCrmSaveNews))
	mux.Handle("POST /api/v1/crm/news/delete", requireKey(apiCrmDeleteNews))

	// Бот MAX: токен и группа задаются из приложения.
	mux.Handle("GET /api/v1/crm/bot", requireKey(apiCrmBot))
	mux.Handle("POST /api/v1/crm/bot", requireKey(apiCrmSaveBot))
	mux.Handle("POST /api/v1/crm/bot/check", requireKey(apiCrmBotCheck))
	mux.Handle("POST /api/v1/crm/bot/restart", requireKey(apiCrmBotRestart))
	mux.Handle("GET /api/v1/crm/bot/chats", requireKey(apiCrmBotChats))
	mux.Handle("POST /api/v1/crm/bot/announce", requireKey(apiCrmBotAnnounce))
	mux.Handle("POST /api/v1/crm/bot/menu", requireKey(apiCrmBotMenu))
}

// ------------------------------------------------------------ помощники

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	enc := json.NewEncoder(w)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		logErr("json", err)
	}
}

func apiErr(w http.ResponseWriter, code int, msg string) {
	writeJSON(w, code, map[string]any{"ok": false, "error": msg})
}

// readJSON разбирает тело запроса, ограничивая его размер.
func readJSON(w http.ResponseWriter, r *http.Request, dst any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, 1<<20)
	if err := json.NewDecoder(r.Body).Decode(dst); err != nil {
		apiErr(w, http.StatusBadRequest, "Некорректный JSON: "+err.Error())
		return false
	}
	return true
}

// bearer достаёт токен из заголовка Authorization.
func bearer(r *http.Request) string {
	h := r.Header.Get("Authorization")
	if strings.HasPrefix(h, "Bearer ") {
		return strings.TrimSpace(strings.TrimPrefix(h, "Bearer "))
	}
	return ""
}

// apiClient возвращает покупателя по токену.
func apiClient(r *http.Request) (Client, bool) {
	id, err := lookupSession(bearer(r), "client")
	if err != nil {
		return Client{}, false
	}
	c, err := getClient(id)
	return c, err == nil
}

// requireKey закрывает CRM-раздел ключом.
func requireKey(h http.HandlerFunc) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if apiKey == "" {
			apiErr(w, http.StatusServiceUnavailable, "API-ключ не настроен на сервере")
			return
		}
		if r.Header.Get("X-API-Key") != apiKey {
			apiErr(w, http.StatusUnauthorized, "Неверный ключ")
			return
		}
		h(w, r)
	})
}

// ------------------------------------------------------ представления

// productJSON — товар в том виде, в каком его удобно читать приложению.
type productJSON struct {
	ID          int64   `json:"id"`
	Name        string  `json:"name"`
	SKU         string  `json:"sku"`
	Category    string  `json:"category"`
	Unit        string  `json:"unit"`
	Price       float64 `json:"price"`
	Cost        float64 `json:"cost"`
	Stock       float64 `json:"stock"`
	MinStock    float64 `json:"minStock"`
	Barcode     string  `json:"barcode"`
	Description string  `json:"description"`
	Image       string  `json:"image"`
	Published   bool    `json:"published"`
	Archived    bool    `json:"archived"`
	LowStock    bool    `json:"lowStock"`
	UpdatedAt   int64   `json:"updatedAt"`
}

func toProductJSON(p Product) productJSON {
	return productJSON{
		ID: p.ID, Name: p.Name, SKU: p.SKU, Category: p.Category, Unit: p.Unit,
		Price: p.Price, Cost: p.Cost, Stock: p.Stock, MinStock: p.MinStock,
		Barcode: p.Barcode, Description: p.Description, Image: p.Image,
		Published: p.Published, Archived: p.Archived, LowStock: p.IsLowStock(),
		UpdatedAt: p.UpdatedAt,
	}
}

type clientJSON struct {
	ID         int64   `json:"id"`
	Phone      string  `json:"phone"`
	Email      string  `json:"email"`
	LastName   string  `json:"lastName"`
	FirstName  string  `json:"firstName"`
	MiddleName string  `json:"middleName"`
	FullName   string  `json:"fullName"`
	Address    string  `json:"address"`
	Segment    string  `json:"segment"`
	Source     string  `json:"source"`
	Note       string  `json:"note"`
	Archived   bool    `json:"archived"`
	Blocked    bool    `json:"blocked"`
	Total      float64 `json:"ordersTotal"`
	Count      int     `json:"ordersCount"`
	CreatedAt  int64   `json:"createdAt"`
	UpdatedAt  int64   `json:"updatedAt"`
}

func toClientJSON(c Client, withStats bool) clientJSON {
	j := clientJSON{
		ID: c.ID, Phone: c.Phone, Email: c.Email, LastName: c.LastName,
		FirstName: c.FirstName, MiddleName: c.MiddleName, FullName: c.FullName(),
		Address: c.Address, Segment: c.Segment, Source: c.Source, Note: c.Note,
		Archived: c.Archived, Blocked: c.Blocked,
		CreatedAt: c.CreatedAt, UpdatedAt: c.UpdatedAt,
	}
	if withStats {
		j.Total, j.Count = clientStats(c.ID)
	}
	return j
}

type orderItemJSON struct {
	// ID позиции — по нему приложение правит количество в составе заказа.
	ID        int64   `json:"id"`
	ProductID *int64  `json:"productId"`
	Name      string  `json:"name"`
	Unit      string  `json:"unit"`
	Qty       float64 `json:"qty"`
	UnitPrice float64 `json:"unitPrice"`
	UnitCost  float64 `json:"unitCost"`
	Sum       float64 `json:"sum"`
}

type orderJSON struct {
	ID         int64           `json:"id"`
	Number     string          `json:"number"`
	ClientID   *int64          `json:"clientId"`
	ClientName string          `json:"clientName"`
	Phone      string          `json:"phone"`
	Email      string          `json:"email"`
	Address    string          `json:"address"`
	Comment    string          `json:"comment"`
	Delivery   string          `json:"delivery"`
	PayMethod  string          `json:"payMethod"`
	Status     int             `json:"status"`
	StatusName string          `json:"statusName"`
	Total      float64         `json:"total"`
	Profit     float64         `json:"profit"`
	Source     string          `json:"source"`
	CreatedAt  int64           `json:"createdAt"`
	Items      []orderItemJSON `json:"items"`
}

func toOrderJSON(o Order) orderJSON {
	items := make([]orderItemJSON, 0, len(o.Items))
	for _, it := range o.Items {
		items = append(items, orderItemJSON{
			ID: it.ID, ProductID: it.ProductID, Name: it.Name, Unit: it.Unit, Qty: it.Qty,
			UnitPrice: it.UnitPrice, UnitCost: it.UnitCost, Sum: it.Sum(),
		})
	}
	return orderJSON{
		ID: o.ID, Number: o.Number(), ClientID: o.ClientID, ClientName: o.ClientName,
		Phone: o.Phone, Email: o.Email, Address: o.Address, Comment: o.Comment,
		Delivery: o.Delivery, PayMethod: o.PayMethod, Status: o.Status,
		StatusName: o.StatusName(), Total: o.Total, Profit: o.Profit(),
		Source: o.Source, CreatedAt: o.CreatedAt, Items: items,
	}
}

// ------------------------------------------------------------ покупатель

func apiRegister(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Phone      string `json:"phone"`
		Password   string `json:"password"`
		Email      string `json:"email"`
		LastName   string `json:"lastName"`
		FirstName  string `json:"firstName"`
		MiddleName string `json:"middleName"`
		Address    string `json:"address"`
	}
	if !readJSON(w, r, &in) {
		return
	}
	phone := normalizePhone(in.Phone)
	if !validPhone(phone) {
		apiErr(w, http.StatusBadRequest, "Некорректный телефон")
		return
	}
	if len([]rune(in.Password)) < 5 {
		apiErr(w, http.StatusBadRequest, "Пароль короче 5 символов")
		return
	}
	if _, err := getClientByPhone(phone); err == nil {
		apiErr(w, http.StatusConflict, "Телефон уже зарегистрирован")
		return
	}
	hash, err := hashPassword(in.Password)
	if err != nil {
		apiErr(w, http.StatusInternalServerError, "Ошибка сервера")
		return
	}
	c := Client{
		Phone: phone, PasswordHash: hash, Email: in.Email, LastName: in.LastName,
		FirstName: in.FirstName, MiddleName: in.MiddleName, Address: in.Address,
		Segment: "Новый", Source: "Приложение",
	}
	if err := insertClient(&c); err != nil {
		apiErr(w, http.StatusInternalServerError, "Не удалось создать аккаунт")
		return
	}
	tok, err := createSession("client", c.ID)
	if err != nil {
		apiErr(w, http.StatusInternalServerError, "Ошибка сессии")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok": true, "token": tok, "client": toClientJSON(c, false),
	})
}

func apiLogin(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Phone    string `json:"phone"`
		Password string `json:"password"`
	}
	if !readJSON(w, r, &in) {
		return
	}
	c, err := getClientByPhone(normalizePhone(in.Phone))
	if err != nil || c.PasswordHash == "" || !checkPassword(in.Password, c.PasswordHash) {
		apiErr(w, http.StatusUnauthorized, "Неверный телефон или пароль")
		return
	}
	if c.Blocked {
		apiErr(w, http.StatusForbidden, "Аккаунт заблокирован. Свяжитесь с магазином.")
		return
	}
	tok, err := createSession("client", c.ID)
	if err != nil {
		apiErr(w, http.StatusInternalServerError, "Ошибка сессии")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok": true, "token": tok, "client": toClientJSON(c, true),
	})
}

func apiMe(w http.ResponseWriter, r *http.Request) {
	c, ok := apiClient(r)
	if !ok {
		apiErr(w, http.StatusUnauthorized, "Нужен вход")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "client": toClientJSON(c, true)})
}

func apiProducts(w http.ResponseWriter, r *http.Request) {
	products, err := listPublishedProducts(
		strings.TrimSpace(r.URL.Query().Get("cat")),
		strings.TrimSpace(r.URL.Query().Get("q")))
	if err != nil {
		apiErr(w, http.StatusInternalServerError, "Ошибка базы")
		return
	}
	out := make([]productJSON, 0, len(products))
	for _, p := range products {
		j := toProductJSON(p)
		j.Cost = 0 // себестоимость покупателю не показываем
		out = append(out, j)
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "products": out})
}

func apiCategories(w http.ResponseWriter, r *http.Request) {
	cats, err := listCategories()
	if err != nil {
		apiErr(w, http.StatusInternalServerError, "Ошибка базы")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "categories": cats})
}

func apiMyOrders(w http.ResponseWriter, r *http.Request) {
	c, ok := apiClient(r)
	if !ok {
		apiErr(w, http.StatusUnauthorized, "Нужен вход")
		return
	}
	orders, err := listOrdersByClient(c.ID)
	if err != nil {
		apiErr(w, http.StatusInternalServerError, "Ошибка базы")
		return
	}
	out := make([]orderJSON, 0, len(orders))
	for _, o := range orders {
		o.Items, _ = listOrderItems(o.ID)
		j := toOrderJSON(o)
		j.Profit = 0 // прибыль — внутренние данные
		for i := range j.Items {
			j.Items[i].UnitCost = 0
		}
		out = append(out, j)
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "orders": out})
}

func apiCreateOrder(w http.ResponseWriter, r *http.Request) {
	c, ok := apiClient(r)
	if !ok {
		apiErr(w, http.StatusUnauthorized, "Нужен вход")
		return
	}
	var in struct {
		Address   string `json:"address"`
		Comment   string `json:"comment"`
		Delivery  string `json:"delivery"`
		PayMethod string `json:"payMethod"`
		Items     []struct {
			ProductID int64   `json:"productId"`
			Qty       float64 `json:"qty"`
		} `json:"items"`
	}
	if !readJSON(w, r, &in) {
		return
	}
	if len(in.Items) == 0 {
		apiErr(w, http.StatusBadRequest, "Пустой заказ")
		return
	}

	lines := make([]CartLine, 0, len(in.Items))
	for _, it := range in.Items {
		p, err := getProduct(it.ProductID)
		if err != nil || p.Archived || !p.Published {
			apiErr(w, http.StatusBadRequest, "Товар недоступен: "+strconv.FormatInt(it.ProductID, 10))
			return
		}
		qty := it.Qty
		if qty <= 0 {
			apiErr(w, http.StatusBadRequest, "Некорректное количество для «"+p.Title()+"»")
			return
		}
		// Остаток заказ не ограничивает — как и на витрине: чего нет на складе,
		// магазин доготовит. Склад при списании просто уходит в минус.
		lines = append(lines, CartLine{Product: p, Qty: qty})
	}

	addr := in.Address
	if addr == "" {
		addr = c.Address
	}
	delivery := in.Delivery
	if delivery == "" {
		delivery = "Доставка"
	}
	payMethod := in.PayMethod
	if payMethod == "" {
		payMethod = "Наличные при получении"
	}
	o := Order{
		ClientID: &c.ID, ClientName: c.FullName(), Phone: c.Phone, Email: c.Email,
		Address: addr, Comment: in.Comment, Delivery: delivery, PayMethod: payMethod,
		Status: StatusNew,
	}
	if err := createOrder(&o, lines); err != nil {
		apiErr(w, http.StatusInternalServerError, "Не удалось создать заказ")
		return
	}
	o.Items, _ = listOrderItems(o.ID)
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "order": toOrderJSON(o)})
}

// ----------------------------------------------------------- CRM-раздел

func apiCrmProducts(w http.ResponseWriter, r *http.Request) {
	products, err := listAllProducts()
	if err != nil {
		apiErr(w, http.StatusInternalServerError, "Ошибка базы")
		return
	}
	out := make([]productJSON, 0, len(products))
	for _, p := range products {
		out = append(out, toProductJSON(p))
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "products": out})
}

// apiCrmSaveProduct создаёт товар (id = 0) или обновляет существующий.
// Так приложение может править ассортимент сайта прямо с телефона.
func apiCrmSaveProduct(w http.ResponseWriter, r *http.Request) {
	// Image — указатель: null означает «поле не прислали, оставить как было»,
	// а пустая строка — «убрать фотографию».
	var in struct {
		productJSON
		Image *string `json:"image"`
	}
	if !readJSON(w, r, &in) {
		return
	}
	var p Product
	isNew := in.ID <= 0
	if !isNew {
		var err error
		if p, err = getProduct(in.ID); err != nil {
			apiErr(w, http.StatusNotFound, "Товар не найден")
			return
		}
	}
	p.Name, p.SKU, p.Category, p.Unit = in.Name, in.SKU, in.Category, in.Unit
	if p.Unit == "" {
		p.Unit = "шт"
	}
	p.Price, p.Cost, p.Stock, p.MinStock = in.Price, in.Cost, in.Stock, in.MinStock
	p.Barcode, p.Description = in.Barcode, in.Description
	p.Published, p.Archived = in.Published, in.Archived
	if in.Image != nil {
		p.Image = *in.Image
	}
	if strings.TrimSpace(p.Name) == "" {
		apiErr(w, http.StatusBadRequest, "Пустое название")
		return
	}

	var err error
	if isNew {
		err = insertProduct(&p)
	} else {
		err = updateProduct(&p)
	}
	if err != nil {
		apiErr(w, http.StatusInternalServerError, "Не удалось сохранить")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "product": toProductJSON(p)})
}

func apiCrmClients(w http.ResponseWriter, r *http.Request) {
	clients, err := listClients(strings.TrimSpace(r.URL.Query().Get("q")))
	if err != nil {
		apiErr(w, http.StatusInternalServerError, "Ошибка базы")
		return
	}
	out := make([]clientJSON, 0, len(clients))
	for _, c := range clients {
		out = append(out, toClientJSON(c, true))
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "clients": out})
}

func apiCrmOrders(w http.ResponseWriter, r *http.Request) {
	status := -1
	if s := r.URL.Query().Get("status"); s != "" {
		if v, err := strconv.Atoi(s); err == nil {
			status = v
		}
	}
	orders, err := listOrders(status, strings.TrimSpace(r.URL.Query().Get("q")))
	if err != nil {
		apiErr(w, http.StatusInternalServerError, "Ошибка базы")
		return
	}
	out := make([]orderJSON, 0, len(orders))
	for _, o := range orders {
		o.Items, _ = listOrderItems(o.ID)
		out = append(out, toOrderJSON(o))
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "orders": out})
}

// apiCrmCreateOrder — продажа, заведённая в приложении, становится заказом на сайте.
// Цены и себестоимость приходят снимком на момент сделки, дата — своя (продажа
// могла быть вчера). Склад списывается сразу, как и при заказе с витрины: продали —
// значит товара стало меньше. Поправить остаток можно кнопкой в карточке заказа.
func apiCrmCreateOrder(w http.ResponseWriter, r *http.Request) {
	var in struct {
		ClientID   int64  `json:"clientId"`
		ClientName string `json:"clientName"`
		Phone      string `json:"phone"`
		Email      string `json:"email"`
		Address    string `json:"address"`
		Comment    string `json:"comment"`
		Delivery   string `json:"delivery"`
		PayMethod  string `json:"payMethod"`
		Status     int    `json:"status"`
		CreatedAt  int64  `json:"createdAt"`
		Items      []struct {
			ProductID int64   `json:"productId"`
			Name      string  `json:"name"`
			Unit      string  `json:"unit"`
			Qty       float64 `json:"qty"`
			UnitPrice float64 `json:"unitPrice"`
			UnitCost  float64 `json:"unitCost"`
		} `json:"items"`
	}
	if !readJSON(w, r, &in) {
		return
	}
	if len(in.Items) == 0 {
		apiErr(w, http.StatusBadRequest, "Пустая продажа")
		return
	}
	if in.Status < 0 || in.Status >= len(StatusNames) {
		apiErr(w, http.StatusBadRequest, "Неизвестный статус")
		return
	}

	items := make([]OrderItem, 0, len(in.Items))
	for _, it := range in.Items {
		if it.Qty <= 0 {
			apiErr(w, http.StatusBadRequest, "Количество должно быть больше нуля")
			return
		}
		item := OrderItem{
			Name: strings.TrimSpace(it.Name), Unit: it.Unit,
			Qty: it.Qty, UnitPrice: it.UnitPrice, UnitCost: it.UnitCost,
		}
		if item.Unit == "" {
			item.Unit = "шт"
		}
		// Товар может быть заведён только в телефоне — тогда позиция без ссылки,
		// с одним лишь названием, и склад по ней не двигаем.
		if it.ProductID > 0 {
			if p, err := getProduct(it.ProductID); err == nil {
				id := p.ID
				item.ProductID = &id
				if item.Name == "" {
					item.Name = p.Name
				}
			}
		}
		if item.Name == "" {
			apiErr(w, http.StatusBadRequest, "У позиции нет ни товара, ни названия")
			return
		}
		items = append(items, item)
	}

	o := Order{
		ClientName: strings.TrimSpace(in.ClientName),
		Phone:      strings.TrimSpace(in.Phone),
		Email:      strings.TrimSpace(in.Email),
		Address:    strings.TrimSpace(in.Address),
		Comment:    strings.TrimSpace(in.Comment),
		Delivery:   in.Delivery,
		PayMethod:  in.PayMethod,
		Status:     in.Status,
		Source:     sourceApp,
		CreatedAt:  in.CreatedAt,
	}
	if o.Delivery == "" {
		o.Delivery = "Продажа на месте"
	}
	if o.PayMethod == "" {
		o.PayMethod = "Наличные"
	}
	if in.ClientID > 0 {
		if c, err := getClient(in.ClientID); err == nil {
			id := c.ID
			o.ClientID = &id
			if o.ClientName == "" {
				o.ClientName = c.FullName()
			}
			if o.Phone == "" {
				o.Phone = c.Phone
			}
		}
	}
	// Отменённая продажа склад не трогает — возвращать потом будет нечего.
	writeOff := in.Status != StatusCanceled
	if err := createOrderItems(&o, items, writeOff); err != nil {
		logErr("создание заказа из приложения", err)
		apiErr(w, http.StatusInternalServerError, "Не удалось сохранить продажу")
		return
	}
	full, err := getOrder(o.ID)
	if err != nil {
		apiErr(w, http.StatusInternalServerError, "Заказ создан, но не читается")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "order": toOrderJSON(full)})
}

func apiCrmOrderStatus(w http.ResponseWriter, r *http.Request) {
	var in struct {
		ID     int64 `json:"id"`
		Status int   `json:"status"`
	}
	if !readJSON(w, r, &in) {
		return
	}
	if in.Status < 0 || in.Status >= len(StatusNames) {
		apiErr(w, http.StatusBadRequest, "Неизвестный статус")
		return
	}
	if err := setOrderStatus(in.ID, in.Status); err != nil {
		apiErr(w, http.StatusInternalServerError, "Не удалось сменить статус")
		return
	}
	o, err := getOrder(in.ID)
	if err != nil {
		apiErr(w, http.StatusNotFound, "Заказ не найден")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "order": toOrderJSON(o)})
}

// apiCrmMergeOrders сводит несколько заказов одного покупателя в один.
//
// Новые заказы объединяются сами при оформлении (см. merge.go), а этот запрос
// нужен приложению для старых дублей, накопившихся раньше: оно присылает id
// заказов с одинаковым телефоном и получает обратно один общий заказ.
func apiCrmMergeOrders(w http.ResponseWriter, r *http.Request) {
	var in struct {
		IDs []int64 `json:"ids"`
	}
	if !readJSON(w, r, &in) {
		return
	}
	o, err := mergeOrders(in.IDs)
	switch {
	case err == nil:
	case errors.Is(err, errMergeNotAllowed):
		apiErr(w, http.StatusConflict,
			"Объединить можно только заказы одного покупателя, которые ещё в работе")
		return
	case errors.Is(err, sql.ErrNoRows):
		apiErr(w, http.StatusNotFound, "Заказ не найден")
		return
	default:
		apiErr(w, http.StatusInternalServerError, "Не удалось объединить заказы")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "order": toOrderJSON(o)})
}

// apiCrmOrderClient привязывает заявку к карточке клиента.
//
// Нужно для заявок из MAX без телефона: человек написал «привезите как
// обычно», бот его не опознал и оставил в комментарии подсказку. Продавец
// смотрит, узнаёт покупателя и связывает заявку с карточкой — телефон и
// адрес подтягиваются оттуда.
//
// Заодно бот запоминает, чей это MAX (см. rememberLeadClient): со следующего
// раза этот человек опознаётся по своему ID, без всяких имён и подсказок.
// Поэтому каждая такая правка продавца делает бота точнее.
func apiCrmOrderClient(w http.ResponseWriter, r *http.Request) {
	var in struct {
		OrderID  int64 `json:"orderId"`
		ClientID int64 `json:"clientId"`
	}
	if !readJSON(w, r, &in) {
		return
	}
	o, err := getOrder(in.OrderID)
	if err != nil {
		apiErr(w, http.StatusNotFound, "Заказ не найден")
		return
	}
	c, err := getClient(in.ClientID)
	if err != nil {
		apiErr(w, http.StatusNotFound, "Клиент не найден")
		return
	}

	// Телефон и адрес берём из карточки, но записанные в заявке не затираем:
	// покупатель мог назвать другой адрес именно для этого заказа.
	phone := o.Phone
	if strings.TrimSpace(phone) == "" {
		phone = c.Phone
	}
	address := o.Address
	if strings.TrimSpace(address) == "" {
		address = c.Address
	}
	if _, err := db.Exec(`UPDATE orders SET client_id = ?, client_name = ?,
      phone = ?, address = ?, updated_at = ? WHERE id = ?`,
		c.ID, c.FullName(), phone, address, nowMs(), o.ID); err != nil {
		logErr("привязка клиента к заказу", err)
		apiErr(w, http.StatusInternalServerError, "Не удалось привязать клиента")
		return
	}
	rememberOrderClient(o, c.ID)

	full, err := getOrder(o.ID)
	if err != nil {
		apiErr(w, http.StatusInternalServerError, "Клиент привязан, но заказ не читается")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "order": toOrderJSON(full)})
}

// apiCrmOrderItem меняет количество в позиции заказа, а qty = 0 убирает позицию.
// Так продавец правит заказ, когда покупатель по телефону просит меньше или
// отказывается от товара. Разницу сервер сам возвращает на склад.
func apiCrmOrderItem(w http.ResponseWriter, r *http.Request) {
	var in struct {
		OrderID int64   `json:"orderId"`
		ItemID  int64   `json:"itemId"`
		Qty     float64 `json:"qty"`
	}
	if !readJSON(w, r, &in) {
		return
	}
	if in.OrderID <= 0 || in.ItemID <= 0 {
		apiErr(w, http.StatusBadRequest, "Не указан заказ или позиция")
		return
	}
	if in.Qty > 1000 {
		apiErr(w, http.StatusBadRequest, "Слишком большое количество")
		return
	}

	switch err := setOrderItemQty(in.OrderID, in.ItemID, roundQty(in.Qty)); {
	case err == nil:
	case errors.Is(err, sql.ErrNoRows):
		apiErr(w, http.StatusNotFound, "Позиция не найдена")
		return
	case errors.Is(err, errOrderCanceled):
		apiErr(w, http.StatusConflict,
			"Заказ отменён — товар уже вернулся на склад, править состав нельзя")
		return
	case errors.Is(err, errLastItem):
		apiErr(w, http.StatusConflict,
			"Это последняя позиция. Чтобы отказаться от заказа целиком, смените статус на «Отменён»")
		return
	default:
		apiErr(w, http.StatusInternalServerError, "Не удалось изменить состав заказа")
		return
	}

	o, err := getOrder(in.OrderID)
	if err != nil {
		apiErr(w, http.StatusNotFound, "Заказ не найден")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "order": toOrderJSON(o)})
}

// apiCrmOrderItemAdd дописывает товар в заказ — покупатель решил взять ещё.
func apiCrmOrderItemAdd(w http.ResponseWriter, r *http.Request) {
	var in struct {
		OrderID   int64   `json:"orderId"`
		ProductID int64   `json:"productId"`
		Qty       float64 `json:"qty"`
	}
	if !readJSON(w, r, &in) {
		return
	}
	if in.OrderID <= 0 || in.ProductID <= 0 {
		apiErr(w, http.StatusBadRequest, "Не указан заказ или товар")
		return
	}
	if in.Qty <= 0 || in.Qty > 1000 {
		apiErr(w, http.StatusBadRequest, "Некорректное количество")
		return
	}

	switch err := addOrderItem(in.OrderID, in.ProductID, roundQty(in.Qty)); {
	case err == nil:
	case errors.Is(err, sql.ErrNoRows):
		apiErr(w, http.StatusNotFound, "Заказ или товар не найден")
		return
	case errors.Is(err, errOrderCanceled):
		apiErr(w, http.StatusConflict, "Заказ отменён — добавлять в него товар нельзя")
		return
	default:
		apiErr(w, http.StatusInternalServerError, "Не удалось добавить товар в заказ")
		return
	}

	o, err := getOrder(in.OrderID)
	if err != nil {
		apiErr(w, http.StatusNotFound, "Заказ не найден")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "order": toOrderJSON(o)})
}

// apiCrmDeleteOrder удаляет один заказ — из приложения это делают по
// отменённым и по ошибочным заявкам, которые бот завёл из переписки.
// Копия заказа перед удалением сохраняется рядом с базой.
func apiCrmDeleteOrder(w http.ResponseWriter, r *http.Request) {
	var in struct {
		ID int64 `json:"id"`
	}
	if !readJSON(w, r, &in) {
		return
	}
	if in.ID <= 0 {
		apiErr(w, http.StatusBadRequest, "Не указан заказ")
		return
	}
	switch err := removeOrder(in.ID, filepath.Dir(dbFile)); {
	case err == nil:
	case errors.Is(err, sql.ErrNoRows):
		apiErr(w, http.StatusNotFound, "Заказ не найден")
		return
	default:
		logErr("удаление заказа", err)
		apiErr(w, http.StatusInternalServerError, "Не удалось удалить заказ")
		return
	}
	var left int
	db.QueryRow(`SELECT COUNT(*) FROM orders`).Scan(&left)
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "left": left})
}

// apiCrmPurgeOrders удаляет заказы: только выполненные или все.
// Это чистка архива, поэтому склад не двигаем — товар покупателям уже отдан.
// Перед удалением сервер сам сохраняет заказы в файл рядом с базой.
func apiCrmPurgeOrders(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Scope string `json:"scope"` // «done» — выполненные, «all» — все
	}
	if !readJSON(w, r, &in) {
		return
	}
	onlyDone := in.Scope != "all"
	if in.Scope != "all" && in.Scope != "done" {
		apiErr(w, http.StatusBadRequest, "Непонятно, что удалять")
		return
	}

	deleted, err := purgeOrders(onlyDone, filepath.Dir(dbFile))
	if err != nil {
		logErr("удаление заказов", err)
		apiErr(w, http.StatusInternalServerError, "Не удалось удалить заказы")
		return
	}
	var left int
	db.QueryRow(`SELECT COUNT(*) FROM orders`).Scan(&left)
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "deleted": deleted, "left": left})
}

// apiCrmPing — приложение проверяет им адрес сервера и ключ в настройках.
func apiCrmPing(w http.ResponseWriter, r *http.Request) {
	var products, clients, orders int
	db.QueryRow(`SELECT COUNT(*) FROM products`).Scan(&products)
	db.QueryRow(`SELECT COUNT(*) FROM clients`).Scan(&clients)
	db.QueryRow(`SELECT COUNT(*) FROM orders`).Scan(&orders)
	writeJSON(w, http.StatusOK, map[string]any{
		"ok": true, "shop": shopName(), "version": version, "time": nowMs(),
		"products": products, "clients": clients, "orders": orders,
	})
}

// apiCrmSaveClient создаёт или обновляет карточку клиента из приложения.
// Пароль отсюда не меняется — его владелец аккаунта задаёт сам на сайте.
// Архив и блокировку принимаем только явными полями: старые версии приложения
// их не присылают, и молча сбрасывать их нельзя.
func apiCrmSaveClient(w http.ResponseWriter, r *http.Request) {
	var in struct {
		clientJSON
		Archived *bool `json:"archived"`
		Blocked  *bool `json:"blocked"`
	}
	if !readJSON(w, r, &in) {
		return
	}
	phone := normalizePhone(in.Phone)
	if !validPhone(phone) {
		apiErr(w, http.StatusBadRequest, "Некорректный телефон")
		return
	}

	var c Client
	if in.ID > 0 {
		var err error
		if c, err = getClient(in.ID); err != nil {
			apiErr(w, http.StatusNotFound, "Клиент не найден")
			return
		}
	} else if existing, err := getClientByPhone(phone); err == nil {
		c = existing // по телефону нашли — обновляем, а не плодим дубли
	}

	c.Phone, c.Email = phone, in.Email
	c.LastName, c.FirstName, c.MiddleName = in.LastName, in.FirstName, in.MiddleName
	c.Address, c.Note = in.Address, in.Note
	if in.Segment != "" {
		c.Segment = in.Segment
	}
	if in.Source != "" {
		c.Source = in.Source
	} else if c.Source == "" {
		c.Source = "Приложение"
	}
	wasBlocked := c.Blocked
	if in.Archived != nil {
		c.Archived = *in.Archived
	}
	if in.Blocked != nil {
		c.Blocked = *in.Blocked
	}

	var err error
	if c.ID > 0 {
		err = updateClient(&c)
	} else {
		err = insertClient(&c)
	}
	if err != nil {
		apiErr(w, http.StatusInternalServerError, "Не удалось сохранить клиента")
		return
	}
	// Заблокировали с телефона — выкидываем сессии, как это делает админка.
	if c.Blocked && !wasBlocked {
		if _, err := db.Exec(`DELETE FROM sessions WHERE kind='client' AND subject_id=?`, c.ID); err != nil {
			logErr("блокировка клиента", err)
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "client": toClientJSON(c, true)})
}

// apiCrmClientDelete — приложение удалило клиента у себя и просит убрать его и на сайте.
// apiCrmClientPassword задаёт покупателю новый пароль от личного кабинета.
//
// Так продавец помогает тому, кто пароль забыл: почты и СМС у магазина нет,
// поэтому покупатель звонит, а продавец из приложения назначает новый пароль
// и диктует его. Пустое поле — «придумай сам»: готовый пароль вернётся в
// ответе, его и называют покупателю.
func apiCrmClientPassword(w http.ResponseWriter, r *http.Request) {
	var in struct {
		ID       int64  `json:"id"`
		Password string `json:"password"`
	}
	if !readJSON(w, r, &in) {
		return
	}
	c, err := getClient(in.ID)
	if err != nil {
		apiErr(w, http.StatusNotFound, "Клиент не найден")
		return
	}
	pw, err := setClientPassword(c.ID, in.Password)
	switch {
	case errors.Is(err, errWeakPassword):
		apiErr(w, http.StatusBadRequest,
			"Пароль короче "+strconv.Itoa(minPasswordLen)+" символов")
		return
	case err != nil:
		logErr("смена пароля покупателя", err)
		apiErr(w, http.StatusInternalServerError, "Не удалось сохранить пароль")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "password": pw})
}

func apiCrmClientDelete(w http.ResponseWriter, r *http.Request) {
	var in struct {
		ID int64 `json:"id"`
	}
	if !readJSON(w, r, &in) {
		return
	}
	if in.ID <= 0 {
		apiErr(w, http.StatusBadRequest, "Не указан клиент")
		return
	}
	if _, err := getClient(in.ID); err != nil {
		apiErr(w, http.StatusNotFound, "Клиент не найден")
		return
	}
	if err := deleteClient(in.ID); err != nil {
		logErr("удаление клиента", err)
		apiErr(w, http.StatusInternalServerError, "Не удалось удалить клиента")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

// apiCrmUploadPhoto принимает фотографию товара с телефона (multipart, поле «photo»)
// и возвращает путь, который потом кладётся в поле image товара.
func apiCrmUploadPhoto(w http.ResponseWriter, r *http.Request) {
	if err := r.ParseMultipartForm(12 << 20); err != nil {
		apiErr(w, http.StatusBadRequest, "Ожидается multipart-форма с полем photo")
		return
	}
	url, err := saveUpload(r, "photo")
	if err != nil {
		apiErr(w, http.StatusInternalServerError, "Не удалось сохранить файл")
		return
	}
	if url == "" {
		apiErr(w, http.StatusBadRequest, "Файл не принят: нужен jpg, png, webp или gif")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "image": url})
}

// apiCrmAdminInfo — кто сейчас администратор сайта.
func apiCrmAdminInfo(w http.ResponseWriter, r *http.Request) {
	var a Admin
	err := db.QueryRow(`SELECT id,login,password_hash,name,created_at FROM admins
      ORDER BY id LIMIT 1`).Scan(&a.ID, &a.Login, &a.PasswordHash, &a.Name, &a.CreatedAt)
	if err != nil {
		apiErr(w, http.StatusNotFound, "Администратор ещё не создан")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok": true,
		"admin": map[string]any{
			"id": a.ID, "login": a.Login, "name": a.Name, "createdAt": a.CreatedAt,
		},
	})
}

// apiCrmAdminPassword меняет логин и пароль от панели управления сайтом.
// Старый пароль спрашиваем, чтобы потерянный телефон с ключом не давал
// сразу же угнать админку.
func apiCrmAdminPassword(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Login       string `json:"login"`
		OldPassword string `json:"oldPassword"`
		NewPassword string `json:"newPassword"`
	}
	if !readJSON(w, r, &in) {
		return
	}
	if len([]rune(in.NewPassword)) < 8 {
		apiErr(w, http.StatusBadRequest, "Новый пароль короче 8 символов")
		return
	}

	var a Admin
	err := db.QueryRow(`SELECT id,login,password_hash,name,created_at FROM admins
      ORDER BY id LIMIT 1`).Scan(&a.ID, &a.Login, &a.PasswordHash, &a.Name, &a.CreatedAt)
	if err != nil {
		apiErr(w, http.StatusNotFound, "Администратор ещё не создан")
		return
	}
	if !checkPassword(in.OldPassword, a.PasswordHash) {
		apiErr(w, http.StatusForbidden, "Текущий пароль указан неверно")
		return
	}

	hash, err := hashPassword(in.NewPassword)
	if err != nil {
		apiErr(w, http.StatusInternalServerError, "Ошибка сервера")
		return
	}
	login := strings.TrimSpace(in.Login)
	if login == "" {
		login = a.Login
	}
	if _, err := db.Exec(`UPDATE admins SET login = ?, password_hash = ? WHERE id = ?`,
		login, hash, a.ID); err != nil {
		apiErr(w, http.StatusInternalServerError, "Не удалось сменить пароль")
		return
	}
	// Старые входы в панель разлогиниваем — пароль ведь уже другой.
	db.Exec(`DELETE FROM sessions WHERE kind = 'admin'`)

	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "login": login})
}

// ------------------------------------------------ тексты витрины и новости

// apiCrmSite отдаёт приложению всё, что показано покупателю в контактах,
// в верхней полоске и бегущей строке — вместе с неопубликованными новостями.
func apiCrmSite(w http.ResponseWriter, r *http.Request) {
	news, err := listNews(false)
	if err != nil {
		apiErr(w, http.StatusInternalServerError, "Не удалось прочитать новости")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok": true, "site": siteText(), "news": news,
	})
}

func apiCrmSaveSite(w http.ResponseWriter, r *http.Request) {
	var in SiteText
	if !readJSON(w, r, &in) {
		return
	}
	if err := saveSiteText(in); err != nil {
		apiErr(w, http.StatusInternalServerError, "Не удалось сохранить тексты")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "site": siteText()})
}

// apiCrmSaveNews создаёт новость (id = 0) или правит существующую.
func apiCrmSaveNews(w http.ResponseWriter, r *http.Request) {
	var in NewsItem
	if !readJSON(w, r, &in) {
		return
	}
	if strings.TrimSpace(in.Text) == "" {
		apiErr(w, http.StatusBadRequest, "Пустая новость")
		return
	}
	if err := saveNews(&in); err != nil {
		apiErr(w, http.StatusInternalServerError, "Не удалось сохранить новость")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "news": in})
}

func apiCrmDeleteNews(w http.ResponseWriter, r *http.Request) {
	var in struct {
		ID int64 `json:"id"`
	}
	if !readJSON(w, r, &in) {
		return
	}
	if err := deleteNews(in.ID); err != nil {
		apiErr(w, http.StatusNotFound, "Новость не найдена")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func apiCrmStats(w http.ResponseWriter, r *http.Request) {
	s, _ := dashboardStats()
	top, _ := topProducts(10)
	low, _ := lowStockProducts()
	lowOut := make([]productJSON, 0, len(low))
	for _, p := range low {
		lowOut = append(lowOut, toProductJSON(p))
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok": true,
		"stats": map[string]any{
			"revenue": s.Revenue, "profit": s.Profit, "ordersTotal": s.OrdersTotal,
			"ordersNew": s.OrdersNew, "clients": s.Clients, "products": s.Products,
			"stockValue": s.StockValue, "lowStock": s.LowStock, "avgCheck": s.AvgCheck,
		},
		"top": top, "lowStockProducts": lowOut,
	})
}
