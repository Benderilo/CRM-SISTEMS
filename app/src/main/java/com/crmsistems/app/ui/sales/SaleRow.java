package com.crmsistems.app.ui.sales;

import com.crmsistems.app.data.entity.OrderEntity;
import com.crmsistems.app.data.entity.Sale;
import com.crmsistems.app.ui.orders.ServerOrder;
import com.crmsistems.app.util.Dates;
import com.crmsistems.app.util.Money;
import com.crmsistems.app.util.Phones;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Строка списка продаж. Продажи в CRM приходят из двух источников: сделки,
 * заведённые вручную в приложении, и заказы, оформленные покупателями на сайте
 * (в панели сайта именно они и есть продажи). Раньше приложение показывало
 * только первые, поэтому раздел «Продажи» выглядел пустым рядом с сайтом —
 * теперь оба источника сводятся в один список.
 * <p>
 * Заказ показывается одной строкой, а в карточке товара — отдельной позицией
 * заказа, чтобы было видно, сколько именно этого товара продано.
 */
public class SaleRow {

    /** Что показывать первой строкой. */
    public static final int TITLE_PRODUCT = 0;
    public static final int TITLE_CLIENT = 1;

    /** Сделка из приложения либо {@code null}, если строка — заказ с сайта. */
    public final Sale sale;

    /**
     * Продажа из нескольких позиций — все строки с общим groupId. Заполнено
     * вместо {@link #sale}, когда в одной продаже больше одного товара.
     */
    public final List<Sale> group;

    /** Заказ с сайта либо {@code null}, если строка — сделка из приложения. */
    public final ServerOrder order;

    /** Позиция заказа, когда строка показывает один товар из заказа. */
    public final ServerOrder.Item item;

    private SaleRow(Sale sale, List<Sale> group, ServerOrder order, ServerOrder.Item item) {
        this.sale = sale;
        this.group = group;
        this.order = order;
        this.item = item;
    }

    public static SaleRow of(Sale sale) {
        return new SaleRow(sale, null, null, null);
    }

    /** Продажа из нескольких позиций одной строкой списка. */
    public static SaleRow ofGroup(List<Sale> group) {
        return new SaleRow(null, group, null, null);
    }

    public static SaleRow of(ServerOrder order) {
        return new SaleRow(null, null, order, null);
    }

    public static SaleRow of(ServerOrder order, ServerOrder.Item item) {
        return new SaleRow(null, null, order, item);
    }

    /** Первая позиция продажи из нескольких: статус, дата и клиент у них общие. */
    private Sale head() {
        return group.get(0);
    }

    public boolean fromSite() {
        return order != null;
    }

    /** Устойчивый ключ строки — по нему список сравнивает элементы при обновлении. */
    public String key() {
        if (sale != null) return "s" + sale.id;
        if (group != null) return "g" + head().groupId;
        if (item != null) return "o" + order.id + "#" + item.productId + "#" + item.name;
        return "o" + order.id;
    }

    public long date() {
        if (group != null) return head().date;
        return sale != null ? sale.date : order.createdAt;
    }

    /**
     * Телефон покупателя. Есть только у заказов с сайта — в сделке из
     * приложения хранится имя клиента, но не номер.
     */
    public String phone() {
        return order != null ? order.phone : "";
    }

    /**
     * Адрес доставки. Есть только у заказов с сайта: в сделке из приложения
     * его не спрашивают.
     */
    public String address() {
        return order != null && order.address != null ? order.address : "";
    }

    /** Сумма строки. */
    public double total() {
        if (sale != null) return sale.total();
        if (group != null) {
            double sum = 0;
            for (Sale s : group) sum += s.total();
            return sum;
        }
        return item != null ? item.sum : order.total;
    }

    /** Прибыль строки. */
    public double profit() {
        if (sale != null) return sale.profit();
        if (group != null) {
            double sum = 0;
            for (Sale s : group) sum += s.profit();
            return sum;
        }
        return item != null ? item.profit() : order.profit;
    }

    /** Количество товара — заполнено у сделок и у позиций заказа. */
    public double qty() {
        if (sale != null) return sale.qty;
        // У продажи из нескольких позиций общего количества нет: товары разные.
        if (group != null) return 0;
        return item != null ? item.qty : 0;
    }

    /** Идёт ли строка в выручку: оплаченные сделки и все заказы, кроме отменённых. */
    public boolean counted() {
        if (group != null) return head().status == Sale.STATUS_PAID;
        return sale != null ? sale.status == Sale.STATUS_PAID : order.counted();
    }

    /** Деньги ещё не получены — только для сделок из приложения. */
    public boolean pending() {
        if (group != null) return head().status == Sale.STATUS_PENDING;
        return sale != null && sale.status == Sale.STATUS_PENDING;
    }

    public String statusName() {
        if (group != null) return head().statusName();
        return sale != null ? sale.statusName() : order.statusName;
    }

    /**
     * Как красить сумму: 0 — доход (зелёный), 1 — ожидание (жёлтый),
     * 2 — не состоялось (красный).
     */
    public int tone() {
        Sale own = sale != null ? sale : (group != null ? head() : null);
        if (own != null) {
            if (own.status == Sale.STATUS_PAID) return 0;
            return own.status == Sale.STATUS_PENDING ? 1 : 2;
        }
        if (order.status == ServerOrder.STATUS_CANCELED) return 2;
        return order.status == ServerOrder.STATUS_DONE ? 0 : 1;
    }

    /**
     * Статус в шкале заказов сайта (0..5) — по нему красится карточка.
     * У сделки из приложения своих три состояния, поэтому приводим их к той же
     * шкале: ждёт оплаты — как новый заказ, оплачено — как выполненный.
     * Отличается от {@link #tone()}: тот огрубляет всё до трёх цветов и служит
     * фильтру списка, а здесь нужны все шесть.
     */
    public int colorStatus() {
        Sale own = sale != null ? sale : (group != null ? head() : null);
        if (own == null) return order.status;
        switch (own.status) {
            case Sale.STATUS_PENDING:
                return ServerOrder.STATUS_NEW;
            case Sale.STATUS_CANCELED:
                return ServerOrder.STATUS_CANCELED;
            default:
                return ServerOrder.STATUS_DONE;
        }
    }

    public String title(int titleMode) {
        if (group != null) {
            if (titleMode == TITLE_CLIENT) {
                String client = head().clientName;
                return client.isEmpty() ? "Без клиента" : client;
            }
            return productNames();
        }
        if (sale != null) {
            String product = sale.productName.isEmpty() ? "Товар удалён" : sale.productName;
            String client = sale.clientName.isEmpty() ? "Без клиента" : sale.clientName;
            return titleMode == TITLE_CLIENT ? client : product;
        }
        String client = order.clientName.isEmpty() ? order.phone : order.clientName;
        if (client.isEmpty()) client = "Покупатель с сайта";
        if (titleMode == TITLE_CLIENT) return client;
        String products = item != null ? item.name : order.itemsSummary();
        return products.isEmpty() ? order.number : products;
    }

    public String subtitle(int titleMode) {
        StringBuilder sb = new StringBuilder(Dates.formatDate(date()));
        if (group != null) {
            // Состав показываем построчно — так же, как в карточке заказа.
            String client = head().clientName.isEmpty() ? "Без клиента" : head().clientName;
            sb.append(" · ").append(titleMode == TITLE_CLIENT ? productNames() : client);
            for (Sale s : group) {
                sb.append('\n').append(s.productName.isEmpty() ? "Товар удалён" : s.productName)
                        .append(" · ").append(Money.qty(s.qty))
                        .append(" × ").append(Money.format(s.unitPrice));
            }
            return sb.toString();
        }
        if (sale != null) {
            String product = sale.productName.isEmpty() ? "Товар удалён" : sale.productName;
            String client = sale.clientName.isEmpty() ? "Без клиента" : sale.clientName;
            sb.append(" · ").append(titleMode == TITLE_CLIENT ? product : client).append('\n')
                    .append(Money.qty(sale.qty)).append(" × ").append(Money.format(sale.unitPrice));
            if (sale.discount > 0) sb.append(" − скидка ").append(Money.format(sale.discount));
            return sb.toString();
        }

        // Заказ с сайта: помечаем источник, чтобы строка не путалась со сделкой.
        String client = order.clientName.isEmpty() ? order.phone : order.clientName;
        if (client.isEmpty()) client = "Покупатель с сайта";
        String products = item != null ? item.name : order.itemsSummary();
        sb.append(" · Сайт, заказ ").append(order.number).append('\n')
                .append(titleMode == TITLE_CLIENT ? products : client);
        if (item != null && item.qty > 0) {
            sb.append('\n').append(Money.qty(item.qty)).append(' ').append(item.unit)
                    .append(" × ").append(Money.format(item.unitPrice));
        }
        return sb.toString();
    }

    /** Названия товаров продажи через запятую. */
    private String productNames() {
        StringBuilder sb = new StringBuilder();
        for (Sale s : group) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s.productName.isEmpty() ? "Товар удалён" : s.productName);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ сборка

    /** Все продажи: сделки приложения плюс заказы сайта, свежие сверху. */
    public static List<SaleRow> merge(List<Sale> sales, List<OrderEntity> orders) {
        List<SaleRow> rows = new ArrayList<>();
        if (sales != null) {
            for (Sale s : sales) rows.add(of(s));
        }
        if (orders != null) {
            for (OrderEntity e : orders) rows.add(of(ServerOrder.fromEntity(e)));
        }
        sortByDate(rows);
        return rows;
    }

    /** Покупки одного клиента: его сделки плюс заказы с сайта с тем же телефоном. */
    public static List<SaleRow> forClient(List<Sale> sales, List<OrderEntity> orders, String phone) {
        List<SaleRow> rows = new ArrayList<>(groupRows(sales));
        String key = Phones.key(phone);
        if (orders != null && !key.isEmpty()) {
            for (OrderEntity e : orders) {
                if (key.equals(Phones.key(e.phone))) rows.add(of(ServerOrder.fromEntity(e)));
            }
        }
        sortByDate(rows);
        return rows;
    }

    /**
     * Продажи одного товара: его сделки плюс позиции заказов с сайта.
     * Позиция узнаётся по id товара на сайте, а если его нет (товар заведён
     * только в приложении) — по названию.
     */
    public static List<SaleRow> forProduct(List<Sale> sales, List<OrderEntity> orders,
                                           long serverId, String name) {
        List<SaleRow> rows = new ArrayList<>();
        if (sales != null) {
            for (Sale s : sales) rows.add(of(s));
        }
        String title = name == null ? "" : name.trim();
        if (orders != null) {
            for (OrderEntity e : orders) {
                ServerOrder o = ServerOrder.fromEntity(e);
                for (ServerOrder.Item it : o.items) {
                    boolean byId = serverId != 0 && it.productId == serverId;
                    // По названию сверяем, только если id сопоставить нечем:
                    // иначе одноимённые товары попали бы в чужую карточку.
                    boolean byName = (serverId == 0 || it.productId == 0)
                            && !title.isEmpty() && title.equalsIgnoreCase(it.name.trim());
                    if (byId || byName) rows.add(of(o, it));
                }
            }
        }
        sortByDate(rows);
        return rows;
    }

    /**
     * Продажи одной строкой на продажу: позиции с общим groupId сходятся
     * в одну строку, остальные остаются как есть.
     */
    private static List<SaleRow> groupRows(List<Sale> sales) {
        List<SaleRow> rows = new ArrayList<>();
        if (sales == null) return rows;
        Map<Long, List<Sale>> groups = new LinkedHashMap<>();
        for (Sale s : sales) {
            if (s.groupId == 0) {
                rows.add(of(s));
                continue;
            }
            List<Sale> group = groups.get(s.groupId);
            if (group == null) {
                group = new ArrayList<>();
                groups.put(s.groupId, group);
            }
            group.add(s);
        }
        for (List<Sale> group : groups.values()) {
            rows.add(group.size() == 1 ? of(group.get(0)) : ofGroup(group));
        }
        return rows;
    }

    private static void sortByDate(List<SaleRow> rows) {
        Collections.sort(rows, (a, b) -> Long.compare(b.date(), a.date()));
    }
}
