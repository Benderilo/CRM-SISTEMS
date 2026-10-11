package com.crmsistems.app.ui.sales;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;

import com.crmsistems.app.data.Repository;
import com.crmsistems.app.data.entity.OrderEntity;
import com.crmsistems.app.data.entity.Sale;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Список заказов с поиском и фильтром по статусу. Данные те же, что и раньше —
 * сделки из приложения плюс заказы с сайта, — но теперь строку видно только
 * если она проходит фильтр и отвечает поисковому запросу.
 */
public class SalesViewModel extends AndroidViewModel {

    /** Все статусы; любое другое значение — точный статус в шкале заказов
     *  сайта ({@link SaleRow#colorStatus()}), сделки приложения приведены к ней же. */
    public static final int FILTER_ALL = -1;

    private final MediatorLiveData<List<SaleRow>> visible = new MediatorLiveData<>();

    private List<Sale> sales = new ArrayList<>();
    private List<OrderEntity> orders = new ArrayList<>();
    private String query = "";
    private int filter = FILTER_ALL;

    public SalesViewModel(@NonNull Application application) {
        super(application);
        Repository repo = Repository.get(application);
        visible.addSource(repo.sales(), value -> {
            sales = value == null ? new ArrayList<>() : value;
            apply();
        });
        visible.addSource(repo.orders(), value -> {
            orders = value == null ? new ArrayList<>() : value;
            apply();
        });
    }

    public LiveData<List<SaleRow>> visible() {
        return visible;
    }

    public void setQuery(String text) {
        query = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        apply();
    }

    public void setFilter(int filter) {
        this.filter = filter;
        apply();
    }

    private void apply() {
        List<SaleRow> rows = SaleRow.merge(sales, orders);
        List<SaleRow> out = new ArrayList<>(rows.size());
        for (SaleRow row : rows) {
            if (filter != FILTER_ALL && row.colorStatus() != filter) continue;
            if (!query.isEmpty() && !matches(row)) continue;
            out.add(row);
        }
        visible.setValue(out);
    }

    /** Ищем по товару, клиенту, статусу и номеру заказа с сайта. */
    private boolean matches(SaleRow row) {
        if (contains(row.title(SaleRow.TITLE_PRODUCT))) return true;
        if (contains(row.title(SaleRow.TITLE_CLIENT))) return true;
        if (contains(row.statusName())) return true;
        if (row.order != null && contains(row.order.number)) return true;
        if (row.sale != null && contains(row.sale.note)) return true;
        return false;
    }

    private boolean contains(String value) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }
}
