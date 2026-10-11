package com.crmsistems.app.ui.sales;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.crmsistems.app.data.entity.OrderEntity;
import com.crmsistems.app.data.entity.Sale;
import com.crmsistems.app.ui.orders.ServerOrder;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Продажи в разделе «Продажи» должны совпадать с тем, что показывает сайт:
 * сделки из приложения плюс заказы покупателей. Проверяем сборку списка,
 * сопоставление заказов с клиентом и товаром и подсчёт выручки.
 */
public class SaleRowTest {

    private static final long DAY = 24L * 60 * 60 * 1000;

    /** Заказ в том виде, в каком его отдаёт сайт (/api/v1/crm/orders), но уже в кэше. */
    private static OrderEntity order(long id, String number, String phone, String client,
                                     int status, long createdAt, JSONObject... items)
            throws JSONException {
        JSONObject j = new JSONObject();
        j.put("id", id);
        j.put("number", number);
        j.put("clientName", client);
        j.put("phone", phone);
        j.put("status", status);
        j.put("statusName", ServerOrder.statusName(status));
        j.put("createdAt", createdAt);
        j.put("items", new JSONArray(Arrays.asList(items)));

        double total = 0, profit = 0;
        for (JSONObject it : items) {
            total += it.optDouble("sum", 0);
            profit += it.optDouble("sum", 0) - it.optDouble("unitCost", 0) * it.optDouble("qty", 0);
        }
        j.put("total", total);
        j.put("profit", profit);
        return ServerOrder.from(j).toEntity();
    }

    private static JSONObject item(Long productId, String name, double qty,
                                   double price, double cost) throws JSONException {
        JSONObject j = new JSONObject();
        j.put("productId", productId == null ? JSONObject.NULL : productId);
        j.put("name", name);
        j.put("unit", "кг");
        j.put("qty", qty);
        j.put("unitPrice", price);
        j.put("unitCost", cost);
        j.put("sum", qty * price);
        return j;
    }

    private static Sale sale(long id, String product, String client, double qty,
                             double price, double cost, int status, long date) {
        Sale s = new Sale();
        s.id = id;
        s.productName = product;
        s.clientName = client;
        s.qty = qty;
        s.unitPrice = price;
        s.unitCost = cost;
        s.status = status;
        s.date = date;
        return s;
    }

    @Test
    public void mergeShowsSalesAndSiteOrdersNewestFirst() throws JSONException {
        long now = 1_700_000_000_000L;
        Sale s = sale(1, "Сало", "Иванов", 2, 500, 300, Sale.STATUS_PAID, now - DAY);
        OrderEntity fresh = order(10, "№10", "+7 999 111-22-33", "Петров",
                ServerOrder.STATUS_NEW, now, item(5L, "Колбаса", 1, 800, 500));
        OrderEntity canceled = order(11, "№11", "+7 999 111-22-33", "Петров",
                ServerOrder.STATUS_CANCELED, now - 2 * DAY, item(5L, "Колбаса", 3, 800, 500));

        List<SaleRow> rows = SaleRow.merge(
                Collections.singletonList(s), Arrays.asList(fresh, canceled));

        assertEquals(3, rows.size());
        assertEquals("Свежий заказ сверху", "o10", rows.get(0).key());
        assertEquals("s1", rows.get(1).key());
        assertEquals("o11", rows.get(2).key());

        assertTrue(rows.get(0).fromSite());
        assertFalse(rows.get(1).fromSite());
        assertFalse("Отменённый заказ в выручку не идёт", rows.get(2).counted());

        double revenue = 0, profit = 0;
        for (SaleRow row : rows) {
            if (!row.counted()) continue;
            revenue += row.total();
            profit += row.profit();
        }
        assertEquals(1000 + 800, revenue, 0.001);        // продажа 2×500 + заказ 1×800
        assertEquals(400 + 300, profit, 0.001);          // (500−300)×2 + (800−500)×1
    }

    @Test
    public void siteOrderKeepsProductAndCostThroughCache() throws JSONException {
        OrderEntity cached = order(20, "№20", "89991112233", "Петров",
                ServerOrder.STATUS_DONE, 1_700_000_000_000L, item(7L, "Рёбра", 2, 600, 350));

        ServerOrder restored = ServerOrder.fromEntity(cached);
        assertEquals(1, restored.items.size());
        ServerOrder.Item it = restored.items.get(0);
        assertEquals("id товара переживает кэш — иначе позиция не найдёт карточку товара",
                7L, it.productId);
        assertEquals(350.0, it.unitCost, 0.001);
        assertEquals(1200.0, it.sum, 0.001);
        assertEquals(500.0, it.profit(), 0.001);
    }

    @Test
    public void clientGetsOrdersWithSamePhoneInAnyFormat() throws JSONException {
        long now = 1_700_000_000_000L;
        OrderEntity mine = order(30, "№30", "+7 (999) 111-22-33", "Петров",
                ServerOrder.STATUS_DONE, now, item(5L, "Колбаса", 1, 800, 500));
        OrderEntity someoneElse = order(31, "№31", "+7 999 444-55-66", "Сидоров",
                ServerOrder.STATUS_DONE, now, item(5L, "Колбаса", 1, 800, 500));
        Sale s = sale(1, "Сало", "Петров", 1, 500, 300, Sale.STATUS_PAID, now - DAY);

        List<SaleRow> rows = SaleRow.forClient(Collections.singletonList(s),
                Arrays.asList(mine, someoneElse), "8 999 111 22 33");

        assertEquals(2, rows.size());
        assertEquals("o30", rows.get(0).key());
        assertEquals("s1", rows.get(1).key());
    }

    @Test
    public void clientWithoutPhoneGetsOnlyOwnSales() throws JSONException {
        long now = 1_700_000_000_000L;
        OrderEntity any = order(40, "№40", "+7 999 111-22-33", "Петров",
                ServerOrder.STATUS_DONE, now, item(5L, "Колбаса", 1, 800, 500));

        List<SaleRow> rows = SaleRow.forClient(new ArrayList<>(), Collections.singletonList(any), "");
        assertTrue(rows.isEmpty());
    }

    @Test
    public void productGetsOnlyItsOwnOrderLines() throws JSONException {
        long now = 1_700_000_000_000L;
        OrderEntity mixed = order(50, "№50", "+7 999 111-22-33", "Петров",
                ServerOrder.STATUS_DONE, now,
                item(5L, "Колбаса", 2, 800, 500),
                item(9L, "Сало", 1, 500, 300));

        List<SaleRow> rows = SaleRow.forProduct(new ArrayList<>(),
                Collections.singletonList(mixed), 5, "Колбаса");

        assertEquals(1, rows.size());
        SaleRow row = rows.get(0);
        assertEquals(2.0, row.qty(), 0.001);
        assertEquals(1600.0, row.total(), 0.001);
        assertEquals(600.0, row.profit(), 0.001);
        assertEquals("Колбаса", row.title(SaleRow.TITLE_PRODUCT));
        assertEquals("Петров", row.title(SaleRow.TITLE_CLIENT));
    }

    @Test
    public void productMatchesByNameOnlyWhenIdsCannotDecide() throws JSONException {
        long now = 1_700_000_000_000L;
        // Товар с сайта удалили — productId у позиции пустой, остаётся название.
        OrderEntity noId = order(60, "№60", "+7 999 111-22-33", "Петров",
                ServerOrder.STATUS_DONE, now, item(null, "Колбаса", 1, 800, 500));
        // А здесь id известны и не совпадают: одноимённый товар в чужую карточку не попадёт.
        OrderEntity otherId = order(61, "№61", "+7 999 111-22-33", "Петров",
                ServerOrder.STATUS_DONE, now, item(77L, "Колбаса", 1, 800, 500));

        List<SaleRow> rows = SaleRow.forProduct(new ArrayList<>(),
                Arrays.asList(noId, otherId), 5, "Колбаса");

        assertEquals(1, rows.size());
        assertEquals("o60#0#Колбаса", rows.get(0).key());
    }
}
