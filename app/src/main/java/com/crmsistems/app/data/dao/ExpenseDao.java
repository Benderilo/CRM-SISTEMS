package com.crmsistems.app.data.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.crmsistems.app.data.entity.Expense;
import com.crmsistems.app.data.model.NameValue;

import java.util.List;

@Dao
public interface ExpenseDao {

    String MONTH = "strftime('%Y-%m', date / 1000, 'unixepoch', 'localtime')";

    @Query("SELECT * FROM expenses ORDER BY date DESC")
    LiveData<List<Expense>> observeAll();

    @Query("SELECT * FROM expenses ORDER BY date DESC")
    List<Expense> getAll();

    @Query("SELECT * FROM expenses WHERE id = :id")
    Expense getById(long id);

    @Query("SELECT COALESCE(SUM(amount), 0) FROM expenses WHERE date BETWEEN :from AND :to")
    double total(long from, long to);

    /** Расходы по периодам. Формат {@code fmt}: «%Y-%m» — по месяцам, «%Y-%m-%d» — по дням. */
    @Query("SELECT strftime(:fmt, date / 1000, 'unixepoch', 'localtime') AS name, "
            + "COALESCE(SUM(amount), 0) AS value FROM expenses "
            + "WHERE date BETWEEN :from AND :to GROUP BY name ORDER BY name")
    List<NameValue> byPeriod(String fmt, long from, long to);

    @Query("SELECT category AS name, COALESCE(SUM(amount), 0) AS value FROM expenses "
            + "WHERE date BETWEEN :from AND :to GROUP BY category ORDER BY value DESC")
    List<NameValue> byCategory(long from, long to);

    @Insert
    long insert(Expense expense);

    @Insert
    List<Long> insertAll(List<Expense> expenses);

    @Update
    void update(Expense expense);

    @Delete
    void delete(Expense expense);

    @Query("DELETE FROM expenses")
    void clear();
}
