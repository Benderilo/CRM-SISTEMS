package main

import (
	"testing"
	"time"
)

func at(day, hour, min int) time.Time {
	return time.Date(2026, 9, day, hour, min, 0, 0, moscow)
}

// Опрос, поднятый вчера вечером, ночью перезапускается — ровно один раз.
func TestDueForRestartRazVSutki(t *testing.T) {
	started := at(6, 20, 0)
	if dueForRestart(started, at(6, 23, 30), 4) {
		t.Error("до часа X перезапускать нечего")
	}
	if !dueForRestart(started, at(7, 4, 0), 4) {
		t.Error("в 04:00 опрос со вчерашнего вечера пора поднять заново")
	}
	// После перезапуска сторож должен успокоиться до следующей ночи.
	restarted := at(7, 4, 0)
	if dueForRestart(restarted, at(7, 4, 1), 4) || dueForRestart(restarted, at(7, 23, 0), 4) {
		t.Error("второй перезапуск за сутки")
	}
	if !dueForRestart(restarted, at(8, 4, 0), 4) {
		t.Error("следующей ночью перезапуск нужен снова")
	}
}

// Сервис, запущенный днём, не должен перезапускаться сразу же из-за того,
// что 04:00 сегодня уже прошли.
func TestDueForRestartPosleUtrennegoStarta(t *testing.T) {
	started := at(6, 10, 0)
	if dueForRestart(started, at(6, 10, 1), 4) {
		t.Error("час X сегодня уже прошёл — перезапуск только завтра")
	}
	if !dueForRestart(started, at(7, 4, 0), 4) {
		t.Error("на следующую ночь перезапуск нужен")
	}
}

// Выключенный бот сторожа не касается: startedAt пуст.
func TestDueForRestartVyklyuchennyyBot(t *testing.T) {
	if dueForRestart(time.Time{}, at(7, 4, 0), 4) {
		t.Error("у выключенного бота перезапускать нечего")
	}
}

// Час из настроек может оказаться любым — берём понятное значение.
func TestNormalizeRestartHour(t *testing.T) {
	for _, h := range []int{-1, 24, 99} {
		if got := normalizeRestartHour(h); got != defaultRestartHour {
			t.Errorf("час %d: получили %d, ждали %d", h, got, defaultRestartHour)
		}
	}
	if normalizeRestartHour(0) != 0 || normalizeRestartHour(23) != 23 {
		t.Error("полночь и 23 часа — нормальные значения")
	}
}

// Строка «следующий перезапуск» в приложении.
func TestNextRestartAt(t *testing.T) {
	if got := nextRestartAt(at(6, 3, 0), 4); !got.Equal(at(6, 4, 0)) {
		t.Errorf("ночью до часа X ждём сегодняшний слот, получили %v", got)
	}
	if got := nextRestartAt(at(6, 12, 0), 4); !got.Equal(at(7, 4, 0)) {
		t.Errorf("днём ждём завтрашний слот, получили %v", got)
	}
	// Ровно в час X слот уже отработан — следующий завтра.
	if got := nextRestartAt(at(6, 4, 0), 4); !got.Equal(at(7, 4, 0)) {
		t.Errorf("в сам час X ждём завтра, получили %v", got)
	}
}

// Сторож поднимает опрос заново: startedAt становится свежим, а в приложении
// видно, когда и почему бот перезапустился.
func TestStorozhPodnimaetOprosZanovo(t *testing.T) {
	openTestDB(t)
	newFakeMax(t)
	t.Cleanup(func() {
		saveBotConfig(botConfig{})
		bot.apply()
	})

	// Час 0 и «сейчас» на сутки с лишним позже старта: перезапуск точно назрел.
	if err := saveBotConfig(botConfig{Token: "t", Enabled: true,
		RestartDaily: true, RestartHour: 0}); err != nil {
		t.Fatalf("настройки бота: %v", err)
	}
	bot.apply()
	started, _, _ := bot.restartState()
	if started.IsZero() {
		t.Fatal("опрос не запустился")
	}

	bot.restartIfDue(started.Add(25 * time.Hour))

	// Сравниваем не по startedAt: на Windows часы идут ступеньками по 15 мс,
	// и запуск с перезапуском попадают в одну и ту же отметку времени.
	restarted, last, why := bot.restartState()
	if last.IsZero() || why != "по расписанию" {
		t.Fatalf("сторож не перезапустил бота: %v %q", last, why)
	}
	if restarted.Before(started) || !bot.runningNow() {
		t.Error("после перезапуска опрос должен идти дальше")
	}

	// Второй раз в те же сутки сторож молчит.
	time.Sleep(20 * time.Millisecond)
	bot.restartIfDue(restarted.Add(time.Minute))
	if _, again, _ := bot.restartState(); !again.Equal(last) {
		t.Error("лишний перезапуск в те же сутки")
	}
}

// Выключенный автоперезапуск бота не трогает.
func TestStorozhMolchitKogdaVyklyuchen(t *testing.T) {
	openTestDB(t)
	newFakeMax(t)
	t.Cleanup(func() {
		saveBotConfig(botConfig{})
		bot.apply()
	})

	if err := saveBotConfig(botConfig{Token: "t", Enabled: true,
		RestartDaily: false, RestartHour: 0}); err != nil {
		t.Fatalf("настройки бота: %v", err)
	}
	bot.apply()
	started, _, _ := bot.restartState()
	// Опрос в сервисе один на всех, и отметка перезапуска могла остаться
	// от соседнего теста — начинаем с чистого листа.
	bot.mu.Lock()
	bot.lastRestart, bot.restartReason = time.Time{}, ""
	bot.mu.Unlock()

	bot.restartIfDue(started.Add(25 * time.Hour))

	if _, last, _ := bot.restartState(); !last.IsZero() {
		t.Errorf("перезапуск при выключенном расписании: %v", last)
	}
}
