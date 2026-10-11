package com.crmsistems.app.data.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.crmsistems.app.data.entity.Sale;
import com.crmsistems.app.data.model.NameValue;

import java.util.List;

@Dao
public interface SaleDao {

    String PAID = "status = 0";
    String TOTAL = "COALESCE(SUM(qty * unitPrice - discount), 0)";
    String PROFIT = "COALESCE(SUM(qty * (unitPrice - unitCost) - discount), 0)";
    String MONTH = "strftime('%Y-%m', date / 1000, 'unixepoch', 'localtime')";

    @Query("SELECT * FROM sales ORDER BY date DESC")
    LiveData<List<Sale>> observeAll();

    @Query("SELECT * FROM sales ORDER BY date DESC LIMIT :limit")
    LiveData<List<Sale>> observeRecent(int limit);

    @Query("SELECT * FROM sales ORDER BY date DESC")
    List<Sale> getAll();

    @Query("SELECT * FROM sales WHERE id = :id")
    Sale getById(long id);

    @Query("SELECT * FROM sales WHERE clientId = :clientId ORDER BY date DESC")
    LiveData<List<Sale>> observeByClient(long clientId);

    @Query("SELECT * FROM sales WHERE productId = :productId ORDER BY date DESC")
    LiveData<List<Sale>> observeByProduct(long productId);

    /**
     * Сколько товара «висит» в продажах, которые ещё не ушли на сайт.
     * В этой таблице лежат только неотправленные продажи — после успешной
     * отправки запись удаляется. На это количество локальный остаток уже
     * уменьшен, а сайт про него ещё не знает и спишет сам, когда получит
     * продажу: при выгрузке карточки товара количество надо вернуть обратно,
     * иначе остаток спишется дважды. Отменённые продажи склад не трогают.
     */
    @Query("SELECT COALESCE(SUM(qty), 0) FROM sales WHERE productId = :productId AND status != 2")
    double unsentQty(long productId);

    // ---------- Аналитика ----------

    @Query("SELECT " + TOTAL + " FROM sales WHERE " + PAID + " AND date BETWEEN :from AND :to")
    double revenue(long from, long to);

    @Query("SELECT " + PROFIT + " FROM sales WHERE " + PAID + " AND date BETWEEN :from AND :to")
    double profit(long from, long to);

    @Query("SELECT " + TOTAL + " FROM sales WHERE status = 1 AND date BETWEEN :from AND :to")
    double pending(long from, long to);

    @Query("SELECT COUNT(*) FROM sales WHERE " + PAID + " AND date BETWEEN :from AND :to")
    int count(long from, long to);

    @Query("SELECT COUNT(DISTINCT clientId) FROM sales WHERE " + PAID + " AND date BETWEEN :from AND :to")
    int activeClients(long from, long to);

    /** Выручка по периодам. Формат {@code fmt}: «%Y-%m» — по месяцам, «%Y-%m-%d» — по дням. */
    @Query("SELECT strftime(:fmt, date / 1000, 'unixepoch', 'localtime') AS name, "
            + TOTAL + " AS value FROM sales "
            + "WHERE " + PAID + " AND date BETWEEN :from AND :to GROUP BY name ORDER BY name")
    List<NameValue> revenueByPeriod(String fmt, long from, long to);

    @Query("SELECT strftime(:fmt, date / 1000, 'unixepoch', 'localtime') AS name, "
            + PROFIT + " AS value FROM sales "
            + "WHERE " + PAID + " AND date BETWEEN :from AND :to GROUP BY name ORDER BY name")
    List<NameValue> profitByPeriod(String fmt, long from, long to);

    @Query("SELECT strftime(:fmt, date / 1000, 'unixepoch', 'localtime') AS name, "
            + "COUNT(*) AS value FROM sales "
            + "WHERE " + PAID + " AND date BETWEEN :from AND :to GROUP BY name ORDER BY name")
    List<NameValue> countByPeriod(String fmt, long from, long to);

    @Query("SELECT productName AS name, " + TOTAL + " AS value FROM sales "
            + "WHERE " + PAID + " AND date BETWEEN :from AND :to "
            + "GROUP BY productName ORDER BY value DESC LIMIT :limit")
    List<NameValue> topProducts(long from, long to, int limit);

    @Query("SELECT productName AS name, COALESCE(SUM(qty), 0) AS value FROM sales "
            + "WHERE " + PAID + " AND date BETWEEN :from AND :to "
            + "GROUP BY productName ORDER BY value DESC LIMIT :limit")
    List<NameValue> topProductsByQty(long from, long to, int limit);

    @Query("SELECT clientName AS name, " + TOTAL + " AS value FROM sales "
            + "WHERE " + PAID + " AND date BETWEEN :from AND :to "
            + "GROUP BY clientName ORDER BY value DESC LIMIT :limit")
    List<NameValue> topClients(long from, long to, int limit);

    @Query("SELECT " + TOTAL + " FROM sales WHERE " + PAID + " AND clientId = :clientId")
    double clientTotal(long clientId);

    @Query("SELECT COUNT(*) FROM sales WHERE clientId = :clientId")
    int clientSalesCount(long clientId);

    @Query("SELECT MAX(date) FROM sales WHERE clientId = :clientId")
    Long clientLastSale(long clientId);

    // ---------- CRUD ----------

    @Insert
    long insert(Sale sale);

    @Insert
    List<Long> insertAll(List<Sale> sales);

    @Update
    void update(Sale sale);

    @Delete
    void delete(Sale sale);

    @Query("DELETE FROM sales")
    void clear();
}
