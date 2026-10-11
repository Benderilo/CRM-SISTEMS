package com.crmsistems.app.data.entity;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Исходящие изменения, сделанные в приложении без связи с сайтом.
 * Когда интернет появится, SyncManager отправит их в первую очередь
 * (до того, как заберёт свежие данные с сайта), и удалит отсюда.
 * <p>
 * kind: 0 — смена статуса заказа (refId = id заказа на сервере, payload = {"status":N);
 *      1 — правка/создание товара (refId = локальный id товара, payload не нужен);
 *      2 — правка/создание клиента (refId = локальный id клиента);
 *      3 — удаление клиента (refId = локальный id, payload = {"serverId":N} —
 *          самой карточки уже нет, поэтому id на сайте возим с собой).
 * <p>
 * Продажи в очереди не живут: они лежат в таблице sales и уходят на сайт
 * заказами при каждом обмене — см. SyncManager#pushSales.
 */
@Entity(tableName = "pending_changes")
public class PendingChange {

    @PrimaryKey(autoGenerate = true)
    public long id;

    public int kind;
    public long refId;
    public String payload = "";
    public long createdAt;
}