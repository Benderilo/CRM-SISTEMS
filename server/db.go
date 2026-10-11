package main

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strings"
	"time"

	_ "modernc.org/sqlite"
)

var db *sql.DB

const schema = `
PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS products (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  name        TEXT    NOT NULL DEFAULT '',
  sku         TEXT    NOT NULL DEFAULT '',
  category    TEXT    NOT NULL DEFAULT '',
  unit        TEXT    NOT NULL DEFAULT 'шт',
  price       REAL    NOT NULL DEFAULT 0,
  cost        REAL    NOT NULL DEFAULT 0,
  stock       REAL    NOT NULL DEFAULT 0,
  min_stock   REAL    NOT NULL DEFAULT 0,
  barcode     TEXT    NOT NULL DEFAULT '',
  description TEXT    NOT NULL DEFAULT '',
  image       TEXT    NOT NULL DEFAULT '',
  archived    INTEGER NOT NULL DEFAULT 0,
  published   INTEGER NOT NULL DEFAULT 1,
  sort_order  INTEGER NOT NULL DEFAULT 0,
  created_at  INTEGER NOT NULL DEFAULT 0,
  updated_at  INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_products_category ON products(category);

CREATE TABLE IF NOT EXISTS clients (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  phone         TEXT    NOT NULL UNIQUE,
  password_hash TEXT    NOT NULL DEFAULT '',
  email         TEXT    NOT NULL DEFAULT '',
  last_name     TEXT    NOT NULL DEFAULT '',
  first_name    TEXT    NOT NULL DEFAULT '',
  middle_name   TEXT    NOT NULL DEFAULT '',
  address       TEXT    NOT NULL DEFAULT '',
  segment       TEXT    NOT NULL DEFAULT 'Новый',
  source        TEXT    NOT NULL DEFAULT 'Сайт',
  note          TEXT    NOT NULL DEFAULT '',
  archived      INTEGER NOT NULL DEFAULT 0,
  blocked       INTEGER NOT NULL DEFAULT 0,
  created_at    INTEGER NOT NULL DEFAULT 0,
  updated_at    INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS orders (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  client_id   INTEGER REFERENCES clients(id) ON DELETE SET NULL,
  client_name TEXT    NOT NULL DEFAULT '',
  phone       TEXT    NOT NULL DEFAULT '',
  email       TEXT    NOT NULL DEFAULT '',
  address     TEXT    NOT NULL DEFAULT '',
  comment     TEXT    NOT NULL DEFAULT '',
  delivery    TEXT    NOT NULL DEFAULT 'Доставка',
  pay_method  TEXT    NOT NULL DEFAULT 'Наличные',
  status      INTEGER NOT NULL DEFAULT 0,
  total       REAL    NOT NULL DEFAULT 0,
  -- Откуда заказ: пусто — с витрины, «app» — продажа, заведённая в приложении.
  source      TEXT    NOT NULL DEFAULT '',
  -- Списывался ли склад при создании: от этого зависит возврат товара при отмене.
  stock_written INTEGER NOT NULL DEFAULT 1,
  created_at  INTEGER NOT NULL DEFAULT 0,
  updated_at  INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_orders_client ON orders(client_id);
CREATE INDEX IF NOT EXISTS idx_orders_status ON orders(status);
CREATE INDEX IF NOT EXISTS idx_orders_created ON orders(created_at);

CREATE TABLE IF NOT EXISTS order_items (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  order_id   INTEGER NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
  product_id INTEGER REFERENCES products(id) ON DELETE SET NULL,
  name       TEXT    NOT NULL DEFAULT '',
  unit       TEXT    NOT NULL DEFAULT 'шт',
  qty        REAL    NOT NULL DEFAULT 1,
  unit_price REAL    NOT NULL DEFAULT 0,
  unit_cost  REAL    NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_items_order ON order_items(order_id);

CREATE TABLE IF NOT EXISTS admins (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  login         TEXT    NOT NULL UNIQUE,
  password_hash TEXT    NOT NULL DEFAULT '',
  name          TEXT    NOT NULL DEFAULT '',
  created_at    INTEGER NOT NULL DEFAULT 0
);

-- Тексты витрины: контакты, описания разделов, верхняя полоска.
-- Правятся из приложения, ключи перечислены в site.go.
CREATE TABLE IF NOT EXISTS settings (
  key        TEXT PRIMARY KEY,
  value      TEXT    NOT NULL DEFAULT '',
  updated_at INTEGER NOT NULL DEFAULT 0
);

-- Новости для бегущей строки на главной.
CREATE TABLE IF NOT EXISTS news (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  text       TEXT    NOT NULL DEFAULT '',
  published  INTEGER NOT NULL DEFAULT 1,
  created_at INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL DEFAULT 0
);

-- Разговоры бота MAX: состояние оформления и набранная корзина.
-- Ключ «пользователь:чат» — один человек может набирать заказ и в группе,
-- и в личке, и они не перемешаются.
CREATE TABLE IF NOT EXISTS bot_sessions (
  key        TEXT PRIMARY KEY,
  user_id    INTEGER NOT NULL DEFAULT 0,
  chat_id    INTEGER NOT NULL DEFAULT 0,
  state      TEXT    NOT NULL DEFAULT '',
  name       TEXT    NOT NULL DEFAULT '',
  phone      TEXT    NOT NULL DEFAULT '',
  address    TEXT    NOT NULL DEFAULT '',
  cart       TEXT    NOT NULL DEFAULT '',
  updated_at INTEGER NOT NULL DEFAULT 0
);

-- Покупатели бота: имя, телефон и адрес с прошлого заказа. Благодаря этому
-- постоянному клиенту не нужно каждый раз всё вводить заново.
CREATE TABLE IF NOT EXISTS bot_customers (
  user_id    INTEGER PRIMARY KEY,
  client_id  INTEGER,
  name       TEXT    NOT NULL DEFAULT '',
  phone      TEXT    NOT NULL DEFAULT '',
  address    TEXT    NOT NULL DEFAULT '',
  updated_at INTEGER NOT NULL DEFAULT 0
);

-- Сообщения MAX, которые бот уже разобрал. Нужны, чтобы догон истории
-- (maxbot_history.go) не завёл вторую заявку по тому же сообщению.
CREATE TABLE IF NOT EXISTS bot_seen (
  mid TEXT PRIMARY KEY,
  at  INTEGER NOT NULL DEFAULT 0
);

-- Последняя заявка, заведённая по сообщению человека из MAX. По ней второе
-- сообщение того же человека дописывается в ту же заявку, а не плодит новые.
-- Отдельно от bot_customers: там покупатели, которые дошли до заказа, а тут
-- любой, кто просто написал.
CREATE TABLE IF NOT EXISTS bot_leads (
  user_id  INTEGER PRIMARY KEY,
  order_id INTEGER NOT NULL,
  at       INTEGER NOT NULL DEFAULT 0
);

-- Сверка заявки с перепиской: когда сверяли в последний раз и каким заказ
-- был после последней правки бота. Если updated_at заказа с тех пор изменился
-- не ботом — значит, заявку взял в руки продавец, и бот в неё больше не лезет
-- (stopped = 1): спорить с человеком, который её уже правит, нельзя.
CREATE TABLE IF NOT EXISTS bot_recheck (
  order_id INTEGER PRIMARY KEY,
  at       INTEGER NOT NULL DEFAULT 0,
  seen_at  INTEGER NOT NULL DEFAULT 0,
  stopped  INTEGER NOT NULL DEFAULT 0
);

-- Чей это MAX: связка пользователя мессенджера с карточкой клиента.
-- Нужна перехвату, чтобы узнавать человека без телефона в сообщении.
-- Отдельно от bot_customers: там данные, которые покупатель сам подтвердил
-- при оформлении, и по ним бот предлагает оформить заказ не спрашивая.
-- Здесь же догадка перехвата — для разговора с покупателем она не годится.
CREATE TABLE IF NOT EXISTS bot_known (
  user_id   INTEGER PRIMARY KEY,
  client_id INTEGER NOT NULL,
  at        INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS sessions (
  token      TEXT PRIMARY KEY,
  kind       TEXT    NOT NULL,           -- 'client' | 'admin'
  subject_id INTEGER NOT NULL,
  created_at INTEGER NOT NULL DEFAULT 0,
  expires_at INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_sessions_expires ON sessions(expires_at);
`

func openDB(path string) error {
	var err error
	db, err = sql.Open("sqlite", path+"?_pragma=busy_timeout(5000)&_pragma=foreign_keys(1)")
	if err != nil {
		return err
	}
	// SQLite не любит параллельную запись — держим один коннект на запись.
	db.SetMaxOpenConns(1)
	if err = db.Ping(); err != nil {
		return err
	}
	if _, err = db.Exec(schema); err != nil {
		return fmt.Errorf("схема: %w", err)
	}
	if _, err = db.Exec(packSchema); err != nil {
		return fmt.Errorf("схема сборки: %w", err)
	}
	migrate()
	return nil
}

// migrate — мелкие правки данных при обновлении версии.
func migrate() {
	// Столбец source появился вместе с продажами из приложения: в уже созданной
	// базе CREATE TABLE IF NOT EXISTS его не добавит, дописываем руками.
	for _, m := range []struct{ table, column, ddl string }{
		{"orders", "source", `ALTER TABLE orders ADD COLUMN source TEXT NOT NULL DEFAULT ''`},
		{"orders", "stock_written", `ALTER TABLE orders ADD COLUMN stock_written INTEGER NOT NULL DEFAULT 1`},
		{"clients", "blocked", `ALTER TABLE clients ADD COLUMN blocked INTEGER NOT NULL DEFAULT 0`},
	} {
		if hasColumn(m.table, m.column) {
			continue
		}
		if _, err := db.Exec(m.ddl); err != nil {
			log.Printf("миграция %s.%s: %v", m.table, m.column, err)
		} else {
			log.Printf("в таблицу %s добавлен столбец %s", m.table, m.column)
		}
	}

	// Картинки переехали с /uploads/ на /photo/: префикс /upload на этом сервере
	// занят чужим медиа-сервисом в nginx, и он перехватывал наши файлы.
	res, err := db.Exec(`UPDATE products SET image = '/photo/' || substr(image, 10)
      WHERE image LIKE '/uploads/%'`)
	if err != nil {
		log.Printf("миграция картинок: %v", err)
		return
	}
	if n, _ := res.RowsAffected(); n > 0 {
		log.Printf("перенесено картинок на новый путь: %d", n)
	}
}

// hasColumn — есть ли столбец в таблице (для мягких миграций старых баз).
func hasColumn(table, column string) bool {
	rows, err := db.Query(`SELECT name FROM pragma_table_info(?)`, table)
	if err != nil {
		log.Printf("pragma_table_info(%s): %v", table, err)
		return true // не знаем — считаем, что есть: ALTER всё равно упал бы
	}
	defer rows.Close()
	for rows.Next() {
		var name string
		if err := rows.Scan(&name); err == nil && name == column {
			return true
		}
	}
	return false
}

func nowMs() int64 { return time.Now().UnixMilli() }

// ---------------------------------------------------------------- товары

const productCols = `id,name,sku,category,unit,price,cost,stock,min_stock,barcode,
description,image,archived,published,sort_order,created_at,updated_at`

func scanProduct(s interface{ Scan(...any) error }) (Product, error) {
	var p Product
	err := s.Scan(&p.ID, &p.Name, &p.SKU, &p.Category, &p.Unit, &p.Price, &p.Cost,
		&p.Stock, &p.MinStock, &p.Barcode, &p.Description, &p.Image,
		&p.Archived, &p.Published, &p.SortOrder, &p.CreatedAt, &p.UpdatedAt)
	return p, err
}

func queryProducts(where string, args ...any) ([]Product, error) {
	rows, err := db.Query(`SELECT `+productCols+` FROM products `+where, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Product{}
	for rows.Next() {
		p, err := scanProduct(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

// listPublishedProducts — товары для витрины: не в архиве и опубликованные.
func listPublishedProducts(category, search string) ([]Product, error) {
	where := `WHERE archived = 0 AND published = 1`
	args := []any{}
	if category != "" {
		where += ` AND category = ?`
		args = append(args, category)
	}
	if search != "" {
		where += ` AND (LOWER(name) LIKE ? OR LOWER(description) LIKE ? OR LOWER(category) LIKE ?)`
		q := "%" + strings.ToLower(search) + "%"
		args = append(args, q, q, q)
	}
	where += ` ORDER BY sort_order, name COLLATE NOCASE`
	return queryProducts(where, args...)
}

func listAllProducts() ([]Product, error) {
	return queryProducts(`ORDER BY archived, sort_order, name COLLATE NOCASE`)
}

func getProduct(id int64) (Product, error) {
	row := db.QueryRow(`SELECT `+productCols+` FROM products WHERE id = ?`, id)
	return scanProduct(row)
}

func insertProduct(p *Product) error {
	p.CreatedAt, p.UpdatedAt = nowMs(), nowMs()
	res, err := db.Exec(`INSERT INTO products
    (name,sku,category,unit,price,cost,stock,min_stock,barcode,description,image,
     archived,published,sort_order,created_at,updated_at)
    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
		p.Name, p.SKU, p.Category, p.Unit, p.Price, p.Cost, p.Stock, p.MinStock,
		p.Barcode, p.Description, p.Image, p.Archived, p.Published, p.SortOrder,
		p.CreatedAt, p.UpdatedAt)
	if err != nil {
		return err
	}
	p.ID, err = res.LastInsertId()
	return err
}

func updateProduct(p *Product) error {
	p.UpdatedAt = nowMs()
	_, err := db.Exec(`UPDATE products SET name=?,sku=?,category=?,unit=?,price=?,cost=?,
    stock=?,min_stock=?,barcode=?,description=?,image=?,archived=?,published=?,
    sort_order=?,updated_at=? WHERE id=?`,
		p.Name, p.SKU, p.Category, p.Unit, p.Price, p.Cost, p.Stock, p.MinStock,
		p.Barcode, p.Description, p.Image, p.Archived, p.Published, p.SortOrder,
		p.UpdatedAt, p.ID)
	return err
}

func deleteProduct(id int64) error {
	_, err := db.Exec(`DELETE FROM products WHERE id = ?`, id)
	return err
}

// listCategories — категории, по которым реально есть опубликованные товары.
func listCategories() ([]string, error) {
	rows, err := db.Query(`SELECT DISTINCT category FROM products
      WHERE archived = 0 AND published = 1 AND TRIM(category) <> ''
      ORDER BY category COLLATE NOCASE`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []string{}
	for rows.Next() {
		var c string
		if err := rows.Scan(&c); err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

// --------------------------------------------------------------- клиенты

const clientCols = `id,phone,password_hash,email,last_name,first_name,middle_name,
address,segment,source,note,archived,blocked,created_at,updated_at`

func scanClient(s interface{ Scan(...any) error }) (Client, error) {
	var c Client
	err := s.Scan(&c.ID, &c.Phone, &c.PasswordHash, &c.Email, &c.LastName, &c.FirstName,
		&c.MiddleName, &c.Address, &c.Segment, &c.Source, &c.Note, &c.Archived,
		&c.Blocked, &c.CreatedAt, &c.UpdatedAt)
	return c, err
}

func getClient(id int64) (Client, error) {
	row := db.QueryRow(`SELECT `+clientCols+` FROM clients WHERE id = ?`, id)
	return scanClient(row)
}

func getClientByPhone(phone string) (Client, error) {
	row := db.QueryRow(`SELECT `+clientCols+` FROM clients WHERE phone = ?`, phone)
	return scanClient(row)
}

func listClients(search string) ([]Client, error) {
	where := `WHERE 1=1`
	args := []any{}
	if search != "" {
		where += ` AND (LOWER(last_name||' '||first_name||' '||middle_name) LIKE ?
                    OR phone LIKE ? OR LOWER(email) LIKE ?)`
		q := "%" + strings.ToLower(search) + "%"
		args = append(args, q, q, q)
	}
	rows, err := db.Query(`SELECT `+clientCols+` FROM clients `+where+
		` ORDER BY created_at DESC`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Client{}
	for rows.Next() {
		c, err := scanClient(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

func insertClient(c *Client) error {
	c.CreatedAt, c.UpdatedAt = nowMs(), nowMs()
	res, err := db.Exec(`INSERT INTO clients
    (phone,password_hash,email,last_name,first_name,middle_name,address,segment,
     source,note,archived,blocked,created_at,updated_at)
    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
		c.Phone, c.PasswordHash, c.Email, c.LastName, c.FirstName, c.MiddleName,
		c.Address, c.Segment, c.Source, c.Note, c.Archived, c.Blocked,
		c.CreatedAt, c.UpdatedAt)
	if err != nil {
		return err
	}
	c.ID, err = res.LastInsertId()
	return err
}

func updateClient(c *Client) error {
	c.UpdatedAt = nowMs()
	_, err := db.Exec(`UPDATE clients SET phone=?,email=?,last_name=?,first_name=?,
    middle_name=?,address=?,segment=?,source=?,note=?,archived=?,blocked=?,updated_at=?
    WHERE id=?`,
		c.Phone, c.Email, c.LastName, c.FirstName, c.MiddleName, c.Address,
		c.Segment, c.Source, c.Note, c.Archived, c.Blocked, c.UpdatedAt, c.ID)
	return err
}

// setClientBlocked — блокировка или разблокировка доступа к сайту.
// При блокировке выкидываем активные сессии, чтобы вход закрылся сразу.
func setClientBlocked(id int64, blocked bool) error {
	if _, err := db.Exec(`UPDATE clients SET blocked=?, updated_at=? WHERE id=?`,
		blocked, nowMs(), id); err != nil {
		return err
	}
	if blocked {
		_, err := db.Exec(`DELETE FROM sessions WHERE kind='client' AND subject_id=?`, id)
		return err
	}
	return nil
}

// deleteClient удаляет карточку клиента. Заказы остаются целыми: у них есть
// снимок имени и телефона, а связь просто обнуляется (ON DELETE SET NULL).
func deleteClient(id int64) error {
	if _, err := db.Exec(`DELETE FROM sessions WHERE kind='client' AND subject_id=?`, id); err != nil {
		return err
	}
	res, err := db.Exec(`DELETE FROM clients WHERE id=?`, id)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return sql.ErrNoRows
	}
	return nil
}

func updateClientPassword(id int64, hash string) error {
	_, err := db.Exec(`UPDATE clients SET password_hash=?, updated_at=? WHERE id=?`,
		hash, nowMs(), id)
	return err
}

// clientStats — сумма выполненных заказов и их количество (LTV клиента).
func clientStats(clientID int64) (total float64, count int) {
	db.QueryRow(`SELECT COALESCE(SUM(total),0), COUNT(*) FROM orders
       WHERE client_id = ? AND status <> ?`, clientID, StatusCanceled).
		Scan(&total, &count)
	return
}

// ---------------------------------------------------------------- заказы

const orderCols = `id,client_id,client_name,phone,email,address,comment,delivery,
pay_method,status,total,source,stock_written,created_at,updated_at`

func scanOrder(s interface{ Scan(...any) error }) (Order, error) {
	var o Order
	var cid sql.NullInt64
	err := s.Scan(&o.ID, &cid, &o.ClientName, &o.Phone, &o.Email, &o.Address, &o.Comment,
		&o.Delivery, &o.PayMethod, &o.Status, &o.Total, &o.Source, &o.StockWritten,
		&o.CreatedAt, &o.UpdatedAt)
	if cid.Valid {
		v := cid.Int64
		o.ClientID = &v
	}
	return o, err
}

func queryOrders(where string, args ...any) ([]Order, error) {
	rows, err := db.Query(`SELECT `+orderCols+` FROM orders `+where, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Order{}
	for rows.Next() {
		o, err := scanOrder(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, o)
	}
	return out, rows.Err()
}

func listOrdersByClient(clientID int64) ([]Order, error) {
	return queryOrders(`WHERE client_id = ? ORDER BY created_at DESC`, clientID)
}

// listOrders — для админки; status < 0 означает «все статусы».
func listOrders(status int, search string) ([]Order, error) {
	where := `WHERE 1=1`
	args := []any{}
	if status >= 0 {
		where += ` AND status = ?`
		args = append(args, status)
	}
	if search != "" {
		where += ` AND (LOWER(client_name) LIKE ? OR phone LIKE ? OR CAST(id AS TEXT) LIKE ?)`
		q := "%" + strings.ToLower(search) + "%"
		args = append(args, q, q, q)
	}
	where += ` ORDER BY created_at DESC`
	return queryOrders(where, args...)
}

func getOrder(id int64) (Order, error) {
	row := db.QueryRow(`SELECT `+orderCols+` FROM orders WHERE id = ?`, id)
	o, err := scanOrder(row)
	if err != nil {
		return o, err
	}
	o.Items, err = listOrderItems(id)
	return o, err
}

func listOrderItems(orderID int64) ([]OrderItem, error) {
	rows, err := db.Query(`SELECT i.id,i.order_id,i.product_id,i.name,i.unit,i.qty,
      i.unit_price,i.unit_cost, COALESCE(p.image,'')
      FROM order_items i LEFT JOIN products p ON p.id = i.product_id
      WHERE i.order_id = ? ORDER BY i.id`, orderID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []OrderItem{}
	for rows.Next() {
		var it OrderItem
		var pid sql.NullInt64
		if err := rows.Scan(&it.ID, &it.OrderID, &pid, &it.Name, &it.Unit, &it.Qty,
			&it.UnitPrice, &it.UnitCost, &it.Image); err != nil {
			return nil, err
		}
		if pid.Valid {
			v := pid.Int64
			it.ProductID = &v
		}
		out = append(out, it)
	}
	return out, rows.Err()
}

// createOrder — заказ покупателя с витрины: позиции берутся из корзины,
// склад списывается всегда.
//
// Если у покупателя уже есть открытый заказ с тем же телефоном, новый не
// заводится: товары дописываются в тот заказ (см. merge.go), и продавцу
// не приходится звонить одному человеку дважды.
func createOrder(o *Order, lines []CartLine) error {
	items := make([]OrderItem, 0, len(lines))
	for _, l := range lines {
		id := l.Product.ID
		items = append(items, OrderItem{
			ProductID: &id,
			Name:      l.Product.Name,
			Unit:      l.Product.Unit,
			Qty:       l.Qty,
			UnitPrice: l.Product.Price,
			UnitCost:  l.Product.Cost,
		})
	}
	if target, ok := findOpenOrderByPhone(o.Phone); ok {
		return appendToOrder(target, o, items)
	}
	return createOrderItems(o, items, true)
}

// createOrderItems — заказ и позиции пишутся одной транзакцией, склад списывается
// сразу, иначе два одновременных заказа могут увести остаток в минус.
// Списание отключается для продаж из приложения, где товар со склада не уходит,
// а o.CreatedAt можно задать заранее — продажа могла состояться вчера.
func createOrderItems(o *Order, items []OrderItem, writeOff bool) error {
	tx, err := db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()

	if o.CreatedAt == 0 {
		o.CreatedAt = nowMs()
	}
	o.UpdatedAt = nowMs()
	o.Total = 0
	for _, it := range items {
		o.Total += it.Sum()
	}

	o.StockWritten = writeOff
	res, err := tx.Exec(`INSERT INTO orders
    (client_id,client_name,phone,email,address,comment,delivery,pay_method,status,
     total,source,stock_written,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
		o.ClientID, o.ClientName, o.Phone, o.Email, o.Address, o.Comment, o.Delivery,
		o.PayMethod, o.Status, o.Total, o.Source, o.StockWritten, o.CreatedAt, o.UpdatedAt)
	if err != nil {
		return err
	}
	if o.ID, err = res.LastInsertId(); err != nil {
		return err
	}

	for _, it := range items {
		if _, err := tx.Exec(`INSERT INTO order_items
        (order_id,product_id,name,unit,qty,unit_price,unit_cost)
        VALUES (?,?,?,?,?,?,?)`,
			o.ID, it.ProductID, it.Name, it.Unit, it.Qty,
			it.UnitPrice, it.UnitCost); err != nil {
			return err
		}
		// Списываем остаток. В минус уводить можно и нужно: заказать разрешено
		// больше, чем лежит на складе, и минус показывает, сколько доготовить.
		// Обрезать по нулю нельзя — тогда отмена такого заказа вернула бы на
		// склад больше, чем с него ушло.
		if !writeOff || it.ProductID == nil {
			continue
		}
		if _, err := tx.Exec(`UPDATE products SET stock = stock - ?, updated_at = ?
          WHERE id = ?`, it.Qty, nowMs(), *it.ProductID); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// setOrderItemQty меняет количество в позиции заказа, а qty <= 0 убирает позицию.
// Нужно, когда покупатель по телефону просит меньше или отказывается от товара.
//
// Разницу возвращаем на склад (или досписываем) — но только если товар этого
// заказа сейчас списан. Сумма заказа пересчитывается по оставшимся позициям.
// Последнюю позицию удалить нельзя: заказ без товаров — это отмена, для неё
// есть статус, который сам вернёт весь товар на склад.
func setOrderItemQty(orderID, itemID int64, qty float64) error {
	tx, err := db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()

	var status int
	var stockWritten bool
	if err := tx.QueryRow(`SELECT status, stock_written FROM orders WHERE id = ?`,
		orderID).Scan(&status, &stockWritten); err != nil {
		return err
	}
	if status == StatusCanceled {
		return errOrderCanceled
	}

	var oldQty float64
	var productID sql.NullInt64
	if err := tx.QueryRow(`SELECT qty, product_id FROM order_items
      WHERE id = ? AND order_id = ?`, itemID, orderID).Scan(&oldQty, &productID); err != nil {
		return err
	}

	var left int
	if err := tx.QueryRow(`SELECT COUNT(*) FROM order_items WHERE order_id = ?`,
		orderID).Scan(&left); err != nil {
		return err
	}
	if qty <= 0 && left <= 1 {
		return errLastItem
	}

	if qty <= 0 {
		if _, err := tx.Exec(`DELETE FROM order_items WHERE id = ?`, itemID); err != nil {
			return err
		}
		qty = 0
	} else if _, err := tx.Exec(`UPDATE order_items SET qty = ? WHERE id = ?`,
		qty, itemID); err != nil {
		return err
	}

	// Взяли меньше — разница возвращается на склад, больше — досписывается.
	if stockWritten && productID.Valid {
		if _, err := tx.Exec(`UPDATE products SET stock = stock + ?, updated_at = ?
          WHERE id = ?`, oldQty-qty, nowMs(), productID.Int64); err != nil {
			return err
		}
	}

	if _, err := tx.Exec(`UPDATE orders SET total = (
          SELECT COALESCE(SUM(qty * unit_price), 0) FROM order_items WHERE order_id = ?
        ), updated_at = ? WHERE id = ?`, orderID, nowMs(), orderID); err != nil {
		return err
	}
	return tx.Commit()
}

// addOrderItem дописывает товар в заказ — покупатель по телефону решил взять ещё.
// Цена и себестоимость берутся текущие: это снимок на момент дозаказа.
// Если такой товар в заказе уже есть, количество складывается.
func addOrderItem(orderID, productID int64, qty float64) error {
	if qty <= 0 {
		return errBadQty
	}
	p, err := getProduct(productID)
	if err != nil {
		return err
	}

	tx, err := db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()

	var status int
	var stockWritten bool
	if err := tx.QueryRow(`SELECT status, stock_written FROM orders WHERE id = ?`,
		orderID).Scan(&status, &stockWritten); err != nil {
		return err
	}
	if status == StatusCanceled {
		return errOrderCanceled
	}

	var itemID int64
	var oldQty float64
	err = tx.QueryRow(`SELECT id, qty FROM order_items
      WHERE order_id = ? AND product_id = ?`, orderID, productID).Scan(&itemID, &oldQty)
	switch {
	case err == nil:
		if _, err := tx.Exec(`UPDATE order_items SET qty = ? WHERE id = ?`,
			oldQty+qty, itemID); err != nil {
			return err
		}
	case errors.Is(err, sql.ErrNoRows):
		if _, err := tx.Exec(`INSERT INTO order_items
          (order_id,product_id,name,unit,qty,unit_price,unit_cost) VALUES (?,?,?,?,?,?,?)`,
			orderID, productID, p.Name, p.Unit, qty, p.Price, p.Cost); err != nil {
			return err
		}
	default:
		return err
	}

	if stockWritten {
		if _, err := tx.Exec(`UPDATE products SET stock = stock - ?, updated_at = ?
          WHERE id = ?`, qty, nowMs(), productID); err != nil {
			return err
		}
	}
	if _, err := tx.Exec(`UPDATE orders SET total = (
          SELECT COALESCE(SUM(qty * unit_price), 0) FROM order_items WHERE order_id = ?
        ), updated_at = ? WHERE id = ?`, orderID, nowMs(), orderID); err != nil {
		return err
	}
	return tx.Commit()
}

// Ошибки правки состава — по ним API отвечает понятным текстом.
var (
	errOrderCanceled = errors.New("заказ отменён")
	errLastItem      = errors.New("последняя позиция")
	errBadQty        = errors.New("некорректное количество")
)

// setOrderStatus — смена статуса. При отмене возвращаем товар на склад.
func setOrderStatus(id int64, status int) error {
	tx, err := db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()

	var old int
	var stockWritten bool
	if err := tx.QueryRow(`SELECT status, stock_written FROM orders WHERE id = ?`,
		id).Scan(&old, &stockWritten); err != nil {
		return err
	}
	if old == status {
		return tx.Commit()
	}
	if _, err := tx.Exec(`UPDATE orders SET status = ?, updated_at = ? WHERE id = ?`,
		status, nowMs(), id); err != nil {
		return err
	}

	// Отметка stock_written говорит, лежит ли товар этого заказа списанным
	// прямо сейчас. При отмене возвращаем его на склад, при возврате заказа
	// в работу — списываем снова. Заказ, по которому склад и так не двигали,
	// отмена не трогает: возвращать нечего.
	switch {
	case status == StatusCanceled && stockWritten:
		if err := adjustStock(tx, id, +1); err != nil {
			return err
		}
		if err := markStockWritten(tx, id, false); err != nil {
			return err
		}
	case old == StatusCanceled && status != StatusCanceled && !stockWritten:
		if err := adjustStock(tx, id, -1); err != nil {
			return err
		}
		if err := markStockWritten(tx, id, true); err != nil {
			return err
		}
	}
	return tx.Commit()
}

func markStockWritten(tx *sql.Tx, id int64, written bool) error {
	_, err := tx.Exec(`UPDATE orders SET stock_written = ?, updated_at = ? WHERE id = ?`,
		written, nowMs(), id)
	return err
}

// setOrderStockWritten — списать заказ со склада вручную или вернуть товар обратно.
// Нужно, когда остаток разошёлся с жизнью: например, продажу завели в приложении
// задним числом и склад тогда не двигали. Повторно одно и то же не списываем —
// смотрим на текущую отметку stock_written.
func setOrderStockWritten(id int64, written bool) error {
	tx, err := db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()

	var current bool
	if err := tx.QueryRow(`SELECT stock_written FROM orders WHERE id = ?`, id).Scan(&current); err != nil {
		return err
	}
	if current == written {
		return tx.Commit()
	}
	sign := 1.0 // возвращаем на склад
	if written {
		sign = -1.0 // списываем
	}
	if err := adjustStock(tx, id, sign); err != nil {
		return err
	}
	if err := markStockWritten(tx, id, written); err != nil {
		return err
	}
	return tx.Commit()
}

// adjustStock — прибавляет (sign=+1) или списывает (sign=-1) позиции заказа со склада.
func adjustStock(tx *sql.Tx, orderID int64, sign float64) error {
	rows, err := tx.Query(`SELECT product_id, qty FROM order_items
      WHERE order_id = ? AND product_id IS NOT NULL`, orderID)
	if err != nil {
		return err
	}
	type mv struct {
		id  int64
		qty float64
	}
	moves := []mv{}
	for rows.Next() {
		var m mv
		if err := rows.Scan(&m.id, &m.qty); err != nil {
			rows.Close()
			return err
		}
		moves = append(moves, m)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}
	for _, m := range moves {
		// Без обрезки по нулю: движение должно быть обратимым, иначе отмена
		// заказа, ушедшего в минус, вернула бы на склад лишнее.
		if _, err := tx.Exec(`UPDATE products SET stock = stock + ?, updated_at = ?
          WHERE id = ?`, sign*m.qty, nowMs(), m.id); err != nil {
			return err
		}
	}
	return nil
}

// removeOrder удаляет один заказ.
//
// Перед удалением заказ со всем составом ложится копией рядом с базой — как и
// при чистке архива: удаление из приложения делается одним нажатием, и вернуть
// случайно стёртый заказ должно быть откуда.
//
// Товар, который числился списанным, возвращается на склад: заказа больше нет,
// значит и остаток за ним не числится. У отменённого заказа склад уже
// возвращён при отмене, поэтому второй раз не двигаем.
func removeOrder(id int64, dumpDir string) error {
	o, err := getOrder(id)
	if err != nil {
		return err
	}
	if err := dumpOrders([]Order{o}, dumpDir); err != nil {
		// Не смогли сохранить копию — не удаляем: это слишком дорого.
		return err
	}

	tx, err := db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()

	if o.StockWritten {
		if err := adjustStock(tx, id, +1); err != nil {
			return err
		}
	}
	if _, err := tx.Exec(`DELETE FROM orders WHERE id = ?`, id); err != nil {
		return err
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	log.Printf("удалён заказ %s на %s", o.Number(), money(o.Total))
	return nil
}

// ------------------------------------------------------------- статистика

// Stats — сводка для дашборда админки.
type Stats struct {
	Revenue     float64
	Profit      float64
	OrdersTotal int
	OrdersNew   int
	Clients     int
	Products    int
	StockValue  float64
	LowStock    int
	AvgCheck    float64
}

func dashboardStats() (Stats, error) {
	var s Stats
	// Выручка и прибыль — по невыполненным заказам тоже, кроме отменённых.
	db.QueryRow(`SELECT COALESCE(SUM(total),0), COUNT(*) FROM orders WHERE status <> ?`,
		StatusCanceled).Scan(&s.Revenue, &s.OrdersTotal)
	db.QueryRow(`SELECT COALESCE(SUM(i.qty*(i.unit_price-i.unit_cost)),0)
      FROM order_items i JOIN orders o ON o.id = i.order_id
      WHERE o.status <> ?`, StatusCanceled).Scan(&s.Profit)
	db.QueryRow(`SELECT COUNT(*) FROM orders WHERE status = ?`, StatusNew).Scan(&s.OrdersNew)
	db.QueryRow(`SELECT COUNT(*) FROM clients WHERE archived = 0`).Scan(&s.Clients)
	db.QueryRow(`SELECT COUNT(*), COALESCE(SUM(stock*cost),0) FROM products
      WHERE archived = 0`).Scan(&s.Products, &s.StockValue)
	db.QueryRow(`SELECT COUNT(*) FROM products
      WHERE archived = 0 AND min_stock > 0 AND stock <= min_stock`).Scan(&s.LowStock)
	if s.OrdersTotal > 0 {
		s.AvgCheck = s.Revenue / float64(s.OrdersTotal)
	}
	return s, nil
}

// TopRow — строка рейтинга «топ товаров».
type TopRow struct {
	Name string
	Qty  float64
	Sum  float64
}

func topProducts(limit int) ([]TopRow, error) {
	rows, err := db.Query(`SELECT i.name, SUM(i.qty), SUM(i.qty*i.unit_price)
      FROM order_items i JOIN orders o ON o.id = i.order_id
      WHERE o.status <> ? GROUP BY i.name ORDER BY SUM(i.qty*i.unit_price) DESC
      LIMIT ?`, StatusCanceled, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []TopRow{}
	for rows.Next() {
		var t TopRow
		if err := rows.Scan(&t.Name, &t.Qty, &t.Sum); err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

func lowStockProducts() ([]Product, error) {
	return queryProducts(`WHERE archived = 0 AND min_stock > 0 AND stock <= min_stock
      ORDER BY stock`)
}

// purgeOrders удаляет заказы: только выполненные (onlyDone) или все.
//
// Склад при этом не трогаем: товар покупателям уже отдан, и возврат остатков
// при чистке архива только запутал бы учёт. Перед удалением заказы
// выгружаются в файл рядом с базой — на случай «удалил не то».
//
// Возвращает, сколько заказов удалено.
func purgeOrders(onlyDone bool, dumpDir string) (int, error) {
	where := ""
	args := []any{}
	if onlyDone {
		where = ` WHERE status = ?`
		args = append(args, StatusDone)
	}

	orders, err := queryOrders(where, args...)
	if err != nil {
		return 0, err
	}
	if len(orders) == 0 {
		return 0, nil
	}
	for i := range orders {
		orders[i].Items, _ = listOrderItems(orders[i].ID)
	}
	if err := dumpOrders(orders, dumpDir); err != nil {
		// Не смогли сохранить копию — удалять не будем, это слишком дорого.
		return 0, err
	}

	res, err := db.Exec(`DELETE FROM orders`+where, args...)
	if err != nil {
		return 0, err
	}
	n, _ := res.RowsAffected()
	log.Printf("удалено заказов: %d (только выполненные: %v)", n, onlyDone)
	return int(n), nil
}

// dumpOrders сохраняет заказы в JSON рядом с базой перед удалением.
func dumpOrders(orders []Order, dir string) error {
	if dir == "" {
		dir = "."
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	out := make([]orderJSON, 0, len(orders))
	for _, o := range orders {
		out = append(out, toOrderJSON(o))
	}
	data, err := json.MarshalIndent(map[string]any{
		"deletedAt": nowMs(),
		"count":     len(out),
		"orders":    out,
	}, "", "  ")
	if err != nil {
		return err
	}
	name := "orders-deleted-" + time.Now().Format("2006-01-02_15-04-05") + ".json"
	return os.WriteFile(filepath.Join(dir, name), data, 0o644)
}
