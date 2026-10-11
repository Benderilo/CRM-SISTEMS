package com.crmsistems.app.ui.analytics;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;

import com.crmsistems.app.data.Repository;
import com.crmsistems.app.data.dao.OrderDao;
import com.crmsistems.app.data.entity.OrderEntity;
import com.crmsistems.app.data.model.NameValue;
import com.crmsistems.app.util.Dates;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Считает все показатели за выбранный период.
 * Пересчёт запускается автоматически при любом изменении продаж, расходов,
 * клиентов, товаров и кэша заказов с сайта.
 */
public class AnalyticsViewModel extends AndroidViewModel {

    public static final int PERIOD_WEEK = 0;
    public static final int PERIOD_MONTH = 1;
    public static final int PERIOD_QUARTER = 2;
    public static final int PERIOD_YEAR = 3;
    public static final int PERIOD_ALL = 4;

    /** Готовый набор цифр для экрана. */
    public static class Snapshot {
        public double revenue;
        public double expense;
        public double profit;
        public double pending;
        public double avgCheck;
        public double stockValue;
        public int salesCount;
        /** Всего сделок за период: продажи из приложения плюс заказы с сайта. */
        public int dealsCount;
        public int clientsCount;
        public int newClients;
        public int productsCount;
        public int activeClients;

        /** Заказы с сайта (кроме отменённых) за период. */
        public int ordersCount;
        public int ordersNew;
        public double ordersRevenue;
        public double ordersProfit;

        public List<String> chartLabels = new ArrayList<>();
        public List<Double> chartRevenue = new ArrayList<>();
        public List<Double> chartExpense = new ArrayList<>();
        public List<Double> chartSales = new ArrayList<>();

        public List<NameValue> topProducts = new ArrayList<>();
        public List<NameValue> topClients = new ArrayList<>();
        public List<NameValue> expenseCategories = new ArrayList<>();
        public List<NameValue> clientSegments = new ArrayList<>();
    }

    private final Repository repo;
    private final MediatorLiveData<Snapshot> data = new MediatorLiveData<>();
    private int period = PERIOD_QUARTER;

    public AnalyticsViewModel(@NonNull Application application) {
        super(application);
        repo = Repository.get(application);
        // Любое изменение в базе — повод пересчитать отчёт.
        data.addSource(repo.sales(), ignored -> reload());
        data.addSource(repo.expenses(), ignored -> reload());
        data.addSource(repo.clients(), ignored -> reload());
        data.addSource(repo.products(), ignored -> reload());
        data.addSource(repo.orders(), ignored -> reload());
    }

    public LiveData<Snapshot> data() {
        return data;
    }

    public int period() {
        return period;
    }

    public void setPeriod(int period) {
        this.period = period;
        reload();
    }

    public long from() {
        switch (period) {
            case PERIOD_WEEK:
                return Dates.daysAgo(6);
            case PERIOD_MONTH:
                return Dates.daysAgo(29);
            case PERIOD_QUARTER:
                return Dates.monthsAgo(2);
            case PERIOD_YEAR:
                return Dates.monthsAgo(11);
            default:
                return 0;
        }
    }

    private boolean byDays() {
        return period == PERIOD_WEEK || period == PERIOD_MONTH;
    }

    private void reload() {
        final long from = from();
        final long to = Dates.endOfDay(System.currentTimeMillis());
        final String fmt = byDays() ? "%Y-%m-%d" : "%Y-%m";

        repo.query(() -> {
            Snapshot s = new Snapshot();
            OrderDao orderDao = repo.db().orderDao();

            s.revenue = repo.db().saleDao().revenue(from, to);
            s.profit = repo.db().saleDao().profit(from, to);
            s.pending = repo.db().saleDao().pending(from, to);
            s.salesCount = repo.db().saleDao().count(from, to);
            s.activeClients = repo.db().saleDao().activeClients(from, to);
            s.expense = repo.db().expenseDao().total(from, to);

            // Заказы с сайта суммируются в общие цифры — как в панели сайта.
            s.ordersRevenue = orderDao.revenue(from, to);
            s.ordersProfit = orderDao.profit(from, to);
            s.ordersCount = orderDao.count(from, to);
            s.ordersNew = orderDao.countNew(from, to);
            s.revenue += s.ordersRevenue;
            s.profit += s.ordersProfit;

            s.dealsCount = s.salesCount + s.ordersCount;
            s.avgCheck = s.dealsCount > 0 ? s.revenue / s.dealsCount : 0;

            s.clientsCount = repo.db().clientDao().countActive();
            s.newClients = repo.db().clientDao().countNew(from, to);
            s.productsCount = repo.db().productDao().countActive();
            s.stockValue = repo.db().productDao().stockValue();

            List<NameValue> revenue = repo.db().saleDao().revenueByPeriod(fmt, from, to);
            List<NameValue> orderRevenue = orderDao.revenueByPeriod(fmt, from, to);
            List<NameValue> expense = repo.db().expenseDao().byPeriod(fmt, from, to);
            List<NameValue> sales = repo.db().saleDao().countByPeriod(fmt, from, to);
            List<NameValue> orderCounts = orderDao.countByPeriod(fmt, from, to);
            merge(s, revenue, orderRevenue, expense, sales, orderCounts);

            s.topProducts = mergeTop(repo.db().saleDao().topProducts(from, to, 5),
                    orderProductTotals(orderDao, from, to), 5);
            s.topClients = mergeTop(repo.db().saleDao().topClients(from, to, 5),
                    orderClientTotals(orderDao, from, to), 5);
            s.expenseCategories = repo.db().expenseDao().byCategory(from, to);
            s.clientSegments = repo.db().clientDao().countBySegment();
            return s;
        }, data::setValue);
    }

    /** Сводим ряды к общему списку подписей по оси X; заказы сайта приплюсовываются к продажам. */
    private void merge(Snapshot s, List<NameValue> revenue, List<NameValue> orderRevenue,
                       List<NameValue> expense, List<NameValue> sales, List<NameValue> orderCounts) {
        List<String> keys = new ArrayList<>();
        addKeys(keys, revenue);
        addKeys(keys, orderRevenue);
        addKeys(keys, expense);
        addKeys(keys, sales);
        addKeys(keys, orderCounts);
        java.util.Collections.sort(keys);

        for (String key : keys) {
            s.chartLabels.add(label(key));
            s.chartRevenue.add(find(revenue, key) + find(orderRevenue, key));
            s.chartExpense.add(find(expense, key));
            s.chartSales.add(find(sales, key) + find(orderCounts, key));
        }
    }

    /** Суммы по товарам из состава заказов с сайта (позиции лежат в кэше как JSON). */
    private Map<String, Double> orderProductTotals(OrderDao orderDao, long from, long to) {
        Map<String, Double> out = new HashMap<>();
        for (OrderEntity o : orderDao.getAll()) {
            if (o.status == 5 || o.createdAt < from || o.createdAt > to) continue;
            try {
                JSONArray arr = new JSONArray(o.itemsJson == null ? "[]" : o.itemsJson);
                for (int i = 0; i < arr.length(); i++) {
                    String name = arr.getJSONObject(i).optString("name", "").trim();
                    if (name.isEmpty()) continue;
                    Double sum = out.get(name);
                    out.put(name, (sum == null ? 0 : sum) + arr.getJSONObject(i).optDouble("sum", 0));
                }
            } catch (JSONException ignored) {
            }
        }
        return out;
    }

    /** Суммы по покупателям из заказов с сайта. */
    private Map<String, Double> orderClientTotals(OrderDao orderDao, long from, long to) {
        Map<String, Double> out = new HashMap<>();
        for (OrderEntity o : orderDao.getAll()) {
            if (o.status == 5 || o.createdAt < from || o.createdAt > to) continue;
            String name = o.clientName == null ? "" : o.clientName.trim();
            if (name.isEmpty()) name = o.phone == null ? "" : o.phone.trim();
            if (name.isEmpty()) name = "Заказ " + o.number;
            Double sum = out.get(name);
            out.put(name, (sum == null ? 0 : sum) + o.total);
        }
        return out;
    }

    /** Сливаем локальный топ и суммы заказов с сайта, берём первые {@code limit}. */
    private List<NameValue> mergeTop(List<NameValue> local, Map<String, Double> orders, int limit) {
        Map<String, Double> all = new HashMap<>();
        for (NameValue v : local) {
            Double sum = all.get(v.safeName());
            all.put(v.safeName(), (sum == null ? 0 : sum) + v.value);
        }
        for (Map.Entry<String, Double> e : orders.entrySet()) {
            Double sum = all.get(e.getKey());
            all.put(e.getKey(), (sum == null ? 0 : sum) + e.getValue());
        }
        List<NameValue> out = new ArrayList<>();
        for (Map.Entry<String, Double> e : all.entrySet()) {
            out.add(new NameValue(e.getKey(), e.getValue()));
        }
        out.sort((a, c) -> Double.compare(c.value, a.value));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    private static void addKeys(List<String> keys, List<NameValue> list) {
        for (NameValue v : list) if (!keys.contains(v.name)) keys.add(v.name);
    }

    private double find(List<NameValue> list, String key) {
        for (NameValue v : list) {
            if (key.equals(v.name)) return v.value;
        }
        return 0;
    }

    /** «2026-03» → «мар 26», «2026-03-15» → «15.03». */
    private String label(String key) {
        if (key == null) return "";
        if (key.length() >= 10) return key.substring(8, 10) + "." + key.substring(5, 7);
        return Dates.monthLabel(key);
    }
}
