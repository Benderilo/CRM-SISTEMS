package com.crmsistems.app.data.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

/**
 * Карточка товара. Хранится локально в базе на телефоне.
 */
@Entity(tableName = "products")
public class Product {

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** Наименование товара. */
    @NonNull
    public String name = "";

    /** Артикул / SKU — используется как ключ при импорте из Excel/CSV. */
    @NonNull
    public String sku = "";

    /** Категория (группа) товара. */
    @NonNull
    public String category = "";

    /** Единица измерения: шт, кг, м, услуга... */
    @NonNull
    public String unit = "шт";

    /** Цена продажи. */
    public double price;

    /** Себестоимость (закупка). Нужна для расчёта прибыли. */
    public double cost;

    /** Остаток на складе. */
    public double stock;

    /** Минимальный остаток — ниже него товар подсвечивается как «заканчивается». */
    public double minStock;

    /** Штрихкод. */
    @NonNull
    public String barcode = "";

    /** Подробное описание. */
    @NonNull
    public String description = "";

    /** Uri картинки товара (SAF), может быть пустым. */
    @NonNull
    public String photoUri = "";

    /** Путь картинки на сайте вида /photo/xxx.jpg — заполняется при синхронизации. */
    @NonNull
    public String serverImage = "";

    /** Загружено ли локальное фото на сайт: чтобы не заливать повторно. */
    public boolean photoUploaded;

    /** Товар в архиве — не показывается в основном списке. */
    public boolean archived;

    /** Показывать ли товар на витрине сайта. */
    public boolean published = true;

    /** id этого же товара на сайте, 0 — на сайт ещё не выгружался. */
    public long serverId;

    public long createdAt;
    public long updatedAt;

    public Product() {
    }

    @Ignore
    public Product(@NonNull String name, double price, double cost, double stock) {
        this.name = name;
        this.price = price;
        this.cost = cost;
        this.stock = stock;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
    }

    /** Наценка в процентах. */
    public double marginPercent() {
        if (price <= 0) return 0;
        return (price - cost) / price * 100.0;
    }

    /** Стоимость складского остатка по себестоимости. */
    public double stockValue() {
        return stock * cost;
    }

    public boolean isLowStock() {
        return minStock > 0 && stock <= minStock;
    }

    @NonNull
    public String title() {
        return name.isEmpty() ? "Без названия" : name;
    }
}
