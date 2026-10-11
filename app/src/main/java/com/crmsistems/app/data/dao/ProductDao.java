package com.crmsistems.app.data.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.crmsistems.app.data.entity.Product;

import java.util.List;

@Dao
public interface ProductDao {

    @Query("SELECT * FROM products ORDER BY name COLLATE NOCASE ASC")
    LiveData<List<Product>> observeAll();

    @Query("SELECT * FROM products ORDER BY name COLLATE NOCASE ASC")
    List<Product> getAll();

    @Query("SELECT * FROM products WHERE archived = 0 ORDER BY name COLLATE NOCASE ASC")
    List<Product> getActive();

    @Query("SELECT * FROM products WHERE id = :id")
    LiveData<Product> observeById(long id);

    @Query("SELECT * FROM products WHERE id = :id")
    Product getById(long id);

    @Query("SELECT * FROM products WHERE sku = :sku AND sku <> '' LIMIT 1")
    Product findBySku(String sku);

    @Query("SELECT COUNT(*) FROM products WHERE archived = 0")
    int countActive();

    @Query("SELECT COALESCE(SUM(stock * cost), 0) FROM products WHERE archived = 0")
    double stockValue();

    @Query("SELECT category AS name, COUNT(*) AS value FROM products WHERE archived = 0 "
            + "GROUP BY category ORDER BY value DESC")
    List<com.crmsistems.app.data.model.NameValue> countByCategory();

    @Query("UPDATE products SET stock = stock - :qty, updatedAt = :now WHERE id = :id")
    void changeStock(long id, double qty, long now);

    @Insert
    long insert(Product product);

    @Insert
    List<Long> insertAll(List<Product> products);

    @Update
    void update(Product product);

    @Delete
    void delete(Product product);

    @Query("DELETE FROM products")
    void clear();
}
