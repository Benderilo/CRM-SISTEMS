package com.crmsistems.app.data.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Расход (закупка, аренда, реклама, зарплата и т.д.).
 */
@Entity(tableName = "expenses", indices = {@Index("date")})
public class Expense {

    public static final String[] CATEGORIES = {
            "Закупка товара", "Аренда", "Реклама", "Зарплата", "Логистика", "Налоги", "Прочее"
    };

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    public String title = "";

    @NonNull
    public String category = "Прочее";

    public double amount;

    public long date;

    @NonNull
    public String note = "";

    public long createdAt;

    public Expense() {
    }

    @Ignore
    public Expense(@NonNull String title, @NonNull String category, double amount, long date) {
        this.title = title;
        this.category = category;
        this.amount = amount;
        this.date = date;
        this.createdAt = System.currentTimeMillis();
    }
}
