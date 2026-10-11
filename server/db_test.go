package main

import (
	"os"
	"path/filepath"
	"testing"
)

// Пока служба работает, SQLite держит свежие записи в журнале shop.db-wal,
// а сам shop.db остаётся почти пустым. Резервная копия одного shop.db в таком
// виде бесполезна, поэтому при остановке служба закрывает базу — SQLite
// переносит журнал внутрь файла. Тест сторожит именно это поведение.
func TestCloseFoldsWalIntoDatabaseFile(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "shop.db")

	if err := openDB(path); err != nil {
		t.Fatalf("открытие базы: %v", err)
	}
	for i := 0; i < 200; i++ {
		if _, err := db.Exec(`INSERT INTO products (name,unit,price,cost,stock)
        VALUES (?,?,?,?,?)`, "Товар", "шт", 100.0, 50.0, 5.0); err != nil {
			t.Fatalf("вставка товара: %v", err)
		}
	}

	if size(t, path) > size(t, path+"-wal") {
		t.Skip("журнал не задействован — проверять нечего")
	}

	if err := db.Close(); err != nil {
		t.Fatalf("закрытие базы: %v", err)
	}

	// После закрытия данные должны лежать в самом файле базы, а журнал — опустеть.
	if got := size(t, path); got < 20*1024 {
		t.Errorf("shop.db после закрытия %d байт — журнал не перенесён в базу", got)
	}
	if got := size(t, path+"-wal"); got > 4*1024 {
		t.Errorf("shop.db-wal после закрытия %d байт — журнал не сброшен", got)
	}

	// И база должна открываться заново без файлов журнала: именно так выглядит
	// восстановление из копии, снятой при остановленной службе.
	os.Remove(path + "-wal")
	os.Remove(path + "-shm")
	if err := openDB(path); err != nil {
		t.Fatalf("повторное открытие: %v", err)
	}
	defer db.Close()

	var n int
	if err := db.QueryRow(`SELECT COUNT(*) FROM products`).Scan(&n); err != nil {
		t.Fatalf("чтение товаров: %v", err)
	}
	if n != 200 {
		t.Errorf("после восстановления товаров %d, ожидалось 200", n)
	}
}

func size(t *testing.T, path string) int64 {
	t.Helper()
	info, err := os.Stat(path)
	if err != nil {
		if os.IsNotExist(err) {
			return 0
		}
		t.Fatalf("stat %s: %v", path, err)
	}
	return info.Size()
}

// Блокировка и удаление клиента: заблокированный теряет сессии, при удалении
// карточка исчезает, а заказ остаётся со снимком имени (ON DELETE SET NULL).
func TestClientBlockAndDelete(t *testing.T) {
	dir := t.TempDir()
	if err := openDB(filepath.Join(dir, "shop.db")); err != nil {
		t.Fatalf("открытие базы: %v", err)
	}
	defer db.Close()

	c := Client{Phone: "+79990001122", LastName: "Иванов", FirstName: "Иван",
		PasswordHash: "x", Segment: "Новый", Source: "Сайт"}
	if err := insertClient(&c); err != nil {
		t.Fatalf("вставка клиента: %v", err)
	}
	if _, err := db.Exec(`INSERT INTO sessions (token,kind,subject_id,created_at,expires_at)
      VALUES ('tok1','client',?,0,9999999999999)`, c.ID); err != nil {
		t.Fatalf("вставка сессии: %v", err)
	}
	o := Order{ClientName: "Иванов Иван", Phone: c.Phone, Status: StatusNew}
	if err := createOrderItems(&o, []OrderItem{{Name: "Фарш", Unit: "кг",
		Qty: 1, UnitPrice: 500, UnitCost: 300}}, true); err != nil {
		t.Fatalf("создание заказа: %v", err)
	}

	// Блокировка закрывает активные сессии.
	if err := setClientBlocked(c.ID, true); err != nil {
		t.Fatalf("блокировка: %v", err)
	}
	got, err := getClient(c.ID)
	if err != nil || !got.Blocked {
		t.Fatalf("после блокировки Blocked = %v (err %v)", got.Blocked, err)
	}
	var n int
	db.QueryRow(`SELECT COUNT(*) FROM sessions WHERE kind='client' AND subject_id=?`,
		c.ID).Scan(&n)
	if n != 0 {
		t.Fatalf("сессий у заблокированного осталось %d", n)
	}
	if err := setClientBlocked(c.ID, false); err != nil {
		t.Fatalf("разблокировка: %v", err)
	}

	// Удаление: карточка исчезает, заказ остаётся, связь обнуляется.
	if err := deleteClient(c.ID); err != nil {
		t.Fatalf("удаление клиента: %v", err)
	}
	if _, err := getClient(c.ID); err == nil {
		t.Fatal("клиент не удалился")
	}
	if err := deleteClient(c.ID); err == nil {
		t.Fatal("повторное удаление должно вернуть ошибку")
	}
	var cid any
	if err := db.QueryRow(`SELECT client_id FROM orders WHERE id = ?`, o.ID).Scan(&cid); err != nil {
		t.Fatalf("чтение заказа: %v", err)
	}
	if cid != nil {
		t.Fatalf("связь заказа не обнулилась: %v", cid)
	}
	var name string
	db.QueryRow(`SELECT client_name FROM orders WHERE id = ?`, o.ID).Scan(&name)
	if name != "Иванов Иван" {
		t.Fatalf("снимок имени в заказе потерялся: %q", name)
	}
}
