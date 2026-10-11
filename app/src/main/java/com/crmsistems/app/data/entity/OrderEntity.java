package com.crmsistems.app.data.entity;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Локальный кэш заказа с сайта. Заказы по-прежнему живут на сервере (там их
 * оформляют покупатели и списываются остатки), но мы сохраняем последний список
 * в телефоне, чтобы его можно было открыть без интернета. При обмене список
 * обновляется свежим с сайта — сайт остаётся главным.
 * <p>
 * Пропавшие с сайта заказы из кэша не выбрасываются, а помечаются датой
 * удаления: в приложении их больше не видно, а в выгрузке в Excel они
 * остаются. Иначе стёртые выполненные заказы исчезали бы и из файла, а он
 * как раз и нужен, чтобы вся история была под рукой.
 */
@Entity(tableName = "orders")
public class OrderEntity {

    @PrimaryKey
    public long id;                 // id заказа на сервере — он же первичный ключ

    public String number = "";
    public String clientName = "";
    public String phone = "";
    public String email = "";
    public String address = "";
    public String comment = "";
    public String delivery = "";
    public String payMethod = "";
    public int status;
    public String statusName = "";
    public double total;
    public double profit;
    public long createdAt;
    public String itemsJson = "[]"; // состав заказа одним JSON-массивом
    public long cachedAt;           // когда сохранили кэш

    /**
     * Когда заказ пропал с сайта (удалили в приложении, в панели управления
     * или чисткой архива). 0 — заказ живой. В приложении такие строки не
     * показываются и в аналитику не идут, но в выгрузку попадают.
     */
    public long deletedAt;
}