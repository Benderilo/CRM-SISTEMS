package main

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

// Уборка сносит только те картинки, на которые никто не ссылается,
// и щадит только что загруженные — карточка могла ещё не сохраниться.
func TestPurgeUnusedPhotos(t *testing.T) {
	openTestDB(t)
	dir := t.TempDir()

	write := func(name string, age time.Duration) string {
		path := filepath.Join(dir, name)
		if err := os.WriteFile(path, []byte("картинка"), 0o644); err != nil {
			t.Fatalf("файл %s: %v", name, err)
		}
		old := time.Now().Add(-age)
		if err := os.Chtimes(path, old, old); err != nil {
			t.Fatalf("время файла %s: %v", name, err)
		}
		return path
	}

	inUse := write("used.jpg", 48*time.Hour)
	orphan := write("orphan.jpg", 48*time.Hour)
	fresh := write("fresh.jpg", time.Minute)

	p := testProduct(t, "Пельмени", "кг", 400, 10)
	p.Image = "/photo/used.jpg"
	if err := updateProduct(&p); err != nil {
		t.Fatalf("картинка товара: %v", err)
	}

	removed, freed, err := purgeUnusedPhotos(dir, photoGrace)
	if err != nil {
		t.Fatalf("уборка: %v", err)
	}
	if removed != 1 || freed == 0 {
		t.Fatalf("убрано файлов %d, освобождено %d байт", removed, freed)
	}
	if _, err := os.Stat(inUse); err != nil {
		t.Error("удалили картинку, которая используется")
	}
	if _, err := os.Stat(fresh); err != nil {
		t.Error("удалили только что загруженную картинку")
	}
	if _, err := os.Stat(orphan); !os.IsNotExist(err) {
		t.Error("ничейная картинка осталась")
	}

	// Архивный товар тоже держит своё фото: его могут вернуть из архива.
	p.Archived = true
	if err := updateProduct(&p); err != nil {
		t.Fatalf("архивация: %v", err)
	}
	if removed, _, err := purgeUnusedPhotos(dir, photoGrace); err != nil || removed != 0 {
		t.Errorf("после архивации убрано %d (err %v)", removed, err)
	}
	if _, err := os.Stat(inUse); err != nil {
		t.Error("картинка архивного товара удалена")
	}
}

// Имя файла достаём из пути карточки, даже если к нему приписан параметр.
func TestPhotoFileName(t *testing.T) {
	cases := map[string]string{
		"/photo/abc.jpg":           "abc.jpg",
		"/photo/abc.jpg?v=2":       "abc.jpg",
		"https://s.ru/photo/x.png": "x.png",
		"":                         "",
	}
	for in, want := range cases {
		if got := photoFileName(in); got != want {
			t.Errorf("photoFileName(%q) = %q, ожидалось %q", in, got, want)
		}
	}
}
