package com.crmsistems.app.ui.products;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;

import com.crmsistems.app.data.Repository;
import com.crmsistems.app.data.entity.Product;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Держит список товаров, поисковый запрос и режим сортировки.
 * Фильтрация идёт в памяти — так список обновляется мгновенно при вводе.
 */
public class ProductsViewModel extends AndroidViewModel {

    public static final int SORT_NAME = 0;
    public static final int SORT_PRICE = 1;
    public static final int SORT_STOCK = 2;
    public static final int ONLY_LOW_STOCK = 3;

    private final Repository repo;
    private final MediatorLiveData<List<Product>> visible = new MediatorLiveData<>();

    private List<Product> all = new ArrayList<>();
    private String query = "";
    private int mode = SORT_NAME;

    public ProductsViewModel(@NonNull Application application) {
        super(application);
        repo = Repository.get(application);
        visible.addSource(repo.products(), products -> {
            all = products == null ? new ArrayList<>() : products;
            apply();
        });
    }

    public LiveData<List<Product>> visible() {
        return visible;
    }

    public List<Product> all() {
        return all;
    }

    public void setQuery(String text) {
        query = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        apply();
    }

    public void setMode(int mode) {
        this.mode = mode;
        apply();
    }

    public double totalStockValue() {
        double sum = 0;
        for (Product p : all) sum += p.stockValue();
        return sum;
    }

    public int lowStockCount() {
        int count = 0;
        for (Product p : all) if (p.isLowStock()) count++;
        return count;
    }

    private void apply() {
        List<Product> result = new ArrayList<>();
        for (Product p : all) {
            if (p.archived) continue;
            if (mode == ONLY_LOW_STOCK && !p.isLowStock()) continue;
            if (!query.isEmpty() && !matches(p)) continue;
            result.add(p);
        }
        switch (mode) {
            case SORT_PRICE:
                Collections.sort(result, (a, b) -> Double.compare(b.price, a.price));
                break;
            case SORT_STOCK:
                Collections.sort(result, (a, b) -> Double.compare(a.stock, b.stock));
                break;
            default:
                Collections.sort(result, (a, b) -> a.name.compareToIgnoreCase(b.name));
        }
        visible.setValue(result);
    }

    private boolean matches(Product p) {
        return contains(p.name) || contains(p.sku) || contains(p.category) || contains(p.barcode);
    }

    private boolean contains(String value) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }
}
