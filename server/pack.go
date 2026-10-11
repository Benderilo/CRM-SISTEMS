package main

import (
	"log"
	"net/http"
	"strconv"
	"strings"
)

// Галочки «положили» из «Сборки» в приложении. Собирают заказы с разных
// телефонов, поэтому отметки живут здесь, а телефоны их только читают и
// пишут.
//
// Ключ отметки сервер не разбирает, кроме хвоста: приложение кладёт в конец
// номера заказов покупателя с этим товаром — «…|[12, 15]». По ним сервер
// сам выбрасывает отметки заказов, которые выполнены, отменены или удалены:
// если чистку доверить телефонам, телефон со старым списком заказов стирал
// бы галочки, только что поставленные с другого.

const packSchema = `
CREATE TABLE IF NOT EXISTS pack_marks (
  mark       TEXT PRIMARY KEY,
  created_at INTEGER NOT NULL DEFAULT 0
);
`

// packOrderIDs достаёт номера заказов из хвоста ключа отметки.
func packOrderIDs(mark string) []int64 {
	open := strings.LastIndex(mark, "|[")
	if open < 0 || !strings.HasSuffix(mark, "]") {
		return nil
	}
	var ids []int64
	for s := range strings.SplitSeq(mark[open+2:len(mark)-1], ",") {
		if id, err := strconv.ParseInt(strings.TrimSpace(s), 10, 64); err == nil {
			ids = append(ids, id)
		}
	}
	return ids
}

// packMarks отдаёт отметки ещё не увезённых заказов, по дороге удаляя
// отметки тех, что уже выполнены, отменены или удалены.
func packMarks() ([]string, error) {
	live := map[int64]bool{}
	rows, err := db.Query(`SELECT id FROM orders WHERE status < 4`)
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		var id int64
		if rows.Scan(&id) == nil {
			live[id] = true
		}
	}
	rows.Close()

	rows, err = db.Query(`SELECT mark FROM pack_marks ORDER BY mark`)
	if err != nil {
		return nil, err
	}
	var marks, stale []string
	for rows.Next() {
		var m string
		if rows.Scan(&m) != nil {
			continue
		}
		alive := false
		for _, id := range packOrderIDs(m) {
			if live[id] {
				alive = true
				break
			}
		}
		if alive {
			marks = append(marks, m)
		} else {
			stale = append(stale, m)
		}
	}
	rows.Close()

	for _, m := range stale {
		if _, err := db.Exec(`DELETE FROM pack_marks WHERE mark = ?`, m); err != nil {
			log.Printf("сборка: чистка отметки: %v", err)
		}
	}
	if marks == nil {
		marks = []string{}
	}
	return marks, nil
}

// setPackMarks ставит и снимает отметки одним махом.
func setPackMarks(set, unset []string) error {
	tx, err := db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	for _, m := range set {
		if m == "" || len(m) > 1000 {
			continue
		}
		if _, err := tx.Exec(`INSERT OR IGNORE INTO pack_marks (mark, created_at)
        VALUES (?, ?)`, m, nowMs()); err != nil {
			return err
		}
	}
	for _, m := range unset {
		if _, err := tx.Exec(`DELETE FROM pack_marks WHERE mark = ?`, m); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// GET /api/v1/crm/packed — все отметки.
func apiCrmPacked(w http.ResponseWriter, r *http.Request) {
	marks, err := packMarks()
	if err != nil {
		logErr("сборка", err)
		apiErr(w, http.StatusInternalServerError, "Не удалось прочитать отметки")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "marks": marks})
}

// POST /api/v1/crm/packed {"set":[...], "unset":[...]} — отвечает всеми
// отметками после правки, чтобы телефону не ходить второй раз.
func apiCrmSavePacked(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Set   []string `json:"set"`
		Unset []string `json:"unset"`
	}
	if !readJSON(w, r, &in) {
		return
	}
	if err := setPackMarks(in.Set, in.Unset); err != nil {
		logErr("сборка", err)
		apiErr(w, http.StatusInternalServerError, "Не удалось сохранить отметки")
		return
	}
	apiCrmPacked(w, r)
}
