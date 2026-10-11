package com.crmsistems.app.data.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Продажа — связь «товар ↔ клиент». Именно на этой таблице строится вся аналитика.
 * Цена и себестоимость сохраняются «снимком» на момент сделки, чтобы отчёты за прошлые
 * периоды не менялись при редактировании карточки товара.
 */
@Entity(tableName = "sales",
        foreignKeys = {
                @ForeignKey(entity = Client.class, parentColumns = "id", childColumns = "clientId",
                        onDelete = ForeignKey.SET_NULL),
                @ForeignKey(entity = Product.class, parentColumns = "id", childColumns = "productId",
                        onDelete = ForeignKey.SET_NULL)
        },
        indices = {@Index("clientId"), @Index("productId"), @Index("date")})
public class Sale {

    public static final int STATUS_PAID = 0;      // оплачено — попадает в доход
    public static final int STATUS_PENDING = 1;   // ожидает оплаты — дебиторка
    public static final int STATUS_CANCELED = 2;  // отменено — в аналитике не учитывается

    public static final String[] STATUS_NAMES = {"Оплачено", "Ожидает оплаты", "Отменено"};

    @PrimaryKey(autoGenerate = true)
    public long id;

    public Long clientId;
    public Long productId;

    /** Снимок названий — чтобы выгрузка в Excel читалась без соединений таблиц. */
    @NonNull
    public String clientName = "";

    @NonNull
    public String productName = "";

    public double qty = 1;

    /** Цена продажи за единицу на момент сделки. */
    public double unitPrice;

    /** Себестоимость за единицу на момент сделки. */
    public double unitCost;

    /** Скидка на всю позицию в деньгах. */
    public double discount;

    /** Дата сделки. */
    public long date;

    public int status = STATUS_PAID;

    @NonNull
    public String note = "";

    public long createdAt;

    /**
     * Метка продажи из нескольких позиций: все строки одной продажи носят один
     * groupId. 0 — обычная продажа в одну строку. На сайт такая продажа уходит
     * одним заказом со всем составом, а в карточке клиента показывается одной
     * покупкой, а не россыпью товаров.
     */
    @ColumnInfo(defaultValue = "0")
    public long groupId;

    public Sale() {
    }

    @Ignore
    public Sale(Long clientId, Long productId, double qty, double unitPrice, double unitCost, long date) {
        this.clientId = clientId;
        this.productId = productId;
        this.qty = qty;
        this.unitPrice = unitPrice;
        this.unitCost = unitCost;
        this.date = date;
        this.createdAt = System.currentTimeMillis();
    }

    /** Сумма сделки с учётом скидки. */
    public double total() {
        return qty * unitPrice - discount;
    }

    /** Прибыль по сделке. */
    public double profit() {
        return qty * (unitPrice - unitCost) - discount;
    }

    @NonNull
    public String statusName() {
        return status >= 0 && status < STATUS_NAMES.length ? STATUS_NAMES[status] : "—";
    }
}
