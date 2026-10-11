package main

import (
	"errors"
	"testing"
)

// Покупатель забыл пароль: продавец задаёт новый. Пустое поле означает
// «придумай сам» — вернувшийся пароль и диктуют по телефону.
func TestSetClientPassword(t *testing.T) {
	openTestDB(t)

	old, err := hashPassword("старый-пароль")
	if err != nil {
		t.Fatalf("хеш: %v", err)
	}
	c := Client{Phone: "+79990001122", FirstName: "Иван", PasswordHash: old}
	if err := insertClient(&c); err != nil {
		t.Fatalf("клиент: %v", err)
	}
	// Покупатель где-то залогинен — эта сессия должна закрыться.
	if _, err := createSession("client", c.ID); err != nil {
		t.Fatalf("сессия: %v", err)
	}

	pw, err := setClientPassword(c.ID, "")
	if err != nil {
		t.Fatalf("смена пароля: %v", err)
	}
	if len([]rune(pw)) < minPasswordLen {
		t.Fatalf("сгенерированный пароль слишком короткий: %q", pw)
	}

	got, err := getClient(c.ID)
	if err != nil {
		t.Fatalf("чтение клиента: %v", err)
	}
	if !checkPassword(pw, got.PasswordHash) {
		t.Errorf("новый пароль не подходит")
	}
	if checkPassword("старый-пароль", got.PasswordHash) {
		t.Errorf("старый пароль всё ещё работает")
	}

	var sessions int
	db.QueryRow(`SELECT COUNT(*) FROM sessions WHERE kind='client' AND subject_id=?`,
		c.ID).Scan(&sessions)
	if sessions != 0 {
		t.Errorf("осталось открытых сессий: %d", sessions)
	}

	// Свой пароль тоже можно задать — но не совсем короткий.
	if _, err := setClientPassword(c.ID, "мясо-2026"); err != nil {
		t.Fatalf("свой пароль: %v", err)
	}
	got, _ = getClient(c.ID)
	if !checkPassword("мясо-2026", got.PasswordHash) {
		t.Errorf("заданный вручную пароль не подходит")
	}

	if _, err := setClientPassword(c.ID, "123"); !errors.Is(err, errWeakPassword) {
		t.Errorf("короткий пароль приняли: %v", err)
	}
	got, _ = getClient(c.ID)
	if !checkPassword("мясо-2026", got.PasswordHash) {
		t.Errorf("после отказа пароль всё-таки поменялся")
	}
}

// Пароли не должны повторяться от вызова к вызову — иначе их можно угадать.
func TestRandomPasswordDiffers(t *testing.T) {
	seen := map[string]bool{}
	for i := 0; i < 20; i++ {
		pw := randomPassword()
		if len([]rune(pw)) < minPasswordLen {
			t.Fatalf("короткий пароль: %q", pw)
		}
		if seen[pw] {
			t.Fatalf("повтор пароля: %q", pw)
		}
		seen[pw] = true
	}
}
