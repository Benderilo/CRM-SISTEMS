package main

import (
	"encoding/base64"
	"encoding/json"
	"math"
	"net/http"
	"net/url"
	"strconv"
	"strings"
)

func registerShop(mux *http.ServeMux) {
	mux.HandleFunc("GET /{$}", handleHome)
	mux.HandleFunc("GET /catalog", handleCatalog)
	mux.HandleFunc("GET /product/{id}", handleProduct)
	mux.HandleFunc("GET /contacts", handleContacts)

	mux.HandleFunc("POST /cart/add", handleCartAdd)
	mux.HandleFunc("POST /cart/update", handleCartUpdate)
	mux.HandleFunc("GET /cart", handleCart)

	mux.HandleFunc("GET /checkout", handleCheckoutForm)
	mux.HandleFunc("POST /checkout", handleCheckoutSubmit)
	mux.HandleFunc("GET /order/{id}", handleOrderView)

	mux.HandleFunc("GET /register", handleRegisterForm)
	mux.HandleFunc("POST /register", handleRegisterSubmit)
	mux.HandleFunc("GET /login", handleLoginForm)
	mux.HandleFunc("POST /login", handleLoginSubmit)
	mux.HandleFunc("POST /logout", handleLogout)

	mux.HandleFunc("GET /profile", handleProfile)
	mux.HandleFunc("POST /profile", handleProfileSave)
}

// ------------------------------------------------------------- корзина

// cartEntry — то, что реально лежит в cookie: только id и количество.
// Цены берём из базы при каждом показе, чтобы их нельзя было подделать.
type cartEntry struct {
	ID  int64   `json:"i"`
	Qty float64 `json:"q"`
}

func readCart(r *http.Request) []cartEntry {
	raw := cookieValue(r, cookieCart)
	if raw == "" {
		return nil
	}
	data, err := base64.RawURLEncoding.DecodeString(raw)
	if err != nil {
		return nil
	}
	var out []cartEntry
	if err := json.Unmarshal(data, &out); err != nil {
		return nil
	}
	// Отсекаем мусор, чтобы кривая cookie не сломала страницу.
	clean := out[:0]
	for _, e := range out {
		if e.ID > 0 && e.Qty > 0 && e.Qty <= 1000 {
			clean = append(clean, e)
		}
	}
	return clean
}

func writeCart(w http.ResponseWriter, entries []cartEntry) {
	if len(entries) == 0 {
		clearCookie(w, cookieCart)
		return
	}
	data, err := json.Marshal(entries)
	if err != nil {
		return
	}
	setCookie(w, cookieCart, base64.RawURLEncoding.EncodeToString(data), 30*24*3600)
}

// buildCart превращает cookie в корзину с актуальными ценами.
// Остаток на складе количество не ограничивает: заказать можно и то, чего
// сейчас нет — магазин доготовит партию и подтвердит заказ по телефону.
func buildCart(r *http.Request) Cart {
	var c Cart
	for _, e := range readCart(r) {
		p, err := getProduct(e.ID)
		if err != nil || p.Archived || !p.Published {
			continue
		}
		c.Lines = append(c.Lines, CartLine{Product: p, Qty: e.Qty})
	}
	return c
}

// handleCartAdd кладёт товар в корзину и возвращает покупателя туда, где он был,
// чтобы можно было набирать заказ подряд: нажал — добавилось, нажал — добавилось.
// Точное количество человек проставит в самой корзине.
func handleCartAdd(w http.ResponseWriter, r *http.Request) {
	id := int64(parseFloat(r.FormValue("id")))
	qty := buyerQty(parseFloat(r.FormValue("qty")))
	p, err := getProduct(id)
	if err != nil || p.Archived || !p.Published {
		http.Redirect(w, r, "/catalog", http.StatusSeeOther)
		return
	}

	entries := readCart(r)
	total := qty
	found := false
	for i := range entries {
		if entries[i].ID == id {
			entries[i].Qty = buyerQty(entries[i].Qty + qty)
			total = entries[i].Qty
			found = true
			break
		}
	}
	if !found {
		entries = append(entries, cartEntry{ID: id, Qty: qty})
	}
	writeCart(w, entries)

	// Фоновому запросу со страницы каталога отвечаем числами — страница
	// обновляет счётчик и подпись под кнопкой, не перезагружаясь.
	if wantsJSON(r) {
		writeJSON(w, http.StatusOK, map[string]any{
			"ok": true, "id": id, "qty": total, "unit": p.Unit,
			"count": len(entries), "title": p.Title(),
		})
		return
	}
	http.Redirect(w, r, backTo(r, p.Title()), http.StatusSeeOther)
}

// backTo — адрес, куда вернуть покупателя после «В корзину»: страница, с которой
// он нажал, плюс отметка added для сообщения. Чужие адреса не принимаем.
func backTo(r *http.Request, title string) string {
	raw := r.FormValue("back")
	if raw == "" {
		if ref, err := url.Parse(r.Referer()); err == nil && ref.Host == r.Host {
			raw = ref.RequestURI()
		}
	}
	u, err := url.Parse(raw)
	if err != nil || !strings.HasPrefix(u.Path, "/") || strings.HasPrefix(u.Path, "//") {
		u = &url.URL{Path: "/catalog"}
	}
	q := u.Query()
	q.Set("added", title)
	u.RawQuery = q.Encode()
	return u.RequestURI()
}

func wantsJSON(r *http.Request) bool {
	return strings.Contains(r.Header.Get("Accept"), "application/json")
}

// handleCartUpdate пересчитывает всю корзину разом: в форме корзины у каждой
// строки своё поле qty_<id>. Кнопка «Убрать» присылает remove=<id>, а «Оформить
// заказ» — go=checkout, чтобы изменённые количества сохранились перед оформлением.
func handleCartUpdate(w http.ResponseWriter, r *http.Request) {
	if err := r.ParseForm(); err != nil {
		http.Redirect(w, r, "/cart", http.StatusSeeOther)
		return
	}
	remove := int64(parseFloat(r.FormValue("remove")))

	entries := readCart(r)
	out := entries[:0]
	for _, e := range entries {
		if e.ID == remove {
			continue
		}
		if vals, ok := r.PostForm["qty_"+i64(e.ID)]; ok && len(vals) > 0 {
			qty := parseFloat(vals[0])
			if qty <= 0 {
				continue // ноль в поле — то же самое, что «Убрать»
			}
			e.Qty = buyerQty(qty)
		}
		out = append(out, e)
	}
	writeCart(w, out)

	if r.FormValue("go") == "checkout" && len(out) > 0 {
		http.Redirect(w, r, "/checkout", http.StatusSeeOther)
		return
	}
	http.Redirect(w, r, "/cart", http.StatusSeeOther)
}

// roundQty убирает хвосты вроде 1.7000000000000002 и держит количество
// в разумных пределах: три знака после запятой — это уже граммы.
//
// Дробные количества остались у продавца: он правит состав заказа, когда
// покупатель по телефону просит взять поменьше. Покупатель же набирает
// заказ целыми — см. buyerQty.
func roundQty(v float64) float64 {
	if v > 1000 {
		v = 1000
	}
	return math.Round(v*1000) / 1000
}

// buyerQty — сколько покупатель может положить в корзину: только целое число
// и не меньше одного. Магазин развозит от килограмма, а полкило в заявке
// оборачивались лишним звонком «вы точно столько хотели?».
//
// Проверка серверная, а не только в поле ввода: в корзину приходят и запросы
// из бота, и просто подделанная форма.
func buyerQty(v float64) float64 {
	v = math.Round(v)
	if v < 1 {
		v = 1
	}
	if v > 1000 {
		v = 1000
	}
	return v
}

func handleCart(w http.ResponseWriter, r *http.Request) {
	cart := buildCart(r)
	render(w, r, "cart.html", page{
		Title:     "Корзина — " + shopName(),
		Nav:       "cart",
		CartCount: len(cart.Lines),
		Data:      cart,
	})
}

func flashAdded(r *http.Request) string {
	if v := r.URL.Query().Get("added"); v != "" {
		return "«" + v + "» в корзине"
	}
	return ""
}

// -------------------------------------------------------------- витрина

type homeData struct {
	Categories []string
	Popular    []Product
	Total      int
	News       []NewsItem // бегущая строка, пишется в приложении
}

func handleHome(w http.ResponseWriter, r *http.Request) {
	cats, _ := listCategories()
	all, _ := listPublishedProducts("", "")
	popular := all
	if len(popular) > 6 {
		popular = popular[:6]
	}
	news, err := listNews(true)
	logErr("новости", err)
	render(w, r, "index.html", page{
		Nav:  "home",
		Data: homeData{Categories: cats, Popular: popular, Total: len(all), News: news},
	})
}

type catalogData struct {
	Products   []Product
	Categories []string
	Category   string
	Search     string
	News       []NewsItem // бегущая строка — та же, что на главной
}

func handleCatalog(w http.ResponseWriter, r *http.Request) {
	cat := strings.TrimSpace(r.URL.Query().Get("cat"))
	search := strings.TrimSpace(r.URL.Query().Get("q"))
	products, err := listPublishedProducts(cat, search)
	if err != nil {
		http.Error(w, "Ошибка базы", http.StatusInternalServerError)
		return
	}
	cats, _ := listCategories()
	news, err := listNews(true)
	logErr("новости", err)
	title := "Каталог"
	if cat != "" {
		title = cat
	}
	render(w, r, "catalog.html", page{
		Title: title + " — " + shopName(),
		Nav:   "catalog",
		Data: catalogData{Products: products, Categories: cats, Category: cat,
			Search: search, News: news},
	})
}

type productData struct {
	Product Product
	Similar []Product
}

func handleProduct(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r, "id")
	if !ok {
		http.NotFound(w, r)
		return
	}
	p, err := getProduct(id)
	if err != nil || p.Archived || !p.Published {
		http.NotFound(w, r)
		return
	}
	similar, _ := listPublishedProducts(p.Category, "")
	out := similar[:0]
	for _, s := range similar {
		if s.ID != p.ID {
			out = append(out, s)
		}
	}
	if len(out) > 3 {
		out = out[:3]
	}
	render(w, r, "product.html", page{
		Title: p.Title() + " — " + shopName(),
		Nav:   "catalog",
		Data:  productData{Product: p, Similar: out},
	})
}

func handleContacts(w http.ResponseWriter, r *http.Request) {
	render(w, r, "contacts.html", page{Title: "Контакты — " + shopName(), Nav: "contacts"})
}

// ------------------------------------------------------------ оформление

type checkoutData struct {
	Cart   Cart
	Form   orderForm
	Errors map[string]string
}

// orderForm — то, что человек вводит при оформлении.
type orderForm struct {
	LastName   string
	FirstName  string
	MiddleName string
	Phone      string
	Email      string
	Address    string
	Comment    string
	Delivery   string
	PayMethod  string
}

func handleCheckoutForm(w http.ResponseWriter, r *http.Request) {
	cart := buildCart(r)
	if cart.Empty() {
		http.Redirect(w, r, "/cart", http.StatusSeeOther)
		return
	}
	form := orderForm{Delivery: "Доставка", PayMethod: "Наличные при получении"}
	// Залогиненному подставляем его данные — не заставляем вводить заново.
	if c, ok := currentClient(r); ok {
		form.LastName, form.FirstName, form.MiddleName = c.LastName, c.FirstName, c.MiddleName
		form.Phone, form.Email, form.Address = formatPhone(c.Phone), c.Email, c.Address
	}
	render(w, r, "checkout.html", page{
		Title: "Оформление заказа — " + shopName(),
		Nav:   "cart",
		Data:  checkoutData{Cart: cart, Form: form, Errors: map[string]string{}},
	})
}

func handleCheckoutSubmit(w http.ResponseWriter, r *http.Request) {
	cart := buildCart(r)
	if cart.Empty() {
		http.Redirect(w, r, "/cart", http.StatusSeeOther)
		return
	}
	f := orderForm{
		LastName:   strings.TrimSpace(r.FormValue("last_name")),
		FirstName:  strings.TrimSpace(r.FormValue("first_name")),
		MiddleName: strings.TrimSpace(r.FormValue("middle_name")),
		Phone:      strings.TrimSpace(r.FormValue("phone")),
		Email:      strings.TrimSpace(r.FormValue("email")),
		Address:    strings.TrimSpace(r.FormValue("address")),
		Comment:    strings.TrimSpace(r.FormValue("comment")),
		Delivery:   r.FormValue("delivery"),
		PayMethod:  r.FormValue("pay_method"),
	}
	errs := map[string]string{}
	phone := normalizePhone(f.Phone)
	if f.FirstName == "" {
		errs["first_name"] = "Укажите имя"
	}
	if !validPhone(phone) {
		errs["phone"] = "Телефон в формате +7 (999) 123-45-67"
	}
	if f.Delivery == "Доставка" && f.Address == "" {
		errs["address"] = "Укажите адрес доставки"
	}
	if len(errs) > 0 {
		render(w, r, "checkout.html", page{
			Title: "Оформление заказа — " + shopName(),
			Nav:   "cart",
			Error: "Проверьте заполнение полей",
			Data:  checkoutData{Cart: cart, Form: f, Errors: errs},
		})
		return
	}

	o := Order{
		Phone:     phone,
		Email:     f.Email,
		Address:   f.Address,
		Comment:   f.Comment,
		Delivery:  f.Delivery,
		PayMethod: f.PayMethod,
		Status:    StatusNew,
	}

	// Привязываем заказ к клиенту: к залогиненному либо к карточке по телефону.
	// Если такого телефона ещё нет — заводим карточку, чтобы заказ не «повис».
	// Заблокированному покупателю заказ оформить нельзя.
	blocked := false
	if c, ok := currentClient(r); ok {
		if c.Blocked {
			blocked = true
		} else {
			o.ClientID, o.ClientName = &c.ID, c.FullName()
			touchClientFromOrder(c, f)
		}
	} else if c, err := getClientByPhone(phone); err == nil {
		if c.Blocked {
			blocked = true
		} else {
			o.ClientID, o.ClientName = &c.ID, c.FullName()
		}
	} else {
		nc := Client{
			Phone: phone, Email: f.Email, LastName: f.LastName, FirstName: f.FirstName,
			MiddleName: f.MiddleName, Address: f.Address, Segment: "Новый", Source: "Сайт",
			Note: "Заведён автоматически при заказе без регистрации",
		}
		if err := insertClient(&nc); err == nil {
			o.ClientID, o.ClientName = &nc.ID, nc.FullName()
		}
	}
	if blocked {
		render(w, r, "checkout.html", page{
			Title: "Оформление заказа — " + shopName(),
			Error: "Этот аккаунт заблокирован. Свяжитесь с магазином.",
			Data:  checkoutData{Cart: cart, Form: f, Errors: errs},
		})
		return
	}
	if o.ClientName == "" {
		o.ClientName = strings.TrimSpace(f.LastName + " " + f.FirstName + " " + f.MiddleName)
	}

	if err := createOrder(&o, cart.Lines); err != nil {
		render(w, r, "checkout.html", page{
			Title: "Оформление заказа — " + shopName(),
			Error: "Не удалось сохранить заказ. Попробуйте ещё раз.",
			Data:  checkoutData{Cart: cart, Form: f, Errors: errs},
		})
		return
	}

	writeCart(w, nil) // корзина больше не нужна
	url := "/order/" + i64(o.ID) + "?new=1"
	if o.Merged {
		// Товары ушли в уже открытый заказ покупателя — скажем об этом прямо,
		// иначе он удивится чужому номеру и сумме больше корзины.
		url += "&merged=1"
	}
	http.Redirect(w, r, url, http.StatusSeeOther)
}

// touchClientFromOrder дописывает клиенту адрес и почту, если он их раньше не указывал.
func touchClientFromOrder(c Client, f orderForm) {
	changed := false
	if c.Address == "" && f.Address != "" {
		c.Address, changed = f.Address, true
	}
	if c.Email == "" && f.Email != "" {
		c.Email, changed = f.Email, true
	}
	if changed {
		updateClient(&c)
	}
}

func handleOrderView(w http.ResponseWriter, r *http.Request) {
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
	// Чужой заказ показываем только сразу после оформления (по ссылке из редиректа).
	justCreated := r.URL.Query().Get("new") == "1"
	if !justCreated {
		c, ok := currentClient(r)
		if !ok || o.ClientID == nil || *o.ClientID != c.ID {
			http.NotFound(w, r)
			return
		}
	}
	render(w, r, "order.html", page{
		Title: "Заказ " + o.Number() + " — " + shopName(),
		Data: map[string]any{"Order": o, "New": justCreated,
			"Merged": justCreated && r.URL.Query().Get("merged") == "1"},
	})
}

// ------------------------------------------------------- вход/регистрация

func handleRegisterForm(w http.ResponseWriter, r *http.Request) {
	if _, ok := currentClient(r); ok {
		http.Redirect(w, r, "/profile", http.StatusSeeOther)
		return
	}
	render(w, r, "register.html", page{
		Title: "Регистрация — " + shopName(),
		Data:  map[string]any{"Form": Client{}, "Errors": map[string]string{}},
	})
}

func handleRegisterSubmit(w http.ResponseWriter, r *http.Request) {
	f := Client{
		Phone:      strings.TrimSpace(r.FormValue("phone")),
		Email:      strings.TrimSpace(r.FormValue("email")),
		LastName:   strings.TrimSpace(r.FormValue("last_name")),
		FirstName:  strings.TrimSpace(r.FormValue("first_name")),
		MiddleName: strings.TrimSpace(r.FormValue("middle_name")),
		Address:    strings.TrimSpace(r.FormValue("address")),
	}
	pw := r.FormValue("password")
	pw2 := r.FormValue("password2")

	errs := map[string]string{}
	phone := normalizePhone(f.Phone)
	if !validPhone(phone) {
		errs["phone"] = "Телефон в формате +7 (999) 123-45-67"
	}
	if len([]rune(pw)) < 5 {
		errs["password"] = "Минимум 5 символов"
	}
	if pw != pw2 {
		errs["password2"] = "Пароли не совпадают"
	}
	if f.FirstName == "" {
		errs["first_name"] = "Укажите имя"
	}
	if len(errs) == 0 {
		if _, err := getClientByPhone(phone); err == nil {
			errs["phone"] = "Этот телефон уже зарегистрирован — войдите"
		}
	}
	if len(errs) > 0 {
		render(w, r, "register.html", page{
			Title: "Регистрация — " + shopName(),
			Error: "Проверьте заполнение полей",
			Data:  map[string]any{"Form": f, "Errors": errs},
		})
		return
	}

	hash, err := hashPassword(pw)
	if err != nil {
		http.Error(w, "Ошибка сервера", http.StatusInternalServerError)
		return
	}
	f.Phone, f.PasswordHash = phone, hash
	f.Segment, f.Source = "Новый", "Сайт"
	if err := insertClient(&f); err != nil {
		render(w, r, "register.html", page{
			Title: "Регистрация — " + shopName(),
			Error: "Не удалось создать аккаунт",
			Data:  map[string]any{"Form": f, "Errors": errs},
		})
		return
	}
	if tok, err := createSession("client", f.ID); err == nil {
		setCookie(w, cookieClient, tok, sessionDays*24*3600)
	}
	http.Redirect(w, r, nextAfterLogin(r), http.StatusSeeOther)
}

func handleLoginForm(w http.ResponseWriter, r *http.Request) {
	if _, ok := currentClient(r); ok {
		http.Redirect(w, r, "/profile", http.StatusSeeOther)
		return
	}
	render(w, r, "login.html", page{
		Title: "Вход — " + shopName(),
		Data:  map[string]any{"Phone": "", "Next": r.URL.Query().Get("next")},
	})
}

func handleLoginSubmit(w http.ResponseWriter, r *http.Request) {
	phone := normalizePhone(r.FormValue("phone"))
	pw := r.FormValue("password")

	c, err := getClientByPhone(phone)
	if err != nil || c.PasswordHash == "" || !checkPassword(pw, c.PasswordHash) {
		render(w, r, "login.html", page{
			Title: "Вход — " + shopName(),
			Error: "Неверный телефон или пароль",
			Data:  map[string]any{"Phone": r.FormValue("phone"), "Next": r.FormValue("next")},
		})
		return
	}
	if c.Blocked {
		render(w, r, "login.html", page{
			Title: "Вход — " + shopName(),
			Error: "Аккаунт заблокирован. Свяжитесь с магазином.",
			Data:  map[string]any{"Phone": "", "Next": r.FormValue("next")},
		})
		return
	}
	tok, err := createSession("client", c.ID)
	if err != nil {
		http.Error(w, "Ошибка сервера", http.StatusInternalServerError)
		return
	}
	setCookie(w, cookieClient, tok, sessionDays*24*3600)
	http.Redirect(w, r, nextAfterLogin(r), http.StatusSeeOther)
}

// nextAfterLogin — куда вернуть человека после входа (только внутренние адреса).
func nextAfterLogin(r *http.Request) string {
	n := r.FormValue("next")
	if n == "" {
		n = r.URL.Query().Get("next")
	}
	if strings.HasPrefix(n, "/") && !strings.HasPrefix(n, "//") {
		return n
	}
	return "/profile"
}

func handleLogout(w http.ResponseWriter, r *http.Request) {
	destroySession(cookieValue(r, cookieClient))
	clearCookie(w, cookieClient)
	http.Redirect(w, r, "/", http.StatusSeeOther)
}

// --------------------------------------------------------------- профиль

func handleProfile(w http.ResponseWriter, r *http.Request) {
	c, ok := currentClient(r)
	if !ok {
		http.Redirect(w, r, "/login?next=/profile", http.StatusSeeOther)
		return
	}
	orders, _ := listOrdersByClient(c.ID)
	for i := range orders {
		orders[i].Items, _ = listOrderItems(orders[i].ID)
	}
	total, count := clientStats(c.ID)
	render(w, r, "profile.html", page{
		Title:  "Личный кабинет — " + shopName(),
		Nav:    "profile",
		Client: &c,
		Flash:  map[bool]string{true: "Данные сохранены", false: ""}[r.URL.Query().Get("saved") == "1"],
		Data: map[string]any{
			"Orders": orders, "Total": total, "Count": count,
			"Errors": map[string]string{},
		},
	})
}

func handleProfileSave(w http.ResponseWriter, r *http.Request) {
	c, ok := currentClient(r)
	if !ok {
		http.Redirect(w, r, "/login?next=/profile", http.StatusSeeOther)
		return
	}
	c.LastName = strings.TrimSpace(r.FormValue("last_name"))
	c.FirstName = strings.TrimSpace(r.FormValue("first_name"))
	c.MiddleName = strings.TrimSpace(r.FormValue("middle_name"))
	c.Email = strings.TrimSpace(r.FormValue("email"))
	c.Address = strings.TrimSpace(r.FormValue("address"))
	if err := updateClient(&c); err != nil {
		http.Error(w, "Не удалось сохранить", http.StatusInternalServerError)
		return
	}

	// Смена пароля — только если оба поля заполнены и совпадают.
	if pw := r.FormValue("password"); pw != "" {
		if len([]rune(pw)) >= 5 && pw == r.FormValue("password2") {
			if h, err := hashPassword(pw); err == nil {
				updateClientPassword(c.ID, h)
			}
		}
	}
	http.Redirect(w, r, "/profile?saved=1", http.StatusSeeOther)
}

func i64(v int64) string { return strconv.FormatInt(v, 10) }
