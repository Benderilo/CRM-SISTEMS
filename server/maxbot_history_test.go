package main

import (
	"context"
	"testing"
)

// Чат не отдал историю — окно «разобрано до» не двигаем.
//
// Это главное: сдвинутое поверх непрочитанного чата окно уносит пропущенные
// сообщения за горизонт навсегда, и заявки по ним не появятся уже никогда.
// Повторный проход ничем не грозит: сообщения отмечены в bot_seen.
func TestCatchUpKeepsWindowWhenChatFails(t *testing.T) {
	openTestDB(t)
	resetSiteCache()
	setCatchPhones(true)
	t.Cleanup(func() { setCatchPhones(false) })
	f := newFakeMax(t)
	api := newMaxAPI("test-token")
	ctx := context.Background()

	before := siteSetting(keyCatchUpAt)

	f.mu.Lock()
	f.failMessages = true
	f.mu.Unlock()
	catchUpHistory(ctx, api)

	if got := siteSetting(keyCatchUpAt); got != before {
		t.Fatalf("окно сдвинулось, хотя история чата не прочиталась: %q → %q",
			before, got)
	}

	// Связь вернулась — пропущенное сообщение всё ещё в окне и заводит заявку.
	missed := newMessage(52, fakeChat, "привезите 2 кг колбасы, 89004445566")
	missed.Body.MID = "mid.missed"
	missed.Timestamp = nowMs()
	f.mu.Lock()
	f.failMessages = false
	f.history = []botMessage{*missed}
	f.mu.Unlock()

	catchUpHistory(ctx, api)

	orders, err := listOrders(-1, "")
	if err != nil || len(orders) != 1 {
		t.Fatalf("заявок %d (err %v), ожидалась одна — сообщение потеряно",
			len(orders), err)
	}
	if siteSetting(keyCatchUpAt) == before {
		t.Error("после удачного догона окно так и не сдвинулось")
	}
}

// Список чатов не пришёл — пробуем второй раз. Обычный ответ приходит за
// сотые доли секунды, поэтому зависший запрос — почти всегда разовая заминка.
func TestCatchUpRetriesChatList(t *testing.T) {
	openTestDB(t)
	resetSiteCache()
	setCatchPhones(true)
	t.Cleanup(func() { setCatchPhones(false) })
	f := newFakeMax(t)
	api := newMaxAPI("test-token")
	ctx := context.Background()

	missed := newMessage(53, fakeChat, "нужен фарш, 89004445577")
	missed.Body.MID = "mid.retry"
	missed.Timestamp = nowMs()
	f.mu.Lock()
	f.chatsFail = 1 // первый запрос отвалится, второй пройдёт
	f.history = []botMessage{*missed}
	f.mu.Unlock()

	catchUpHistory(ctx, api)

	f.mu.Lock()
	calls := f.chatsCalls
	f.mu.Unlock()
	if calls != 2 {
		t.Errorf("запросов списка чатов %d, ожидалось два — повтора не было", calls)
	}
	orders, err := listOrders(-1, "")
	if err != nil || len(orders) != 1 {
		t.Fatalf("заявок %d (err %v) — повтор не спас догон", len(orders), err)
	}
}

// Список чатов не пришёл совсем — окно не двигаем и молчим до третьей
// неудачи подряд: одна заминка раз в несколько часов это не авария.
func TestCatchUpQuietOnSingleFailure(t *testing.T) {
	openTestDB(t)
	resetSiteCache()
	setCatchPhones(true)
	t.Cleanup(func() { setCatchPhones(false) })
	f := newFakeMax(t)
	api := newMaxAPI("test-token")
	ctx := context.Background()
	catchUpFails = 0
	t.Cleanup(func() { catchUpFails = 0 })

	before := siteSetting(keyCatchUpAt)
	f.mu.Lock()
	f.chatsFail = 2 // обе попытки мимо
	f.mu.Unlock()

	catchUpHistory(ctx, api)

	if got := siteSetting(keyCatchUpAt); got != before {
		t.Errorf("окно сдвинулось после полной неудачи: %q → %q", before, got)
	}
	if catchUpFails != 1 {
		t.Errorf("неудачи считаются неверно: %d, ожидалась одна", catchUpFails)
	}
}
