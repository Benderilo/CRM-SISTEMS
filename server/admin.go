package main

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

func registerAdmin(mux *http.ServeMux) {
	mux.HandleFunc("GET /admin/login", handleAdminLoginForm)
	mux.HandleFunc("POST /admin/login", handleAdminLoginSubmit)
	mux.HandleFunc("POST /admin/logout", handleAdminLogout)

	mux.Handle("GET /admin/{$}", requireAdmin(handleAdminDashboard))

	mux.Handle("GET /admin/orders", requireAdmin(handleAdminOrders))
	mux.Handle("GET /admin/orders/{id}", requireAdmin(handleAdminOrderView))
	mux.Handle("POST /admin/orders/{id}/status", requireAdmin(handleAdminOrderStatus))
	mux.Handle("POST /admin/orders/{id}/stock", requireAdmin(handleAdminOrderStock))
	mux.Handle("POST /admin/orders/{id}/delete", requireAdmin(handleAdminOrderDelete))

	mux.Handle("GET /admin/products", requireAdmin(handleAdminProducts))
	mux.Handle("GET /admin/products/new", requireAdmin(handleAdminProductNew))
	mux.Handle("GET /admin/products/{id}", requireAdmin(handleAdminProductEdit))
	mux.Handle("POST /admin/products/save", requireAdmin(handleAdminProductSave))
	mux.Handle("POST /admin/products/{id}/delete", requireAdmin(handleAdminProductDelete))

	mux.Handle("GET /admin/stock", requireAdmin(handleAdminStock))
	mux.Handle("POST /admin/stock", requireAdmin(handleAdminStockSave))

	mux.Handle("GET /admin/clients", requireAdmin(handleAdminClients))
	mux.Handle("GET /admin/clients/{id}", requireAdmin(handleAdminClientView))
	mux.Handle("POST /admin/clients/{id}", requireAdmin(handleAdminClientSave))
	mux.Handle("POST /admin/clients/{id}/block", requireAdmin(handleAdminClientBlock))
	mux.Handle("POST /admin/clients/{id}/password", requireAdmin(handleAdminClientPassword))
	mux.Handle("POST /admin/clients/{id}/delete", requireAdmin(handleAdminClientDelete))
}

// adminHandler — обработчик, которому уже известен вошедший сотрудник.
type adminHandler func(http.ResponseWriter, *http.Request, Admin)

// requireAdmin закрывает страницу от посторонних.
func requireAdmin(h adminHandler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		a, ok := currentAdmin(r)
		if !ok {
			http.Redirect(w, r, "/admin/login?next="+r.URL.Path, http.StatusSeeOther)
			return
		}
		h(w, r, a)
	})
}

// renderAdmin рисует страницу админки.
func renderAdmin(w http.ResponseWriter, r *http.Request, a Admin, name string, p page) {
	p.Admin = &a
	p.Site = siteText()
	if p.Title == "" {
		p.Title = "Панель управления"
	}
	t, ok := tpl["admin/"+name]
	if !ok {
		http.Error(w, "Шаблон не найден: "+name, http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	if err := t.ExecuteTemplate(w, "layout", p); err != nil {
		logErr("шаблон admin/"+name, err)
	}
}

// ------------------------------------------------------------ вход

func handleAdminLoginForm(w http.ResponseWriter, r *http.Request) {
	if _, ok := currentAdmin(r); ok {
		http.Redirect(w, r, "/admin", http.StatusSeeOther)
		return
	}
	renderAdmin(w, r, Admin{}, "login.html", page{Title: "Вход в панель управления"})
}

func handleAdminLoginSubmit(w http.ResponseWriter, r *http.Request) {
	login := strings.TrimSpace(r.FormValue("login"))
	pw := r.FormValue("password")

	var a Admin
	err := db.QueryRow(`SELECT id,login,password_hash,name,created_at FROM admins
      WHERE login = ?`, login).Scan(&a.ID, &a.Login, &a.PasswordHash, &a.Name, &a.CreatedAt)
	if err != nil || !checkPassword(pw, a.PasswordHash) {
		renderAdmin(w, r, Admin{}, "login.html", page{
			Title: "Вход в панель управления",
			Error: "Неверный логин или пароль",
		})
		return
	}
	tok, err := createSession("admin", a.ID)
	if err != nil {
		http.Error(w, "Ошибка сервера", http.StatusInternalServerError)
		return
	}
	setCookie(w, cookieAdmin, tok, sessionDays*24*3600)
	next := r.FormValue("next")
	if !strings.HasPrefix(next, "/admin") {
		next = "/admin"
	}
	http.Redirect(w, r, next, http.StatusSeeOther)
}

func handleAdminLogout(w http.ResponseWriter, r *http.Request) {
	destroySession(cookieValue(r, cookieAdmin))
	clearCookie(w, cookieAdmin)
	http.Redirect(w, r, "/admin/login", http.StatusSeeOther)
}

// --------------------------------------------------------- дашборд

func handleAdminDashboard(w http.ResponseWriter, r *http.Request, a Admin) {
	stats, _ := dashboardStats()
	recent, _ := listOrders(-1, "")
	if len(recent) > 8 {
		recent = recent[:8]
	}
	top, _ := topProducts(6)
	low, _ := lowStockProducts()

	// Максимум для шкал рейтинга.
	var maxSum float64
	for _, t := range top {
		if t.Sum > maxSum {
			maxSum = t.Sum
		}
	}
	renderAdmin(w, r, a, "dashboard.html", page{
		Title: "Сводка",
		Nav:   "dashboard",
		Data: map[string]any{
			"Stats": stats, "Recent": recent, "Top": top,
			"Low": low, "MaxSum": maxSum,
		},
	})
}

// ---------------------------------------------------------- заказы

func handleAdminOrders(w http.ResponseWriter, r *http.Request, a Admin) {
	status := -1
	if s := r.URL.Query().Get("status"); s != "" {
		if v, err := strconv.Atoi(s); err == nil {
			status = v
		}
	}
	search := strings.TrimSpace(r.URL.Query().Get("q"))
	orders, _ := listOrders(status, search)

	// Счётчики по статусам для вкладок.
	counts := make([]int, len(StatusNames))
	all, _ := listOrders(-1, "")
	for _, o := range all {
		if o.Status >= 0 && o.Status < len(counts) {
			counts[o.Status]++
		}
	}
	renderAdmin(w, r, a, "orders.html", page{
		Title: "Заказы",
		Nav:   "orders",
		Data: map[string]any{
			"Orders": orders, "Status": status, "Search": search,
			"Counts": counts, "All": len(all),
		},
	})
}

func handleAdminOrderView(w http.ResponseWriter, r *http.Request, a Admin) {
	id, ok := pathID(r, "id")
	if !ok {
		http.NotFound(w, r)
		return
	}
	o, err := getOrder(id)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	var client *Client
	if o.ClientID != nil {
		if c, err := getClient(*o.ClientID); err == nil {
			client = &c
		}
	}
	renderAdmin(w, r, a, "order.html", page{
		Title: "Заказ " + o.Number(),
		Nav:   "orders",
		Flash: map[bool]string{true: "Статус обновлён", false: ""}[r.URL.Query().Get("ok") == "1"],
		Data:  map[string]any{"Order": o, "Client": client},
	})
}

func handleAdminOrderStatus(w http.ResponseWriter, r *http.Request, a Admin) {
	id, ok := pathID(r, "id")
	if !ok {
		http.NotFound(w, r)
		return
	}
	status, err := strconv.Atoi(r.FormValue("status"))
	if err != nil || status < 0 || status >= len(StatusNames) {
		http.Redirect(w, r, "/admin/orders/"+i64(id), http.StatusSeeOther)
		return
	}
	if err := setOrderStatus(id, status); err != nil {
		logErr("смена статуса", err)
	}
	http.Redirect(w, r, "/admin/orders/"+i64(id)+"?ok=1", http.StatusSeeOther)
}

// handleAdminOrderStock — списать заказ со склада вручную или вернуть товар.
// Пригождается, когда остаток разошёлся с жизнью: например, продажу из
// приложения завели задним числом, и склад тогда не двигали.
func handleAdminOrderStock(w http.ResponseWriter, r *http.Request, a Admin) {
	id, ok := pathID(r, "id")
	if !ok {
		http.NotFound(w, r)
		return
	}
	if err := setOrderStockWritten(id, r.FormValue("action") == "write"); err != nil {
		logErr("ручное списание склада", err)
	}
	http.Redirect(w, r, "/admin/orders/"+i64(id)+"?ok=1", http.StatusSeeOther)
}

func handleAdminOrderDelete(w http.ResponseWriter, r *http.Request, a Admin) {
	if id, ok := pathID(r, "id"); ok {
		logErr("удаление заказа", removeOrder(id, filepath.Dir(dbFile)))
	}
	http.Redirect(w, r, "/admin/orders", http.StatusSeeOther)
}

// ---------------------------------------------------------- товары

func handleAdminProducts(w http.ResponseWriter, r *http.Request, a Admin) {
	products, _ := listAllProducts()
	var stockValue, retailValue float64
	for _, p := range products {
		if !p.Archived {
			stockValue += p.StockValue()
			retailValue += p.Stock * p.Price
		}
	}
	renderAdmin(w, r, a, "products.html", page{
		Title: "Товары",
		Nav:   "products",
		Flash: map[bool]string{true: "Товар сохранён", false: ""}[r.URL.Query().Get("ok") == "1"],
		Data: map[string]any{
			"Products": products, "StockValue": stockValue, "RetailValue": retailValue,
		},
	})
}

func handleAdminProductNew(w http.ResponseWriter, r *http.Request, a Admin) {
	cats, _ := listCategories()
	renderAdmin(w, r, a, "product_edit.html", page{
		Title: "Новый товар",
		Nav:   "products",
		Data: map[string]any{
			"Product":    Product{Unit: "кг", Published: true},
			"IsNew":      true,
			"Categories": cats,
		},
	})
}

func handleAdminProductEdit(w http.ResponseWriter, r *http.Request, a Admin) {
	id, ok := pathID(r, "id")
	if !ok {
		http.NotFound(w, r)
		return
	}
	p, err := getProduct(id)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	cats, _ := listCategories()
	renderAdmin(w, r, a, "product_edit.html", page{
		Title: p.Title(),
		Nav:   "products",
		Data:  map[string]any{"Product": p, "IsNew": false, "Categories": cats},
	})
}

func handleAdminProductSave(w http.ResponseWriter, r *http.Request, a Admin) {
	// 12 МБ — с запасом на фотографию товара с телефона.
	if err := r.ParseMultipartForm(12 << 20); err != nil {
		if err := r.ParseForm(); err != nil {
			http.Error(w, "Некорректная форма", http.StatusBadRequest)
			return
		}
	}
	id, _ := strconv.ParseInt(r.FormValue("id"), 10, 64)

	var p Product
	isNew := id <= 0
	if !isNew {
		var err error
		if p, err = getProduct(id); err != nil {
			http.NotFound(w, r)
			return
		}
	}

	p.Name = strings.TrimSpace(r.FormValue("name"))
	p.SKU = strings.TrimSpace(r.FormValue("sku"))
	p.Category = strings.TrimSpace(r.FormValue("category"))
	p.Unit = strings.TrimSpace(r.FormValue("unit"))
	if p.Unit == "" {
		p.Unit = "шт"
	}
	p.Price = parseFloat(r.FormValue("price"))
	p.Cost = parseFloat(r.FormValue("cost"))
	p.Stock = parseFloat(r.FormValue("stock"))
	p.MinStock = parseFloat(r.FormValue("min_stock"))
	p.Barcode = strings.TrimSpace(r.FormValue("barcode"))
	p.Description = strings.TrimSpace(r.FormValue("description"))
	p.Published = r.FormValue("published") != ""
	p.Archived = r.FormValue("archived") != ""
	p.SortOrder = int(parseFloat(r.FormValue("sort_order")))

	if p.Name == "" {
		cats, _ := listCategories()
		renderAdmin(w, r, a, "product_edit.html", page{
			Title: "Товар",
			Nav:   "products",
			Error: "Укажите название товара",
			Data:  map[string]any{"Product": p, "IsNew": isNew, "Categories": cats},
		})
		return
	}

	if imgURL, err := saveUpload(r, "image"); err != nil {
		logErr("загрузка картинки", err)
	} else if imgURL != "" {
		p.Image = imgURL
	}
	if r.FormValue("remove_image") != "" {
		p.Image = ""
	}

	var err error
	if isNew {
		err = insertProduct(&p)
	} else {
		err = updateProduct(&p)
	}
	if err != nil {
		logErr("сохранение товара", err)
		http.Error(w, "Не удалось сохранить товар", http.StatusInternalServerError)
		return
	}
	http.Redirect(w, r, "/admin/products?ok=1", http.StatusSeeOther)
}

func handleAdminProductDelete(w http.ResponseWriter, r *http.Request, a Admin) {
	if id, ok := pathID(r, "id"); ok {
		if err := deleteProduct(id); err != nil {
			// Товар может быть связан с заказами — тогда просто прячем его.
			if p, err2 := getProduct(id); err2 == nil {
				p.Archived, p.Published = true, false
				updateProduct(&p)
			}
		}
	}
	http.Redirect(w, r, "/admin/products", http.StatusSeeOther)
}

// saveUpload кладёт присланный файл в папку картинок и возвращает путь вида /uploads/xxx.jpg.
func saveUpload(r *http.Request, field string) (string, error) {
	if r.MultipartForm == nil {
		return "", nil
	}
	file, hdr, err := r.FormFile(field)
	if err != nil {
		return "", nil // файл не прислали — это не ошибка
	}
	defer file.Close()

	ext := strings.ToLower(filepath.Ext(hdr.Filename))
	switch ext {
	case ".jpg", ".jpeg", ".png", ".webp", ".gif":
	default:
		return "", nil // чужие форматы не сохраняем
	}
	b := make([]byte, 8)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	name := hex.EncodeToString(b) + ext
	dst, err := os.Create(filepath.Join(uploadDir, name))
	if err != nil {
		return "", err
	}
	defer dst.Close()
	if _, err := io.Copy(dst, io.LimitReader(file, 12<<20)); err != nil {
		return "", err
	}
	return "/photo/" + name, nil
}

// ----------------------------------------------------------- склад

func handleAdminStock(w http.ResponseWriter, r *http.Request, a Admin) {
	products, _ := queryProducts(`WHERE archived = 0 ORDER BY category, name COLLATE NOCASE`)
	var stockValue, retail float64
	low := 0
	for _, p := range products {
		stockValue += p.StockValue()
		retail += p.Stock * p.Price
		if p.IsLowStock() {
			low++
		}
	}
	renderAdmin(w, r, a, "stock.html", page{
		Title: "Склад",
		Nav:   "stock",
		Flash: map[bool]string{true: "Остатки обновлены", false: ""}[r.URL.Query().Get("ok") == "1"],
		Data: map[string]any{
			"Products": products, "StockValue": stockValue, "Retail": retail, "Low": low,
		},
	})
}

// handleAdminStockSave — быстрая правка остатков прямо в таблице.
func handleAdminStockSave(w http.ResponseWriter, r *http.Request, a Admin) {
	if err := r.ParseForm(); err != nil {
		http.Error(w, "Некорректная форма", http.StatusBadRequest)
		return
	}
	for key, vals := range r.Form {
		if !strings.HasPrefix(key, "stock_") || len(vals) == 0 {
			continue
		}
		id, err := strconv.ParseInt(strings.TrimPrefix(key, "stock_"), 10, 64)
		if err != nil {
			continue
		}
		p, err := getProduct(id)
		if err != nil {
			continue
		}
		newStock := parseFloat(vals[0])
		if newStock != p.Stock {
			p.Stock = newStock
			updateProduct(&p)
		}
	}
	http.Redirect(w, r, "/admin/stock?ok=1", http.StatusSeeOther)
}

// --------------------------------------------------------- клиенты

func handleAdminClients(w http.ResponseWriter, r *http.Request, a Admin) {
	search := strings.TrimSpace(r.URL.Query().Get("q"))
	clients, _ := listClients(search)

	// Сумма заказов по каждому — покажем прямо в списке.
	type row struct {
		Client Client
		Total  float64
		Count  int
	}
	rows := make([]row, 0, len(clients))
	for _, c := range clients {
		t, n := clientStats(c.ID)
		rows = append(rows, row{Client: c, Total: t, Count: n})
	}
	renderAdmin(w, r, a, "clients.html", page{
		Title: "Клиенты",
		Nav:   "clients",
		Data:  map[string]any{"Rows": rows, "Search": search},
	})
}

func handleAdminClientView(w http.ResponseWriter, r *http.Request, a Admin) {
	renderAdminClient(w, r, a,
		map[bool]string{true: "Сохранено", false: ""}[r.URL.Query().Get("ok") == "1"], "")
}

// renderAdminClient — карточка покупателя. Отдельно от обработчика, потому что
// её показывают и после действий над клиентом: сообщение о результате нельзя
// унести в адрес страницы, там ему не место (новый пароль осел бы в истории
// браузера и в логах).
func renderAdminClient(w http.ResponseWriter, r *http.Request, a Admin, flash, errText string) {
	id, ok := pathID(r, "id")
	if !ok {
		http.NotFound(w, r)
		return
	}
	c, err := getClient(id)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	orders, _ := listOrdersByClient(id)
	total, count := clientStats(id)
	avg := 0.0
	if count > 0 {
		avg = total / float64(count)
	}
	renderAdmin(w, r, a, "client.html", page{
		Title: c.FullName(),
		Nav:   "clients",
		Flash: flash,
		Error: errText,
		Data: map[string]any{
			"C": c, "Orders": orders, "Total": total, "Count": count, "Avg": avg,
			"Segments": []string{"Новый", "Постоянный", "VIP", "Спящий", "Проблемный"},
		},
	})
}

// handleAdminClientPassword — задать покупателю новый пароль вместо забытого.
//
// Почты и СМС у магазина нет, поэтому восстановление тут человеческое:
// покупатель звонит, продавец нажимает кнопку и диктует пароль. Пустое поле
// означает «придумай сам».
func handleAdminClientPassword(w http.ResponseWriter, r *http.Request, a Admin) {
	id, ok := pathID(r, "id")
	if !ok {
		http.NotFound(w, r)
		return
	}
	pw, err := setClientPassword(id, r.FormValue("password"))
	switch {
	case errors.Is(err, errWeakPassword):
		renderAdminClient(w, r, a, "",
			fmt.Sprintf("Пароль короче %d символов — придумайте подлиннее или оставьте поле пустым",
				minPasswordLen))
		return
	case err != nil:
		logErr("смена пароля покупателя", err)
		renderAdminClient(w, r, a, "", "Не удалось сохранить пароль")
		return
	}
	renderAdminClient(w, r, a,
		"Новый пароль: "+pw+" — продиктуйте его покупателю. Входить он будет по своему телефону.", "")
}

func handleAdminClientSave(w http.ResponseWriter, r *http.Request, a Admin) {
	id, ok := pathID(r, "id")
	if !ok {
		http.NotFound(w, r)
		return
	}
	c, err := getClient(id)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	c.LastName = strings.TrimSpace(r.FormValue("last_name"))
	c.FirstName = strings.TrimSpace(r.FormValue("first_name"))
	c.MiddleName = strings.TrimSpace(r.FormValue("middle_name"))
	c.Email = strings.TrimSpace(r.FormValue("email"))
	c.Address = strings.TrimSpace(r.FormValue("address"))
	c.Segment = strings.TrimSpace(r.FormValue("segment"))
	c.Note = strings.TrimSpace(r.FormValue("note"))
	c.Archived = r.FormValue("archived") != ""
	if p := normalizePhone(r.FormValue("phone")); validPhone(p) {
		c.Phone = p
	}
	if err := updateClient(&c); err != nil {
		logErr("сохранение клиента", err)
	}
	http.Redirect(w, r, "/admin/clients/"+i64(id)+"?ok=1", http.StatusSeeOther)
}

// handleAdminClientBlock — блокировка и разблокировка покупателя.
// Заблокированный не может войти на сайт и оформить заказ; активные сессии
// закрываются сразу.
func handleAdminClientBlock(w http.ResponseWriter, r *http.Request, a Admin) {
	id, ok := pathID(r, "id")
	if !ok {
		http.NotFound(w, r)
		return
	}
	// Форма присылает blocked=1 («заблокировать») или blocked=0 («разблокировать»).
	if err := setClientBlocked(id, r.FormValue("blocked") == "1"); err != nil {
		logErr("блокировка клиента", err)
	}
	http.Redirect(w, r, "/admin/clients/"+i64(id), http.StatusSeeOther)
}

// handleAdminClientDelete — полное удаление карточки. История заказов остаётся:
// в заказах лежат снимки имени и телефона, а пустая связь просто обнуляется.
func handleAdminClientDelete(w http.ResponseWriter, r *http.Request, a Admin) {
	if id, ok := pathID(r, "id"); ok {
		if err := deleteClient(id); err != nil {
			logErr("удаление клиента", err)
		}
	}
	http.Redirect(w, r, "/admin/clients", http.StatusSeeOther)
}
