package main

import (
	"log"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// Уборка фотографий товаров.
//
// Каждая новая картинка ложится в папку загрузок отдельным файлом, а старая
// просто перестаёт упоминаться в карточке. За год такой работы диск (а его
// на сервере всего 10 ГБ) забивается снимками, которых никто не увидит.
// Раз в час проходим по папке и удаляем файлы, на которые не ссылается
// ни один товар.
//
// Свежие файлы не трогаем: между загрузкой фото и сохранением карточки
// проходит несколько секунд, и в это время картинка ещё «ничья».

// photoGrace — сколько картинка живёт, даже если на неё пока никто не ссылается.
const photoGrace = 24 * time.Hour

// purgeUnusedPhotos удаляет неиспользуемые картинки и возвращает,
// сколько файлов убрано и сколько байт освободилось.
func purgeUnusedPhotos(dir string, grace time.Duration) (int, int64, error) {
	if dir == "" {
		return 0, 0, nil
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		if os.IsNotExist(err) {
			return 0, 0, nil
		}
		return 0, 0, err
	}

	used, err := usedPhotoNames()
	if err != nil {
		return 0, 0, err
	}

	var removed int
	var freed int64
	cutoff := time.Now().Add(-grace)
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		if used[e.Name()] {
			continue
		}
		info, err := e.Info()
		if err != nil {
			continue
		}
		// Только что загруженное фото ещё может ждать сохранения карточки.
		if info.ModTime().After(cutoff) {
			continue
		}
		if err := os.Remove(filepath.Join(dir, e.Name())); err != nil {
			logErr("удаление картинки", err)
			continue
		}
		removed++
		freed += info.Size()
	}
	return removed, freed, nil
}

// usedPhotoNames — имена файлов, на которые ссылается хоть один товар.
// Архивные товары тоже считаются: их могут вернуть из архива.
func usedPhotoNames() (map[string]bool, error) {
	rows, err := db.Query(`SELECT image FROM products WHERE image <> ''`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	used := map[string]bool{}
	for rows.Next() {
		var image string
		if err := rows.Scan(&image); err != nil {
			continue
		}
		if name := photoFileName(image); name != "" {
			used[name] = true
		}
	}
	return used, rows.Err()
}

// photoFileName достаёт имя файла из пути вида «/photo/abc.jpg».
func photoFileName(image string) string {
	image = strings.TrimSpace(image)
	if image == "" {
		return ""
	}
	if i := strings.IndexAny(image, "?#"); i >= 0 {
		image = image[:i]
	}
	return filepath.Base(image)
}

// startPhotoCleanup запускает уборку: сразу после старта и дальше раз в час.
func startPhotoCleanup(dir string) {
	go func() {
		for {
			removed, freed, err := purgeUnusedPhotos(dir, photoGrace)
			if err != nil {
				logErr("уборка картинок", err)
			} else if removed > 0 {
				log.Printf("убрано неиспользуемых картинок: %d (освободилось %.1f МБ)",
					removed, float64(freed)/(1024*1024))
			}
			time.Sleep(time.Hour)
		}
	}()
}
