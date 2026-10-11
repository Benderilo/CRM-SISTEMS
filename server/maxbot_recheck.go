package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"strings"
	"time"
)

// Сверка заявки с перепиской: не разошлось ли то, что в заказе, с тем,
// что человек на самом деле просил.
//
// Заявка живёт несколько дней: человек дописывает («и ещё килограмм фарша»),
// исправляет прежнее сообщение, передумывает. Разбор при получении видит
// только одно сообщение, поэтому бот возвращается к заявке и сверяет весь
// накопившийся разговор с текущим составом заказа.
//
// Частота затухает: пока заявка свежая, разговор идёт и сверять нужно часто;
// потом всё реже; и обязательный проход в день сборки, когда состав ещё можно
// поправить, а после уже поздно.
//
// Забытое бот дописывает в заказ сам: покупатель просил сочни, а в заказе
// их нет — позиция появляется, и в комментарии отмечено, что её внёс бот.
// Правила те же, что при разборе сообщения (aiSplitItems): товар однозначно
// нашёлся в каталоге и количество названо. Товар, который в заказе уже есть,
// бот не трогает — это уже правка количества, а количество, как и отказ от
// товара, только описывается в комментарии. Решает продавец.
//
// И главное правило: заявку, которую тронул человек, бот больше не сверяет
// (см. bot_recheck.stopped). Продавец, открывший заказ и поправивший состав,
// знает больше модели — спорить с ним и дописывать ему замечания нельзя.

const (
	// recheckEvery — как часто просыпаемся посмотреть, кого пора сверить.
	// Сама сверка идёт по своему расписанию, это лишь шаг часов.
	recheckEvery = 20 * time.Minute

	// Затухающее расписание: пока заявке меньше recheckFreshFor — сверяем
	// каждый recheckFreshStep, до recheckDayFor — каждый recheckDayStep,
	// дальше раз в сутки.
	recheckFreshFor  = 3 * time.Hour
	recheckFreshStep = time.Hour
	recheckDayFor    = 24 * time.Hour
	recheckDayStep   = 6 * time.Hour
	recheckSlowStep  = 24 * time.Hour

	// recheckHour — час утреннего прохода в день сборки, по Москве.
	recheckHour = 8

	// recheckMaxAge — заявки старше двух недель не сверяем: если она
	// столько висит открытой, разбираться там нужно не боту.
	recheckMaxAge = 14 * 24 * time.Hour
)

// recheckDays — дни сборки: в эти дни проходим по всем открытым заявкам
// утром, даже если сверяли недавно. Перед сборкой состав ещё можно
// поправить, а после — уже поздно.
var recheckDays = map[time.Weekday]bool{
	time.Thursday: true,
	time.Friday:   true,
}

// recheckPrompt — задание модели. Просим не пересказывать заказ, а найти
// расхождение: пересказ продавцу и так виден в комментарии. Забытые товары
// модель отдаёт отдельным списком add с названием из каталога — их бот
// допишет в заказ сам. Каталог дописывается ниже (aiCatalog).
const recheckPrompt = `Ты сверяешь заказ магазина с перепиской покупателя.
Тебе дана переписка (сообщения по порядку, сверху старые) и текущий состав заказа.
Реши, соответствует ли состав заказа тому, что покупатель просил в итоге.
Учитывай исправления: если покупатель менял количество или отказывался от товара, верным считается последнее его слово.
Ответь ТОЛЬКО JSON без пояснений:
{"ok": true|false, "note": "чем состав расходится с перепиской, одной строкой", "add": [{"name":"как назвал покупатель","product":"точное название из каталога или пусто","qty":число,"unit":"кг|шт|уп"}]}
ok=true — состав совпадает с просьбой покупателя, note пустой, add пустой.
ok=false — что-то забыли, лишнее или не то количество; в note коротко скажи что именно.
В add перечисли только товары, которых в заказе нет совсем, а покупатель их просил. Товар, который в заказе уже есть, в add не пиши, даже если количество другое, — это только в note.
Для add найди товар в каталоге и скопируй в product ЕГО название буква в букву; подходящего нет или их несколько — оставь product пустым.
Количество ставь только то, которое покупатель назвал. Не назвал — пиши 0.
Не придирайся к словам: «пара» — это 2, «десяток» — 10. Товар, которого нет в заказе, но покупатель о нём только спрашивал, расхождением не считается.`

// recheckVerdict — что модель нашла при сверке.
type recheckVerdict struct {
	OK   bool     `json:"ok"`
	Note string   `json:"note"`
	Add  []aiItem `json:"add"`
}

// startRecheck поднимает фоновую сверку. Зовётся один раз при старте.
func startRecheck() {
	go func() {
		for {
			time.Sleep(recheckEvery)
			runRecheck(time.Now().In(moscow))
		}
	}()
}

// runRecheck — один проход по открытым заявкам бота.
func runRecheck(now time.Time) {
	if !aiReady() {
		return // разбор выключен или недоступен — сверять нечем
	}
	rows, err := db.Query(`SELECT order_id, at, seen_at FROM bot_recheck
      WHERE stopped = 0`)
	if err != nil {
		logErr("список заявок для сверки", err)
		return
	}
	type due struct{ orderID, at, seenAt int64 }
	var list []due
	for rows.Next() {
		var d due
		if err := rows.Scan(&d.orderID, &d.at, &d.seenAt); err == nil {
			list = append(list, d)
		}
	}
	rows.Close()
	logErr("список заявок для сверки", rows.Err())

	for _, d := range list {
		o, err := getOrder(d.orderID)
		if err != nil || !mergeable(o) {
			// Заявку закрыли, отменили или удалили — сверять больше нечего.
			stopRecheck(d.orderID)
			continue
		}
		// Заказ тронул человек: с этого момента он ведёт его сам.
		if o.UpdatedAt != d.seenAt {
			stopRecheck(d.orderID)
			log.Printf("сверка заявки %s прекращена: заказ правили вручную", o.Number())
			continue
		}
		if !recheckDue(o.CreatedAt, d.at, now) {
			continue
		}
		recheckOrder(o, now)
	}
}

// recheckDue — пора ли сверять эту заявку.
func recheckDue(createdAt, lastAt int64, now time.Time) bool {
	age := now.Sub(time.UnixMilli(createdAt))
	if age > recheckMaxAge {
		return false
	}
	since := now.Sub(time.UnixMilli(lastAt))

	// День сборки: один проход утром поверх обычного расписания. Сверяли
	// сегодня после этого часа — второй раз не идём.
	if recheckDays[now.Weekday()] && now.Hour() >= recheckHour {
		morning := time.Date(now.Year(), now.Month(), now.Day(), recheckHour, 0, 0, 0,
			now.Location())
		if lastAt == 0 || time.UnixMilli(lastAt).In(now.Location()).Before(morning) {
			return true
		}
	}

	switch {
	case age < recheckFreshFor:
		return since >= recheckFreshStep
	case age < recheckDayFor:
		return since >= recheckDayStep
	default:
		return since >= recheckSlowStep
	}
}

// recheckOrder сверяет одну заявку: забытые товары дописывает в заказ, а
// что разошлось — в комментарий. Совпало — молчим: продавцу не нужен поток
// «всё в порядке».
func recheckOrder(o Order, now time.Time) {
	v, ok := askRecheck(recheckText(o))
	if !ok {
		return // модель не ответила — попробуем в следующий раз
	}
	added := recheckAdd(o, v.Add)
	if len(added) == 0 && (v.OK || strings.TrimSpace(v.Note) == "") {
		markRechecked(o.ID, o.UpdatedAt, now)
		return
	}

	note := "Сверка с перепиской " + now.Format("02.01 15:04") + ":"
	if n := oneLine(v.Note); n != "" {
		note += " " + n
	}
	if len(added) > 0 {
		parts := make([]string, 0, len(added))
		for _, it := range added {
			parts = append(parts, it.Name+" — "+formatQty(it.Qty)+" "+aiUnit(it.Unit))
		}
		note += "\nБот дописал в заказ (проверьте): " + strings.Join(parts, "; ")
	}
	if err := appendOrderComment(o, note); err != nil {
		logErr("сверка заявки", err)
		return
	}
	// Комментарий дописали мы — запоминаем новый updated_at, иначе следующий
	// проход примет собственную правку за работу продавца.
	fresh, err := getOrder(o.ID)
	if err != nil {
		logErr("сверка заявки", err)
		return
	}
	markRechecked(o.ID, fresh.UpdatedAt, now)
	log.Printf("сверка заявки %s: %s", o.Number(), oneLine(note))
}

// recheckAdd дописывает в заказ забытые товары и возвращает, что дописал.
//
// Берём только то, что взял бы и разбор сообщения (aiSplitItems): товар
// однозначно найден в каталоге и количество названо. Товар, который в заказе
// уже есть, пропускаем, даже если модель его назвала: сложить количество
// значит решить за продавца, а разошедшееся количество описано в note.
// Этой же проверкой следующая сверка не допишет товар второй раз.
func recheckAdd(o Order, items []aiItem) []OrderItem {
	ready, _ := aiSplitItems(aiVerdict{Items: items})
	have := map[int64]bool{}
	for _, it := range o.Items {
		if it.ProductID != nil {
			have[*it.ProductID] = true
		}
	}
	var added []OrderItem
	for _, it := range ready {
		id := *it.ProductID
		if have[id] {
			continue
		}
		if err := addOrderItem(o.ID, id, it.Qty); err != nil {
			logErr("сверка заявки: дописать "+it.Name, err)
			continue
		}
		have[id] = true
		added = append(added, it)
	}
	return added
}

// recheckText — что показываем модели: переписка и текущий состав заказа.
func recheckText(o Order) string {
	var b strings.Builder
	b.WriteString("Переписка:\n")
	b.WriteString(strings.TrimSpace(o.Comment))
	b.WriteString("\n\nСостав заказа сейчас:\n")
	if len(o.Items) == 0 {
		b.WriteString("(пусто — в заказе нет ни одной позиции)\n")
	}
	for _, it := range o.Items {
		b.WriteString(it.Name + " — " + formatQty(it.Qty) + " " + aiUnit(it.Unit) + "\n")
	}
	return b.String()
}

// askRecheck спрашивает модель. Как и разбор сообщений: второе значение —
// ответила ли она, а не «всё в порядке».
func askRecheck(text string) (recheckVerdict, bool) {
	if !aiReady() || aiPaused() || strings.TrimSpace(text) == "" {
		return recheckVerdict{}, false
	}
	if r := []rune(text); len(r) > aiMaxText*2 {
		// Переписка длиннее одного сообщения, поэтому запас вдвое больше.
		text = string(r[:aiMaxText*2])
	}

	ctx, cancel := context.WithTimeout(context.Background(), aiTimeout)
	defer cancel()

	prompt := recheckPrompt + aiCatalog()
	raw, err := aiChat(ctx, prompt, text, true)
	if err != nil && strings.Contains(strings.ToLower(err.Error()), "think") {
		raw, err = aiChat(ctx, prompt, text, false)
	}
	if err != nil {
		aiFailed(err)
		return recheckVerdict{}, false
	}
	aiWorked()

	var v recheckVerdict
	if err := json.Unmarshal([]byte(jsonObject(raw)), &v); err != nil {
		aiFailed(fmt.Errorf("ответ сверки не разобран: %w", err))
		return recheckVerdict{}, false
	}
	v.Note = oneLine(v.Note)
	v.Add = aiClean(aiVerdict{Items: v.Add}, text).Items
	return v, true
}

// ------------------------------------------------------------ учёт правок

// watchOrder берёт заявку под сверку. Зовётся, когда бот её завёл.
func watchOrder(orderID, updatedAt int64) {
	if orderID == 0 {
		return
	}
	_, err := db.Exec(`INSERT INTO bot_recheck (order_id, at, seen_at, stopped)
      VALUES (?,0,?,0) ON CONFLICT(order_id) DO UPDATE SET seen_at = excluded.seen_at`,
		orderID, updatedAt)
	logErr("заявка под сверку", err)
}

// botTouched — бот сам изменил заказ. Без этого следующий проход принял бы
// собственную правку за работу продавца и сверку бы прекратил.
func botTouched(orderID, updatedAt int64) {
	if orderID == 0 {
		return
	}
	_, err := db.Exec(`UPDATE bot_recheck SET seen_at = ? WHERE order_id = ?`,
		updatedAt, orderID)
	logErr("отметка правки бота", err)
}

// stopRecheck — больше эту заявку не сверяем.
func stopRecheck(orderID int64) {
	_, err := db.Exec(`UPDATE bot_recheck SET stopped = 1 WHERE order_id = ?`, orderID)
	logErr("остановка сверки", err)
}

// markRechecked — сверили; запоминаем когда и каким заказ остался.
func markRechecked(orderID, updatedAt int64, now time.Time) {
	_, err := db.Exec(`UPDATE bot_recheck SET at = ?, seen_at = ? WHERE order_id = ?`,
		now.UnixMilli(), updatedAt, orderID)
	logErr("отметка сверки", err)
}
