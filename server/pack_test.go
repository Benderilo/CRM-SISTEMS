package main

import (
	"reflect"
	"testing"
)

func TestPackOrderIDs(t *testing.T) {
	cases := map[string][]int64{
		"p12|79270523370|[5]":   {5},
		"Блины|кг|id7|[7, 9]":   {7, 9},
		"p3|79270523370|":       nil,
		"без хвоста":            nil,
		"p3|79270523370|[x, 4]": {4},
	}
	for mark, want := range cases {
		if got := packOrderIDs(mark); !reflect.DeepEqual(got, want) {
			t.Errorf("%q: %v, ждали %v", mark, got, want)
		}
	}
}

// Отметки выполненных, отменённых и удалённых заказов сервер убирает сам,
// а пока хоть один заказ покупателя в работе — держит.
func TestPackMarksDropFinishedOrders(t *testing.T) {
	openTestDB(t)
	for id, status := range map[int64]int{1: 1, 2: 4, 3: 5, 4: 2} {
		if _, err := db.Exec(`INSERT INTO orders (id, status) VALUES (?, ?)`, id, status); err != nil {
			t.Fatalf("заказ: %v", err)
		}
	}
	err := setPackMarks([]string{
		"p1|a|[1]",    // принят — остаётся
		"p1|b|[2]",    // выполнен — уходит
		"p1|c|[3]",    // отменён — уходит
		"p1|d|[99]",   // удалён — уходит
		"p1|e|[2, 4]", // один выполнен, другой собирается — остаётся
	}, nil)
	if err != nil {
		t.Fatal(err)
	}
	got, err := packMarks()
	if err != nil {
		t.Fatal(err)
	}
	if want := []string{"p1|a|[1]", "p1|e|[2, 4]"}; !reflect.DeepEqual(got, want) {
		t.Fatalf("отметки %v, ждали %v", got, want)
	}

	if err := setPackMarks([]string{"p1|a|[1]"}, []string{"p1|e|[2, 4]"}); err != nil {
		t.Fatal(err)
	}
	got, _ = packMarks()
	if want := []string{"p1|a|[1]"}; !reflect.DeepEqual(got, want) {
		t.Fatalf("после снятия %v, ждали %v", got, want)
	}
}
