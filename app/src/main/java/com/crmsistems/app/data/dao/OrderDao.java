package com.crmsistems.app.data.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.crmsistems.app.data.entity.OrderEntity;
import com.crmsistems.app.data.model.NameValue;

import java.util.List;

/**
 * Доступ к локальному кэшу заказов. Свежий список всегда приходит с сервера,
 * а офлайн показываем последнюю удачную копию как есть.
 */
@Dao
public interface OrderDao {

    /** Отменённые заказы (статус 5) в аналитику не берём — как на сайте. */
    String NOT_CANCELED = "status != 5";

    /**
     * Живой заказ — тот, который ещё есть на сайте. Удалённые остаются в базе
     * ради выгрузки в Excel, но в приложении их не показываем и в аналитику
     * не берём: в списке они были бы мусором.
     */
    String ALIVE = "deletedAt = 0";

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<OrderEntity> orders);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(OrderEntity order);

    @Query("SELECT * FROM orders WHERE " + ALIVE + " ORDER BY createdAt DESC")
    List<OrderEntity> getAll();

    /** Всё, что когда-либо приходило с сайта, вместе с удалённым — для выгрузки. */
    @Query("SELECT * FROM orders ORDER BY createdAt DESC")
    List<OrderEntity> getAllWithHistory();

    @Query("SELECT * FROM orders WHERE " + ALIVE + " ORDER BY createdAt DESC")
    LiveData<List<OrderEntity>> observeAll();

    @Query("SELECT * FROM orders WHERE id = :id")
    OrderEntity getById(long id);

    /** Id всех заказов в кэше — по ним видно, какие пришли с сайта впервые. */
    @Query("SELECT id FROM orders")
    List<Long> allIds();

    /** Оптимистично применить смену статуса в кэше, пока правка не ушла на сайт. */
    @Query("UPDATE orders SET status = :status, statusName = :statusName WHERE id = :id")
    void updateStatus(long id, int status, String statusName);

    /**
     * Пометить заказ удалённым — после того, как его удалили на сайте.
     * Из базы не выбрасываем: в выгрузке он должен остаться.
     */
    @Query("UPDATE orders SET deletedAt = :at WHERE id = :id AND " + ALIVE)
    void markDeleted(long id, long at);

    /**
     * Пометить удалёнными все заказы, кроме пришедших с сайта: их там больше
     * нет — стёрли в панели управления или чисткой архива.
     */
    @Query("UPDATE orders SET deletedAt = :at WHERE " + ALIVE + " AND id NOT IN (:aliveIds)")
    void markDeletedExcept(List<Long> aliveIds, long at);

    /** То же, когда с сайта не пришло ни одного заказа. */
    @Query("UPDATE orders SET deletedAt = :at WHERE " + ALIVE)
    void markAllDeleted(long at);

    @Query("DELETE FROM orders")
    void clear();

    @Query("SELECT COUNT(*) FROM orders WHERE " + ALIVE)
    int count();

    // ---------- Аналитика ----------

    @Query("SELECT COALESCE(SUM(total), 0) FROM orders WHERE " + NOT_CANCELED + " AND " + ALIVE
            + " AND createdAt BETWEEN :from AND :to")
    double revenue(long from, long to);

    @Query("SELECT COALESCE(SUM(profit), 0) FROM orders WHERE " + NOT_CANCELED + " AND " + ALIVE
            + " AND createdAt BETWEEN :from AND :to")
    double profit(long from, long to);

    @Query("SELECT COUNT(*) FROM orders WHERE " + NOT_CANCELED + " AND " + ALIVE
            + " AND createdAt BETWEEN :from AND :to")
    int count(long from, long to);

    @Query("SELECT COUNT(*) FROM orders WHERE status = 0 AND " + ALIVE
            + " AND createdAt BETWEEN :from AND :to")
    int countNew(long from, long to);

    @Query("SELECT strftime(:fmt, createdAt / 1000, 'unixepoch', 'localtime') AS name, "
            + "COALESCE(SUM(total), 0) AS value FROM orders "
            + "WHERE " + NOT_CANCELED + " AND " + ALIVE + " AND createdAt BETWEEN :from AND :to "
            + "GROUP BY name ORDER BY name")
    List<NameValue> revenueByPeriod(String fmt, long from, long to);

    @Query("SELECT strftime(:fmt, createdAt / 1000, 'unixepoch', 'localtime') AS name, "
            + "COUNT(*) AS value FROM orders "
            + "WHERE " + NOT_CANCELED + " AND " + ALIVE + " AND createdAt BETWEEN :from AND :to "
            + "GROUP BY name ORDER BY name")
    List<NameValue> countByPeriod(String fmt, long from, long to);
}