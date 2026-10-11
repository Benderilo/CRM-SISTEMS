package main

import (
	"log"
	"time"
)

// Ночной перезапуск бота.
//
// Опрос MAX иногда затихает: связь есть, ошибок нет, а обновления перестают
// приходить — со стороны это выглядит как «бот отвалился». Разбираться с
// чужим сервером нечем, поэтому раз в сутки, глухой ночью, опрос поднимается
// заново: старый контекст отменяется, http-клиент и маркер создаются с нуля,
// заодно перечитываются настройки. Для покупателей это незаметно —
// переписка живёт в базе, а не в памяти опроса.
//
// Час перезапуска задаётся в приложении; сам перезапуск можно выключить там же.

const (
	// defaultRestartHour — 4 часа ночи по Москве: заказов в это время нет.
	defaultRestartHour = 4
	// restartCheckEvery — как часто сторож смотрит на часы. Минуты хватает:
	// точность перезапуска до минуты никому не нужна.
	restartCheckEvery = time.Minute
)

// normalizeRestartHour приводит час к 0–23: в настройках может оказаться
// что угодно, а перезапуск должен случиться в понятное время.
func normalizeRestartHour(h int) int {
	if h < 0 || h > 23 {
		return defaultRestartHour
	}
	return h
}

// restartSlot — сегодняшний момент перезапуска по часам продавца.
func restartSlot(now time.Time, hour int) time.Time {
	return time.Date(now.Year(), now.Month(), now.Day(),
		normalizeRestartHour(hour), 0, 0, 0, now.Location())
}

// dueForRestart — пора ли поднимать опрос заново: час X сегодня уже наступил,
// а нынешний опрос стартовал раньше него, то есть живёт со вчерашнего дня.
//
// Отсюда же следует, что после перезапуска второго за сутки не будет: у
// свежего опроса startedAt позже слота.
func dueForRestart(started, now time.Time, hour int) bool {
	if started.IsZero() {
		return false
	}
	slot := restartSlot(now, hour)
	return !now.Before(slot) && started.Before(slot)
}

// nextRestartAt — когда перезапуск случится в следующий раз. Нужно только
// приложению: показать строкой «следующий — завтра в 04:00».
func nextRestartAt(now time.Time, hour int) time.Time {
	slot := restartSlot(now, hour)
	if !now.Before(slot) {
		slot = slot.AddDate(0, 0, 1)
	}
	return slot
}

// startBotWatch запускает сторожа. Живёт всё время работы сервиса: бота могут
// включить из приложения через неделю после старта.
func startBotWatch() {
	go func() {
		for {
			time.Sleep(restartCheckEvery)
			bot.restartIfDue(time.Now().In(moscow))
		}
	}()
}

// restartIfDue — проверка по часам, вызывается раз в минуту.
func (r *botRunner) restartIfDue(now time.Time) {
	c := loadBotConfig()
	if !c.RestartDaily {
		return
	}
	r.mu.Lock()
	due := r.running && dueForRestart(r.startedAt, now, c.RestartHour)
	r.mu.Unlock()
	if due {
		r.restart("по расписанию")
	}
}

// restart поднимает опрос заново и запоминает, когда и почему.
func (r *botRunner) restart(reason string) {
	r.mu.Lock()
	r.lastRestart = time.Now()
	r.restartReason = reason
	r.mu.Unlock()
	log.Printf("бот MAX: перезапуск %s", reason)
	r.apply()
}

// runningNow — идёт ли опрос прямо сейчас.
func (r *botRunner) runningNow() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.running
}

// restartState — что показать в приложении про перезапуски.
func (r *botRunner) restartState() (started, last time.Time, reason string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.startedAt, r.lastRestart, r.restartReason
}
