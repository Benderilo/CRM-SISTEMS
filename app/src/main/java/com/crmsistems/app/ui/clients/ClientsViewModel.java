package com.crmsistems.app.ui.clients;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;

import com.crmsistems.app.data.Repository;
import com.crmsistems.app.data.entity.Client;
import com.crmsistems.app.data.entity.OrderEntity;
import com.crmsistems.app.data.entity.Sale;
import com.crmsistems.app.ui.orders.ServerOrder;
import com.crmsistems.app.util.Phones;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Список клиентов + сумма покупок по каждому. Суммы считаются из таблицы продаж
 * и из заказов с сайта (их сопоставляем по телефону — на сайте это логин
 * покупателя), поэтому цифры совпадают с карточкой клиента и с панелью сайта.
 */
public class ClientsViewModel extends AndroidViewModel {

    public static final int SORT_NAME = 0;
    public static final int SORT_NEW = 1;
    public static final int ONLY_WITH_SALES = 2;

    private final MediatorLiveData<List<ClientRow>> visible = new MediatorLiveData<>();

    private List<Client> clients = new ArrayList<>();
    private List<Sale> sales = new ArrayList<>();
    private List<OrderEntity> orders = new ArrayList<>();
    private String query = "";
    private int mode = SORT_NAME;
    /** Выбранный сегмент чипом или {@code null} — показывать все. Фильтр и
     *  сортировка независимы: сегмент отбирает, чипы ниже задают порядок. */
    private String segment;
    private double totalRevenue;

    public ClientsViewModel(@NonNull Application application) {
        super(application);
        Repository repo = Repository.get(application);
        visible.addSource(repo.clients(), value -> {
            clients = value == null ? new ArrayList<>() : value;
            apply();
        });
        visible.addSource(repo.sales(), value -> {
            sales = value == null ? new ArrayList<>() : value;
            apply();
        });
        visible.addSource(repo.orders(), value -> {
            orders = value == null ? new ArrayList<>() : value;
            apply();
        });
    }

    public LiveData<List<ClientRow>> visible() {
        return visible;
    }

    public int totalClients() {
        return clients.size();
    }

    public double totalRevenue() {
        return totalRevenue;
    }

    public void setQuery(String text) {
        query = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        apply();
    }

    public void setMode(int mode) {
        this.mode = mode;
        apply();
    }

    public void setSegment(String segment) {
        this.segment = segment;
        apply();
    }

    private void apply() {
        Map<Long, double[]> stats = new HashMap<>();
        totalRevenue = 0;
        for (Sale s : sales) {
            if (s.clientId == null || s.status != Sale.STATUS_PAID) continue;
            double[] acc = stats.get(s.clientId);
            if (acc == null) {
                acc = new double[]{0, 0, 0};
                stats.put(s.clientId, acc);
            }
            acc[0] += s.total();
            acc[1] += 1;
            acc[2] = Math.max(acc[2], s.date);
            totalRevenue += s.total();
        }

        // Заказы с сайта — по телефону покупателя; отменённые не считаем, как на сайте.
        Map<String, double[]> byPhone = new HashMap<>();
        for (OrderEntity o : orders) {
            if (o.status == ServerOrder.STATUS_CANCELED) continue;
            String key = Phones.key(o.phone);
            if (key.isEmpty()) continue;
            double[] acc = byPhone.get(key);
            if (acc == null) {
                acc = new double[]{0, 0, 0};
                byPhone.put(key, acc);
            }
            acc[0] += o.total;
            acc[1] += 1;
            acc[2] = Math.max(acc[2], o.createdAt);
            totalRevenue += o.total;
        }

        List<ClientRow> rows = new ArrayList<>();
        for (Client c : clients) {
            if (c.archived) continue;
            if (!query.isEmpty() && !matches(c)) continue;
            double[] acc = stats.get(c.id);
            double[] site = byPhone.get(Phones.key(c.phone));
            double total = acc == null ? 0 : acc[0];
            int count = acc == null ? 0 : (int) acc[1];
            long last = acc == null ? 0 : (long) acc[2];
            if (site != null) {
                total += site[0];
                count += (int) site[1];
                last = Math.max(last, (long) site[2]);
            }
            if (segment != null && !segment.equalsIgnoreCase(c.segment)) continue;
            if (mode == ONLY_WITH_SALES && count == 0) continue;
            rows.add(new ClientRow(c, total, count, last));
        }

        if (mode == SORT_NEW) {
            Collections.sort(rows, (a, b) -> Long.compare(b.client.createdAt, a.client.createdAt));
        } else if (mode == ONLY_WITH_SALES) {
            Collections.sort(rows, (a, b) -> Double.compare(b.total, a.total));
        } else {
            Collections.sort(rows, (a, b) -> a.client.name.compareToIgnoreCase(b.client.name));
        }
        visible.setValue(rows);
    }

    private boolean matches(Client c) {
        return contains(c.name) || contains(c.phone) || contains(c.email)
                || contains(c.company) || contains(c.tags);
    }

    private boolean contains(String value) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }
}
