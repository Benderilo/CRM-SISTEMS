package main

import (
	"database/sql"
	"errors"
	"sort"
	"strings"
)

// Объединение заказов одного покупателя.
//
// Человек редко заказывает в один заход: сначала оформил пельмени, через пять
// минут вспомнил про колбасу — и в CRM висят две карточки на один звонок.
// Поэтому новый заказ с того же телефона не заводится заново, а дописывается
// в уже открытый: продавец видит один заказ, один состав и одну сумму.
//
// Объединяются только заказы «в работе» (Новый, Принят, Собирается):
// выполненный или отменённый заказ — уже история, дописывать в него нельзя,
// иначе поехали бы выручка и склад. Продажи из приложения (source=app)
// не трогаем вовсе — это архив сделок, а не заявка покупателя.

// errMergeNotAllowed — заказы не подходят под объединение (разные телефоны,
// закрытые статусы или их просто меньше двух).
var errMergeNotAllowed = errors.New("заказы нельзя объединить")

// mergeableStatus — заказ ещё в работе, к нему можно дописать позиции.
func mergeableStatus(s int) bool {
	return s == StatusNew || s == StatusAccepted || s == StatusPacking
}

// mergeable — заказ покупателя в работе (продажи из приложения не в счёт).
func mergeable(o Order) bool {
	return mergeableStatus(o.Status) && o.Source != sourceApp
}

// findOpenOrderByPhone — открытый заказ того же покупателя, к которому подошьётся
// новый. Телефон сверяем нормализованным: «8 999…» и «+7 999…» — один и тот же
// человек. Берём самый свежий из открытых.
func findOpenOrderByPhone(phone string) (Order, bool) {
	key := normalizePhone(phone)
	if key == "" {
		return Order{}, false
	}
	orders, err := queryOrders(`WHERE status IN (?,?,?) AND source <> ?
      ORDER BY created_at DESC`, StatusNew, StatusAccepted, StatusPacking, sourceApp)
	if err != nil {
		return Order{}, false
	}
	for _, o := range orders {
		if normalizePhone(o.Phone) == key {
			return o, true
		}
	}
	return Order{}, false
}

// appendToOrder дописывает позиции нового заказа в уже существующий.
// Заказ в *o заменяется целевым — вызывающий покажет покупателю его номер и
// новую сумму, а по флагу Merged поймёт, что заказ не создан, а дополнен.
func appendToOrder(target Order, o *Order, items []OrderItem) error {
	tx, err := db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()

	// Склад двигаем, только если товар этого заказа сейчас списан: иначе
	// дозаказ ушёл бы в минус дважды.
	if err := addItemsTx(tx, target.ID, target.StockWritten, items); err != nil {
		return err
	}
	if err := refreshContactsTx(tx, target, *o); err != nil {
		return err
	}
	if err := reopenTx(tx, target); err != nil {
		return err
	}
	if err := retotalTx(tx, target.ID); err != nil {
		return err
	}
	if err := tx.Commit(); err != nil {
		return err
	}

	full, err := getOrder(target.ID)
	if err != nil {
		return err
	}
	full.Merged = true
	*o = full
	return nil
}

// addItemsTx кладёт позиции в заказ: одинаковый товар по той же цене
// складывается в одну строку, остальное дописывается новой.
//
// Цену в ключ берём не зря: если карточка товара подорожала между заказами,
// старая строка должна остаться по старой цене — покупателю обещали её.
func addItemsTx(tx *sql.Tx, orderID int64, writeOff bool, items []OrderItem) error {
	for _, it := range items {
		var itemID int64
		var oldQty float64
		var err error
		if it.ProductID != nil {
			err = tx.QueryRow(`SELECT id, qty FROM order_items
              WHERE order_id = ? AND product_id = ? AND unit_price = ?`,
				orderID, *it.ProductID, it.UnitPrice).Scan(&itemID, &oldQty)
		} else {
			err = tx.QueryRow(`SELECT id, qty FROM order_items
              WHERE order_id = ? AND product_id IS NULL AND name = ? AND unit_price = ?`,
				orderID, it.Name, it.UnitPrice).Scan(&itemID, &oldQty)
		}
		switch {
		case err == nil:
			if _, err := tx.Exec(`UPDATE order_items SET qty = ? WHERE id = ?`,
				oldQty+it.Qty, itemID); err != nil {
				return err
			}
		case errors.Is(err, sql.ErrNoRows):
			if _, err := tx.Exec(`INSERT INTO order_items
              (order_id,product_id,name,unit,qty,unit_price,unit_cost)
              VALUES (?,?,?,?,?,?,?)`, orderID, it.ProductID, it.Name, it.Unit,
				it.Qty, it.UnitPrice, it.UnitCost); err != nil {
				return err
			}
		default:
			return err
		}

		// Как и при заказе с витрины: в минус уводить можно — минус показывает,
		// сколько доготовить.
		if !writeOff || it.ProductID == nil {
			continue
		}
		if _, err := tx.Exec(`UPDATE products SET stock = stock - ?, updated_at = ?
          WHERE id = ?`, it.Qty, nowMs(), *it.ProductID); err != nil {
			return err
		}
	}
	return nil
}

// refreshContactsTx переносит в заказ свежие данные покупателя: адрес и способ
// получения берём последние (человек мог передумать), комментарии копим все —
// в них просьбы вроде «позвонить за час».
func refreshContactsTx(tx *sql.Tx, target Order, fresh Order) error {
	clientID := target.ClientID
	if clientID == nil && fresh.ClientID != nil {
		clientID = fresh.ClientID
	}
	_, err := tx.Exec(`UPDATE orders SET client_id=?, client_name=?, email=?, address=?,
      delivery=?, pay_method=?, comment=?, updated_at=? WHERE id=?`,
		clientID,
		pick(target.ClientName, fresh.ClientName),
		pick(fresh.Email, target.Email),
		pick(fresh.Address, target.Address),
		pick(fresh.Delivery, target.Delivery),
		pick(fresh.PayMethod, target.PayMethod),
		joinComments(target.Comment, fresh.Comment),
		nowMs(), target.ID)
	return err
}

// pick — первое непустое значение.
func pick(first, second string) string {
	if strings.TrimSpace(first) != "" {
		return first
	}
	return second
}

// joinComments склеивает комментарии заказов, не повторяя уже сказанное.
func joinComments(parts ...string) string {
	out := []string{}
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p == "" {
			continue
		}
		seen := false
		for _, has := range out {
			if has == p {
				seen = true
				break
			}
		}
		if !seen {
			out = append(out, p)
		}
	}
	return strings.Join(out, "\n")
}

// reopenTx возвращает заказ в «Новый», если он был принят или уже собирался.
//
// Покупатель дозаказал — состав и сумма стали другими, а продавец подтверждал
// прежние. Пусть заказ снова попадётся ему на глаза: напоминание в шторке
// живёт как раз по «Новым». Склад при этом не двигается — его трогает только
// отмена.
func reopenTx(tx *sql.Tx, target Order) error {
	if target.Status == StatusNew {
		return nil
	}
	_, err := tx.Exec(`UPDATE orders SET status = ?, updated_at = ? WHERE id = ?`,
		StatusNew, nowMs(), target.ID)
	return err
}

// retotalTx пересчитывает сумму заказа по его позициям.
func retotalTx(tx *sql.Tx, orderID int64) error {
	_, err := tx.Exec(`UPDATE orders SET total = (
          SELECT COALESCE(SUM(qty * unit_price), 0) FROM order_items WHERE order_id = ?
        ), updated_at = ? WHERE id = ?`, orderID, nowMs(), orderID)
	return err
}

// mergeOrders сводит несколько уже созданных заказов в один — этим чистятся
// старые дубли, которые появились до того, как объединение заработало само.
//
// Цель — самый ранний заказ: его номер покупатель уже знает и мог записать.
// Остальные после переноса позиций удаляются, склад при этом не двигается:
// товар не исчез, он просто перешёл в другой заказ.
func mergeOrders(ids []int64) (Order, error) {
	if len(ids) < 2 {
		return Order{}, errMergeNotAllowed
	}

	orders := make([]Order, 0, len(ids))
	seen := map[int64]bool{}
	key := ""
	for _, id := range ids {
		if seen[id] {
			continue
		}
		seen[id] = true
		o, err := getOrder(id)
		if err != nil {
			return Order{}, err
		}
		if !mergeable(o) {
			return Order{}, errMergeNotAllowed
		}
		phone := normalizePhone(o.Phone)
		if phone == "" || (key != "" && key != phone) {
			return Order{}, errMergeNotAllowed
		}
		key = phone
		orders = append(orders, o)
	}
	if len(orders) < 2 {
		return Order{}, errMergeNotAllowed
	}

	// Самый ранний заказ — цель, остальные подшиваются к нему по времени.
	sort.Slice(orders, func(i, j int) bool {
		if orders[i].CreatedAt != orders[j].CreatedAt {
			return orders[i].CreatedAt < orders[j].CreatedAt
		}
		return orders[i].ID < orders[j].ID
	})
	target, sources := orders[0], orders[1:]

	tx, err := db.Begin()
	if err != nil {
		return Order{}, err
	}
	defer tx.Rollback()

	for _, src := range sources {
		// Товар просто переезжает в другой заказ, поэтому склад трогаем только
		// когда отметки о списании у заказов разные — иначе он «спишется»
		// или «вернётся» второй раз.
		switch {
		case src.StockWritten && !target.StockWritten:
			if err := adjustStock(tx, src.ID, +1); err != nil {
				return Order{}, err
			}
		case !src.StockWritten && target.StockWritten:
			if err := adjustStock(tx, src.ID, -1); err != nil {
				return Order{}, err
			}
		}
		if _, err := tx.Exec(`DELETE FROM order_items WHERE order_id = ?`, src.ID); err != nil {
			return Order{}, err
		}
		if err := addItemsTx(tx, target.ID, false, src.Items); err != nil {
			return Order{}, err
		}
		if err := refreshContactsTx(tx, target, src); err != nil {
			return Order{}, err
		}
		if _, err := tx.Exec(`DELETE FROM orders WHERE id = ?`, src.ID); err != nil {
			return Order{}, err
		}

		// Копию цели держим в актуальном виде: следующий источник должен
		// дополнять уже дописанный комментарий, а не затирать его.
		target.Comment = joinComments(target.Comment, src.Comment)
		target.ClientName = pick(target.ClientName, src.ClientName)
		target.Email = pick(src.Email, target.Email)
		target.Address = pick(src.Address, target.Address)
		target.Delivery = pick(src.Delivery, target.Delivery)
		target.PayMethod = pick(src.PayMethod, target.PayMethod)
		if target.ClientID == nil {
			target.ClientID = src.ClientID
		}
	}
	// Собранный из нескольких заказ продавец ещё не подтверждал в таком виде.
	if err := reopenTx(tx, target); err != nil {
		return Order{}, err
	}
	if err := retotalTx(tx, target.ID); err != nil {
		return Order{}, err
	}
	if err := tx.Commit(); err != nil {
		return Order{}, err
	}

	full, err := getOrder(target.ID)
	if err != nil {
		return Order{}, err
	}
	full.Merged = true
	return full, nil
}
