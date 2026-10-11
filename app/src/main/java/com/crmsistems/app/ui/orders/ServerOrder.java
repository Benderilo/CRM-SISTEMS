package com.crmsistems.app.ui.orders;

import com.crmsistems.app.data.entity.OrderEntity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Заказ с сайта. В базу телефона не пишется: заказы живут на сервере,
 * там же меняются статусы и списываются остатки, поэтому показываем их «как есть».
 */
public class ServerOrder implements Serializable {

    public static final String[] STATUS_NAMES = {
            "Новый", "Принят", "Собирается", "В доставке", "Выполнен", "Отменён"};

    public static final int STATUS_NEW = 0;
    public static final int STATUS_ACCEPTED = 1;
    public static final int STATUS_PACKING = 2;
    public static final int STATUS_DELIVERY = 3;
    public static final int STATUS_DONE = 4;
    public static final int STATUS_CANCELED = 5;

    public long id;
    public String number = "";
    public String clientName = "";
    public String phone = "";
    public String email = "";
    public String address = "";
    public String comment = "";
    public String delivery = "";
    public String payMethod = "";
    public int status;
    public String statusName = "";
    public double total;
    public double profit;
    public long createdAt;
    /**
     * Откуда заказ: пусто — покупку оформили на сайте, «app» — это наша же
     * продажа, улетевшая туда из приложения. В кэш не пишется: источник нужен
     * только сразу после разбора ответа, чтобы не сообщать о своих продажах
     * как о новых заказах.
     */
    public String source = "";
    public final List<Item> items = new ArrayList<>();

    /** Позиция заказа. */
    public static class Item implements Serializable {
        /** id самой позиции на сайте — по нему правится количество в заказе. */
        public long id;
        /** id товара на сайте — по нему позиция попадает в карточку товара. */
        public long productId;
        public String name = "";
        public String unit = "";
        public double qty;
        public double unitPrice;
        /** Себестоимость на момент заказа; CRM-ключ её видит, витрина — нет. */
        public double unitCost;
        public double sum;

        /** Прибыль по позиции. */
        public double profit() {
            return sum - unitCost * qty;
        }
    }

    public static ServerOrder from(JSONObject j) throws JSONException {
        ServerOrder o = new ServerOrder();
        o.id = j.getLong("id");
        o.number = j.optString("number", "№" + o.id);
        o.clientName = j.optString("clientName", "");
        o.phone = j.optString("phone", "");
        o.email = j.optString("email", "");
        o.address = j.optString("address", "");
        o.comment = j.optString("comment", "");
        o.delivery = j.optString("delivery", "");
        o.payMethod = j.optString("payMethod", "");
        o.status = j.optInt("status", 0);
        o.statusName = j.optString("statusName", statusName(o.status));
        o.total = j.optDouble("total", 0);
        o.profit = j.optDouble("profit", 0);
        o.createdAt = j.optLong("createdAt", 0);
        o.source = j.optString("source", "");

        JSONArray arr = j.optJSONArray("items");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject ji = arr.getJSONObject(i);
                o.items.add(itemFrom(ji));
            }
        }
        return o;
    }

    /** Позиция заказа из JSON — формат одинаков и у сайта, и у локального кэша. */
    private static Item itemFrom(JSONObject ji) {
        Item it = new Item();
        it.id = ji.optLong("id", 0);
        it.productId = ji.optLong("productId", 0);
        it.name = ji.optString("name", "");
        it.unit = ji.optString("unit", "");
        it.qty = ji.optDouble("qty", 0);
        it.unitPrice = ji.optDouble("unitPrice", 0);
        it.unitCost = ji.optDouble("unitCost", 0);
        it.sum = ji.optDouble("sum", 0);
        return it;
    }

    public static List<ServerOrder> fromArray(JSONArray arr) throws JSONException {
        List<ServerOrder> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) out.add(from(arr.getJSONObject(i)));
        return out;
    }

    public static String statusName(int s) {
        return s >= 0 && s < STATUS_NAMES.length ? STATUS_NAMES[s] : "—";
    }

    /** Учитывается ли заказ в выручке — на сайте это все заказы, кроме отменённых. */
    public boolean counted() {
        return status != STATUS_CANCELED;
    }

    /** Заказ завёл продавец в приложении, а не покупатель на сайте. */
    public boolean fromApp() {
        return "app".equals(source);
    }

    /**
     * Телефон покупателя как ключ — по нему заказы одного человека узнают
     * друг друга: «8 (999) 000-11-22» и «+7 999 0001122» это один номер.
     */
    public String phoneKey() {
        return com.crmsistems.app.util.Phones.key(phone);
    }

    /**
     * Заказ ещё в работе — его можно объединить с другим заказом того же
     * покупателя. Выполненный и отменённый не трогаем: это уже история,
     * как и продажи, заведённые в приложении.
     */
    public boolean mergeable() {
        return !fromApp() && (status == STATUS_NEW || status == STATUS_ACCEPTED
                || status == STATUS_PACKING);
    }

    /** Кем оформлен заказ — имя, а если его нет, то телефон. */
    public String buyer() {
        if (!clientName.isEmpty()) return clientName;
        return phone;
    }

    /** Краткий состав заказа одной строкой — для списка. */
    public String itemsSummary() {
        StringBuilder sb = new StringBuilder();
        for (Item it : items) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(it.name);
        }
        return sb.toString();
    }

    // --------------------------------------------------------- кэш в базе телефона

    /** Сохранить заказ в кэш-сущность для офлайн-просмотра. */
    public OrderEntity toEntity() {
        OrderEntity e = new OrderEntity();
        e.id = id;
        e.number = number;
        e.clientName = clientName;
        e.phone = phone;
        e.email = email;
        e.address = address;
        e.comment = comment;
        e.delivery = delivery;
        e.payMethod = payMethod;
        e.status = status;
        e.statusName = statusName;
        e.total = total;
        e.profit = profit;
        e.createdAt = createdAt;
        e.itemsJson = itemsToJson();
        e.cachedAt = System.currentTimeMillis();
        return e;
    }

    /** Восстановить заказ из кэш-сущности (когда нет интернета). */
    public static ServerOrder fromEntity(OrderEntity e) {
        ServerOrder o = new ServerOrder();
        o.id = e.id;
        o.number = e.number;
        o.clientName = e.clientName;
        o.phone = e.phone;
        o.email = e.email;
        o.address = e.address;
        o.comment = e.comment;
        o.delivery = e.delivery;
        o.payMethod = e.payMethod;
        o.status = e.status;
        o.statusName = e.statusName;
        o.total = e.total;
        o.profit = e.profit;
        o.createdAt = e.createdAt;
        itemsFromJson(e.itemsJson, o.items);
        return o;
    }

    private String itemsToJson() {
        JSONArray arr = new JSONArray();
        for (Item it : items) {
            JSONObject ji = new JSONObject();
            try {
                ji.put("id", it.id);
                ji.put("productId", it.productId);
                ji.put("name", it.name);
                ji.put("unit", it.unit);
                ji.put("qty", it.qty);
                ji.put("unitPrice", it.unitPrice);
                ji.put("unitCost", it.unitCost);
                ji.put("sum", it.sum);
            } catch (JSONException ignored) {
            }
            arr.put(ji);
        }
        return arr.toString();
    }

    private static void itemsFromJson(String json, List<Item> out) {
        out.clear();
        if (json == null || json.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                out.add(itemFrom(arr.getJSONObject(i)));
            }
        } catch (JSONException ignored) {
        }
    }
}
