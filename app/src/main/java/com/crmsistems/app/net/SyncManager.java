package com.crmsistems.app.net;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import com.crmsistems.app.data.AppDatabase;
import com.crmsistems.app.data.Repository;
import com.crmsistems.app.data.entity.Client;
import com.crmsistems.app.data.entity.OrderEntity;
import com.crmsistems.app.data.entity.PendingChange;
import com.crmsistems.app.data.entity.Product;
import com.crmsistems.app.data.entity.Sale;
import com.crmsistems.app.ui.orders.OrderNotifier;
import com.crmsistems.app.ui.orders.ServerOrder;
import com.crmsistems.app.util.Money;
import com.crmsistems.app.util.Photos;
import com.crmsistems.app.util.Prefs;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Обмен данными между телефоном и сайтом.
 * <p>
 * Порядок такой: сначала отправляем накопленные офлайн-правки (исходящие),
 * затем забираем с сайта заказы (они приоритетны — там клиенты оформляют
 * покупки), потом выгружаем на сайт новые товары/клиенты из приложения и
 * вливаем полный список с сайта в базу телефона. При совпадении сайт считается
 * главным — он видит заказы покупателей и списывает остатки, поэтому его
 * данные свежее.
 * <p>
 * Записи сопоставляются по serverId, а если его нет — по артикулу (товары)
 * и телефону (клиенты), чтобы после первого подключения не появились дубли.
 * <p>
 * Заказы в базу телефона кэшируются только для просмотра офлайн — автором
 * списка и статусов остаётся сайт.
 */
public final class SyncManager {

    /** Что получилось по итогам обмена — показываем это пользователю. */
    public static class Result {
        public int productsPulled;
        public int productsPushed;
        public int clientsPulled;
        public int clientsPushed;
        public int orders;
        /** Продажи из приложения, ушедшие на сайт заказами. */
        public int salesPushed;
        public int pendingFlushed;
        /** Записи, которые сайт не принял (пустое название, кривой телефон). */
        public int rejected;
        /** Заказы с сайта, которых до этого обмена в приложении не было. */
        public final List<ServerOrder> newOrders = new ArrayList<>();
        /** Сколько заказов висит в статусе «Новый» — по ним живёт напоминание. */
        public int pendingOrders;
        /** Сколько лишних карточек убрало объединение заказов одного покупателя. */
        public int ordersMerged;
        public String error;

        public boolean ok() {
            return error == null;
        }

        /** Короткая строка для всплывающего сообщения. */
        public String summary() {
            if (error != null) return error;
            StringBuilder sb = new StringBuilder();
            if (pendingFlushed > 0) sb.append("Отправлено офлайн-правок: ").append(pendingFlushed).append(", ");
            if (salesPushed > 0) sb.append("заказов на сайт: ").append(salesPushed).append(", ");
            sb.append("Товаров: ").append(productsPulled)
                    .append(productsPushed > 0 ? " (+" + productsPushed + " на сайт)" : "")
                    .append(", клиентов: ").append(clientsPulled)
                    .append(clientsPushed > 0 ? " (+" + clientsPushed + ")" : "")
                    .append(", заказов: ").append(orders);
            if (rejected > 0) {
                sb.append(". Сайт не принял записей: ").append(rejected)
                        .append(" — проверьте название товара и телефон клиента");
            }
            return sb.toString();
        }
    }

    public interface Callback {
        void onDone(Result result);
    }

    /**
     * Обмен с сайтом идёт по одному на всё приложение. Раньше два потока
     * (например, автосинхронизация при запуске и «потянули список вниз»)
     * успевали прочитать одни и те же неотправленные продажи до их удаления
     * и отправляли каждую дважды — на сайте появлялись заказы-двойники.
     */
    private static final java.util.concurrent.locks.ReentrantLock SYNC_LOCK =
            new java.util.concurrent.locks.ReentrantLock(true);

    private final Context app;
    private final Repository repo;
    private final Handler main = new Handler(Looper.getMainLooper());

    public SyncManager(Context context) {
        this.app = context.getApplicationContext();
        this.repo = Repository.get(context);
    }

    /** Полная синхронизация в фоне, результат приходит в главный поток. */
    public void syncAll(Callback callback) {
        repo.io().execute(() -> {
            Result r = syncBlocking();
            main.post(() -> callback.onDone(r));
        });
    }

    /** То же самое, но синхронно — только для фонового потока. */
    public Result syncBlocking() {
        Result r = new Result();
        if (!Prefs.serverReady(app)) {
            r.error = "Сначала укажите адрес сайта и ключ доступа";
            return r;
        }
        ServerApi api = new ServerApi(app);
        AppDatabase db = repo.db();
        SYNC_LOCK.lock();
        try {
            // 1. Сначала отправляем то, что накопилось без интернета.
            flushPending(api, db, r);
            // 2. Свои товары и клиенты уходят раньше продаж: заказ на сайте
            //    должен ссылаться на уже существующие карточки.
            pushNewProducts(api, db, r);
            pushNewClients(api, db, r);
            // 3. Продажи из приложения — на сайт заказами; следом тянем заказы,
            //    и только что отправленные продажи сразу возвращаются в кэш.
            pushSales(api, db, r);
            pullOrdersMerged(api, db, r);
            // 4. Полные списки товаров и клиентов обратно (заодно свежие остатки).
            pullProducts(api, db, r);
            pullClients(api, db, r);
            Prefs.setLastSync(app, System.currentTimeMillis());
        } catch (ServerApi.ApiException e) {
            r.error = e.getMessage();
        } catch (JSONException e) {
            r.error = "Сайт прислал неожиданный ответ";
        } finally {
            SYNC_LOCK.unlock();
        }
        return r;
    }

    // ------------------------------------------------------------- исходящие

    /**
     * Отправить накопленные офлайн-правки на сайт и удалить их из очереди.
     * Если сеть пропала прямо в процессе — прерываем (оставшиеся подождут
     * следующего подключения). Записи, которые сайт уже не может принять
     * (404 — удалили, 400 — неверные данные), выбрасываем, иначе зависнут навсегда.
     */
    private void flushPending(ServerApi api, AppDatabase db, Result r)
            throws ServerApi.ApiException, JSONException {
        List<PendingChange> pending = db.pendingChangeDao().getAll();
        for (PendingChange pc : pending) {
            try {
                switch (pc.kind) {
                    case 0: {  // смена статуса заказа
                        int status = new JSONObject(pc.payload).optInt("status", -1);
                        if (status < 0) {
                            db.pendingChangeDao().delete(pc.id);
                            break;
                        }
                        api.setOrderStatus(pc.refId, status);
                        db.pendingChangeDao().delete(pc.id);
                        r.pendingFlushed++;
                        break;
                    }
                    case 1: {  // товар
                        Product p = db.productDao().getById(pc.refId);
                        if (p == null) {
                            db.pendingChangeDao().delete(pc.id);
                            break;
                        }
                        uploadPhotoIfNeeded(api, p);
                        JSONObject saved = api.saveProduct(productToJson(p)).getJSONObject("product");
                        p.serverId = saved.getLong("id");
                        db.productDao().update(p);
                        db.pendingChangeDao().delete(pc.id);
                        r.pendingFlushed++;
                        break;
                    }
                    case 2: {  // клиент
                        Client c = db.clientDao().getById(pc.refId);
                        if (c == null) {
                            db.pendingChangeDao().delete(pc.id);
                            break;
                        }
                        if (TextUtils.isEmpty(c.phone)) {
                            // Без телефона сайт клиента не примет — оставляем запись,
                            // пользователь поправит телефон и тогда уйдёт.
                            break;
                        }
                        JSONObject saved = api.saveClient(clientToJson(c)).getJSONObject("client");
                        c.serverId = saved.getLong("id");
                        db.clientDao().update(c);
                        db.pendingChangeDao().delete(pc.id);
                        r.pendingFlushed++;
                        break;
                    }
                    case 3: {  // удаление клиента
                        long serverId = new JSONObject(pc.payload).optLong("serverId", 0);
                        if (serverId > 0) {
                            api.deleteClient(serverId);
                            // На случай, если pull успел вернуть карточку назад.
                            db.clientDao().deleteByServerId(serverId);
                        }
                        db.pendingChangeDao().delete(pc.id);
                        r.pendingFlushed++;
                        break;
                    }
                    default:
                        db.pendingChangeDao().delete(pc.id);
                }
            } catch (ServerApi.ApiException e) {
                if (e.code == 0) throw e;          // связь пропала — стопаем
                if (e.code == 400 || e.code == 404) {
                    db.pendingChangeDao().delete(pc.id);  // не отправить никак — выбрасываем
                }
                // 5xx и прочее — оставляем, попробуем в следующий раз
            } catch (JSONException e) {
                db.pendingChangeDao().delete(pc.id);  // битый payload — выбрасываем
            }
        }
    }

    /** Поставить смену статуса заказа в очередь офлайн-правок. Вызывать из фонового потока. */
    public static void enqueueOrderStatus(Context ctx, long orderId, int status) {
        PendingChange pc = new PendingChange();
        pc.kind = 0;
        pc.refId = orderId;
        pc.payload = "{\"status\":" + status + "}";
        pc.createdAt = System.currentTimeMillis();
        AppDatabase db = Repository.get(ctx).db();
        db.pendingChangeDao().deleteKindRef(0, orderId);
        db.pendingChangeDao().insert(pc);
        // Оптимистично показываем новый статус в кэше, пока правка не ушла на сайт.
        db.orderDao().updateStatus(orderId, status, ServerOrder.statusName(status));
    }

    /** Поставить правку товара в очередь офлайн-правок (по локальному id). */
    public static void enqueueProduct(Context ctx, long localId) {
        enqueue(ctx, 1, localId);
    }

    /** Поставить правку клиента в очередь офлайн-правок (по локальному id). */
    public static void enqueueClient(Context ctx, long localId) {
        enqueue(ctx, 2, localId);
    }

    /**
     * Поставить удаление клиента в очередь офлайн-правок. Самой карточки вскоре
     * не станет, поэтому id на сайте возим в payload.
     */
    public static void enqueueClientDelete(Context ctx, long localId, long serverId) {
        PendingChange pc = new PendingChange();
        pc.kind = 3;
        pc.refId = localId;
        pc.payload = "{\"serverId\":" + serverId + "}";
        pc.createdAt = System.currentTimeMillis();
        AppDatabase db = Repository.get(ctx).db();
        db.pendingChangeDao().deleteKindRef(3, localId);
        db.pendingChangeDao().insert(pc);
    }

    private static void enqueue(Context ctx, int kind, long refId) {
        PendingChange pc = new PendingChange();
        pc.kind = kind;
        pc.refId = refId;
        pc.createdAt = System.currentTimeMillis();
        AppDatabase db = Repository.get(ctx).db();
        db.pendingChangeDao().deleteKindRef(kind, refId);
        db.pendingChangeDao().insert(pc);
    }

    // -------------------------------------------------------------- продажи

    /**
     * Продажи, заведённые в приложении, уходят на сайт заказами: в админке
     * должно быть видно всё, что продано, независимо от того, где оформили.
     * <p>
     * После удачной отправки локальная запись удаляется — дальше эта продажа
     * живёт как заказ на сайте (сайт главный, и двойного учёта не возникает:
     * заказ тут же возвращается в кэш при pullOrders).
     */
    private void pushSales(ServerApi api, AppDatabase db, Result r)
            throws ServerApi.ApiException, JSONException {
        for (List<Sale> group : groupSales(db.saleDao().getAll())) {
            // Товар ещё не знаком сайту (serverId = 0): заказ без ссылки на
            // товар сервер не спишет, а продажу после отправки мы удалим —
            // списание потеряется. Держим продажу в телефоне до синхронизации
            // товара, и всю продажу целиком: половина состава на сайте хуже,
            // чем ничего.
            if (!readyForSite(db, group)) continue;
            try {
                api.createOrder(salesToJson(db, group));
            } catch (ServerApi.ApiException e) {
                if (e.code == 0) throw e;  // связь пропала — остальные подождут
                continue;                  // сайт не принял: продажа остаётся в телефоне
            }
            for (Sale s : group) db.saleDao().delete(s);
            r.salesPushed++;
        }
    }

    /**
     * Разложить продажи по группам: строки с общим groupId — одна продажа
     * из нескольких позиций, она уходит на сайт одним заказом. Продажи
     * с groupId = 0 остаются каждая сама по себе.
     */
    private static List<List<Sale>> groupSales(List<Sale> sales) {
        List<List<Sale>> groups = new ArrayList<>();
        Map<Long, List<Sale>> byGroup = new HashMap<>();
        for (Sale s : sales) {
            if (s.groupId == 0) {
                groups.add(new ArrayList<>(java.util.Collections.singletonList(s)));
                continue;
            }
            List<Sale> group = byGroup.get(s.groupId);
            if (group == null) {
                group = new ArrayList<>();
                byGroup.put(s.groupId, group);
                groups.add(group);
            }
            group.add(s);
        }
        return groups;
    }

    /** Все товары продажи уже выгружены на сайт. */
    private static boolean readyForSite(AppDatabase db, List<Sale> group) {
        for (Sale s : group) {
            if (s.productId == null) continue;
            Product p = db.productDao().getById(s.productId);
            if (p != null && p.serverId == 0) return false;
        }
        return true;
    }

    /**
     * Продажа в виде заказа для сайта: цены снимком, скидка сложена в цену.
     * Продажа из нескольких позиций уходит одним заказом со всем составом.
     * Склад сайт списывает сам — продали, значит товара стало меньше.
     * Поле writeOff — совместимость со старой сборкой сервера, которая без
     * этого флага не списывала остаток; новая сборка поле игнорирует.
     */
    private JSONObject salesToJson(AppDatabase db, List<Sale> group) throws JSONException {
        Sale head = group.get(0);
        JSONArray items = new JSONArray();
        double discount = 0;
        StringBuilder note = new StringBuilder();
        for (Sale s : group) {
            JSONObject item = new JSONObject();
            String name = s.productName;
            String unit = "шт";
            if (s.productId != null) {
                Product p = db.productDao().getById(s.productId);
                if (p != null) {
                    item.put("productId", p.serverId);
                    if (name.isEmpty()) name = p.name;
                    unit = p.unit;
                }
            }
            item.put("name", name.isEmpty() ? "Продажа" : name);
            item.put("unit", unit);
            item.put("qty", s.qty);
            // Скидка на сайте отдельным полем не живёт — уменьшаем цену позиции,
            // чтобы сумма и прибыль заказа совпали с продажей до копейки.
            item.put("unitPrice",
                    s.qty > 0 ? (s.qty * s.unitPrice - s.discount) / s.qty : s.unitPrice);
            item.put("unitCost", s.unitCost);
            items.put(item);

            discount += s.discount;
            if (!s.note.isEmpty()) {
                if (note.length() > 0) note.append('\n');
                note.append(s.note);
            }
        }

        JSONObject j = new JSONObject();
        j.put("items", items);
        j.put("status", orderStatusOf(head.status));
        j.put("writeOff", head.status != Sale.STATUS_CANCELED);
        j.put("createdAt", head.date);
        j.put("clientName", head.clientName);
        if (head.clientId != null) {
            Client c = db.clientDao().getById(head.clientId);
            if (c != null) {
                j.put("clientId", c.serverId);
                if (head.clientName.isEmpty()) j.put("clientName", c.name);
                j.put("phone", c.phone);
                j.put("address", c.address);
            }
        }
        StringBuilder comment = new StringBuilder(note);
        if (discount > 0) {
            if (comment.length() > 0) comment.append('\n');
            comment.append("Скидка ").append(Money.plain(discount));
        }
        j.put("comment", comment.toString());
        return j;
    }

    /** Статус продажи в статусе заказа сайта. */
    private static int orderStatusOf(int saleStatus) {
        switch (saleStatus) {
            case Sale.STATUS_PENDING:
                return 1;  // Принят — деньги ещё не получены
            case Sale.STATUS_CANCELED:
                return ServerOrder.STATUS_CANCELED;
            default:
                return ServerOrder.STATUS_DONE;  // Оплачено
        }
    }

    /**
     * Отправить продажи на сайт прямо сейчас — вызывается после сохранения
     * карточки, чтобы заказ появился в админке сразу, а не при следующем обмене.
     * Свои товары и клиенты уходят раньше продаж — иначе заказ с товаром,
     * которого сайт ещё не знает, останется без ссылки и без списания склада.
     */
    public void pushSalesNow(Callback callback) {
        repo.io().execute(() -> {
            Result r = new Result();
            SYNC_LOCK.lock();
            try {
                ServerApi api = new ServerApi(app);
                pushNewProducts(api, repo.db(), r);
                pushNewClients(api, repo.db(), r);
                pushSales(api, repo.db(), r);
                pullOrdersMerged(api, repo.db(), r);
            } catch (ServerApi.ApiException e) {
                r.error = e.code == 0
                        ? "Нет связи: продажа уйдёт на сайт при появлении интернета"
                        : e.getMessage();
            } catch (JSONException e) {
                r.error = "Сайт прислал неожиданный ответ";
            } finally {
                SYNC_LOCK.unlock();
            }
            if (callback != null) main.post(() -> callback.onDone(r));
        });
    }

    // --------------------------------------------------------------- заказы

    /**
     * Свежие заказы с сайта для экрана списка: сначала отправляем накопленные
     * офлайн-правки (иначе смена статуса, сделанная без связи, визуально
     * откатилась бы на старое значение), затем тянем список и обновляем кэш.
     * Блокирующий — вызывать только из фонового потока.
     */
    public List<ServerOrder> refreshOrdersBlocking()
            throws ServerApi.ApiException, JSONException {
        return refreshOrdersBlocking(new Result());
    }

    /** То же самое, но вызывающий получает и сводку обмена — в ней новые заказы. */
    public List<ServerOrder> refreshOrdersBlocking(Result r)
            throws ServerApi.ApiException, JSONException {
        SYNC_LOCK.lock();
        try {
            ServerApi api = new ServerApi(app);
            flushPending(api, repo.db(), r);
            pullOrdersMerged(api, repo.db(), r);
            List<ServerOrder> out = new ArrayList<>();
            for (OrderEntity e : repo.db().orderDao().getAll()) {
                out.add(ServerOrder.fromEntity(e));
            }
            return out;
        } finally {
            SYNC_LOCK.unlock();
        }
    }

    /**
     * Заказы с сайта — в локальный кэш (полной заменой). Сайт остаётся главным.
     * <p>
     * Заодно считаем две вещи. Первая — заказы, которых в кэше ещё не было:
     * о них всплывает подсказка внутри приложения. Тут два исключения: кэш был
     * пуст (первый обмен — иначе всплыл бы весь архив магазина) и заказ помечен
     * источником «app», то есть это наша же продажа, только что улетевшая на
     * сайт из приложения.
     * <p>
     * Вторая — заказы в статусе «Новый»: по ним живёт напоминание в шторке.
     * Считаем их заново каждый раз, поэтому оно и появляется, и пропадает само.
     */
    /**
     * Забрать заказы и заодно свести дубли одного покупателя.
     * <p>
     * Сайт объединяет заказы сам, ещё при оформлении: второй заказ с того же
     * телефона дописывается в открытый первый. Но в магазине уже накопились
     * дубли, разведённые до этого, — их подчищаем отсюда, по одному запросу
     * на покупателя, и после этого забираем список заново.
     */
    private void pullOrdersMerged(ServerApi api, AppDatabase db, Result r)
            throws ServerApi.ApiException, JSONException {
        List<ServerOrder> orders = pullOrders(api, db, r);
        if (mergeDuplicates(api, orders, r)) pullOrders(api, db, r);
    }

    /**
     * Найти заказы с одинаковым телефоном и попросить сайт слепить их в один.
     * Возвращает true, если хоть что-то объединилось, — тогда список надо
     * перечитать. Сбои не роняют обмен: заказы просто останутся раздельными.
     */
    private boolean mergeDuplicates(ServerApi api, List<ServerOrder> orders, Result r) {
        Map<String, List<Long>> byPhone = new HashMap<>();
        for (ServerOrder o : orders) {
            if (!o.mergeable()) continue;
            String key = o.phoneKey();
            if (key.isEmpty()) continue;
            List<Long> ids = byPhone.get(key);
            if (ids == null) byPhone.put(key, ids = new ArrayList<>());
            ids.add(o.id);
        }

        boolean merged = false;
        for (List<Long> ids : byPhone.values()) {
            if (ids.size() < 2) continue;
            try {
                api.mergeOrders(ids);
                r.ordersMerged += ids.size() - 1;
                merged = true;
            } catch (ServerApi.ApiException e) {
                // Нет связи — дальше идти незачем, объединим при следующем обмене.
                if (e.code == 0) break;
                // Сайт отказал (заказ успели закрыть с другого устройства) —
                // не беда, показываем как есть.
            }
        }
        return merged;
    }

    private List<ServerOrder> pullOrders(ServerApi api, AppDatabase db, Result r)
            throws ServerApi.ApiException, JSONException {
        JSONArray arr = api.orders();
        Set<Long> known = new HashSet<>(db.orderDao().allIds());
        boolean firstFill = known.isEmpty();

        List<OrderEntity> rows = new ArrayList<>(arr.length());
        List<ServerOrder> all = new ArrayList<>(arr.length());
        List<ServerOrder> pending = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            ServerOrder o = ServerOrder.from(arr.getJSONObject(i));
            all.add(o);
            rows.add(o.toEntity());
            if (!firstFill && !known.contains(o.id) && !o.fromApp()) r.newOrders.add(o);
            if (o.status == ServerOrder.STATUS_NEW) pending.add(o);
        }

        // Заказ, пропавший с сайта (удалили в приложении, в панели управления
        // или чисткой архива), из кэша не выбрасываем — помечаем удалённым.
        // В приложении его больше не видно, а в выгрузке в Excel он остаётся:
        // стёртые выполненные заказы иначе исчезали бы и из файла.
        long now = System.currentTimeMillis();
        List<Long> aliveIds = new ArrayList<>(rows.size());
        for (OrderEntity e : rows) aliveIds.add(e.id);
        if (aliveIds.isEmpty()) {
            db.orderDao().markAllDeleted(now);
        } else {
            db.orderDao().markDeletedExcept(aliveIds, now);
        }
        if (!rows.isEmpty()) db.orderDao().insertAll(rows);
        r.orders = rows.size();
        r.pendingOrders = pending.size();
        OrderNotifier.notifyPending(app, pending);
        return all;
    }

    // ------------------------------------------------------------- товары

    /** Товары, заведённые в приложении и ещё не known сайту, отправляем туда. */
    private void pushNewProducts(ServerApi api, AppDatabase db, Result r)
            throws ServerApi.ApiException, JSONException {
        for (Product p : db.productDao().getAll()) {
            if (p.serverId != 0) continue;
            try {
                uploadPhotoIfNeeded(api, p);
                JSONObject resp = api.saveProduct(productToJson(p));
                JSONObject saved = resp.getJSONObject("product");
                p.serverId = saved.getLong("id");
                db.productDao().update(p);
                r.productsPushed++;
            } catch (ServerApi.ApiException e) {
                // Связь пропала — дальше идти незачем, остальные подождут.
                if (e.code == 0) throw e;
                // Сайт не принял карточку (например, пустое название). Раньше это
                // роняло весь обмен: не уходили продажи, не приходили заказы,
                // и так при каждом запуске. Теперь пропускаем и считаем.
                r.rejected++;
            }
        }
    }

    private void pullProducts(ServerApi api, AppDatabase db, Result r)
            throws ServerApi.ApiException, JSONException {
        JSONArray arr = api.products();

        Map<Long, Product> byServerId = new HashMap<>();
        Map<String, Product> bySku = new HashMap<>();
        for (Product p : db.productDao().getAll()) {
            if (p.serverId != 0) byServerId.put(p.serverId, p);
            if (!TextUtils.isEmpty(p.sku)) bySku.put(p.sku, p);
        }

        for (int i = 0; i < arr.length(); i++) {
            JSONObject j = arr.getJSONObject(i);
            long serverId = j.getLong("id");
            String sku = j.optString("sku", "");

            Product p = byServerId.get(serverId);
            if (p == null && !TextUtils.isEmpty(sku)) p = bySku.get(sku);

            boolean isNew = p == null;
            if (isNew) p = new Product();
            applyProduct(p, j);
            p.serverId = serverId;
            p.updatedAt = System.currentTimeMillis();

            if (isNew) {
                p.createdAt = p.updatedAt;
                db.productDao().insert(p);
            } else {
                db.productDao().update(p);
            }
            r.productsPulled++;
        }
    }

    private void applyProduct(Product p, JSONObject j) {
        p.name = j.optString("name", "");
        p.sku = j.optString("sku", "");
        p.category = j.optString("category", "");
        p.unit = j.optString("unit", "шт");
        p.price = j.optDouble("price", 0);
        p.cost = j.optDouble("cost", 0);
        p.stock = j.optDouble("stock", 0);
        p.minStock = j.optDouble("minStock", 0);
        p.barcode = j.optString("barcode", "");
        p.description = j.optString("description", "");
        p.archived = j.optBoolean("archived", false);
        p.published = j.optBoolean("published", true);
        // Путь к фото на сайте. Локальное photoUri и флаг photoUploaded не трогаем:
        // локальный снимок — источник правды для приложения, а флаг показывает,
        // загружен ли он уже на сайт.
        p.serverImage = j.optString("image", "");
    }

    /**
     * Карточка товара для сайта. Остаток отправляем такой, каким он был до
     * продаж, ещё не ушедших на сайт: их сервер спишет сам, когда получит
     * заказы, — иначе одно и то же количество вычлось бы дважды и товар
     * потихоньку «утекал» бы со склада.
     */
    private JSONObject productToJson(Product p) throws JSONException {
        double unsent = p.id > 0 ? repo.db().saleDao().unsentQty(p.id) : 0;
        JSONObject j = new JSONObject();
        j.put("id", p.serverId);
        j.put("name", p.name);
        j.put("sku", p.sku);
        j.put("category", p.category);
        j.put("unit", p.unit);
        j.put("price", p.price);
        j.put("cost", p.cost);
        j.put("stock", p.stock + unsent);
        j.put("minStock", p.minStock);
        j.put("barcode", p.barcode);
        j.put("description", p.description);
        j.put("archived", p.archived);
        j.put("published", p.published);
        j.put("image", p.serverImage);
        return j;
    }

    /** Выгрузить один товар на сайт — вызывается после правки карточки в приложении. */
    public void pushProduct(Product p, Callback callback) {
        repo.io().execute(() -> {
            Result r = new Result();
            SYNC_LOCK.lock();
            try {
                ServerApi api = new ServerApi(app);
                uploadPhotoIfNeeded(api, p);
                JSONObject saved = api.saveProduct(productToJson(p)).getJSONObject("product");
                p.serverId = saved.getLong("id");
                repo.db().productDao().update(p);
                r.productsPushed = 1;
            } catch (ServerApi.ApiException e) {
                if (e.code == 0) {
                    // Нет связи — сохраним правку в очередь, отправим при появлении интернета.
                    enqueueProduct(app, p.id);
                    r.error = "Нет связи: правка сохранена, отправим при появлении интернета";
                } else {
                    r.error = e.getMessage();
                }
            } catch (JSONException e) {
                r.error = "Сайт прислал неожиданный ответ";
            } finally {
                SYNC_LOCK.unlock();
            }
            main.post(() -> callback.onDone(r));
        });
    }

    // ----------------------------------------------------- фото товара

    /**
     * Если у товара есть локальное фото, которого ещё нет на сайте, — заливаем его
     * и запоминаем путь. Повторно одно и то же фото не заливаем: флаг photoUploaded
     * сбрасывается только когда пользователь выбирает или очищает картинку в карточке.
     */
    private void uploadPhotoIfNeeded(ServerApi api, Product p) throws ServerApi.ApiException {
        if (p.photoUri.isEmpty() || p.photoUploaded) return;
        // Снимок уменьшаем перед отправкой: оригинал с телефона весит мегабайты,
        // а в карточке товара показывается размером с ладонь. Если пережать не
        // вышло — шлём как есть, фотография важнее экономии.
        String ext = ".jpg";
        byte[] data = Photos.compress(app, p.photoUri);
        if (data == null || data.length == 0) {
            data = readBytes(p.photoUri);
            ext = extOf(p.photoUri);
        }
        if (data == null || data.length == 0) return;
        String path = api.uploadPhoto(data, ext);
        if (!path.isEmpty()) {
            p.serverImage = path;
            p.photoUploaded = true;
            repo.db().productDao().update(p);
        }
    }

    /** Байты картинки по её content-Uri. */
    private byte[] readBytes(String uriStr) {
        try {
            Uri uri = Uri.parse(uriStr);
            try (InputStream in = app.getContentResolver().openInputStream(uri)) {
                if (in == null) return null;
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] tmp = new byte[8192];
                int n;
                while ((n = in.read(tmp)) > 0) buf.write(tmp, 0, n);
                return buf.toByteArray();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Расширение файла с точкой по mime-типу Uri — сервер фильтрует именно по нему. */
    private String extOf(String uriStr) {
        String ext = ".jpg";
        try {
            String mime = app.getContentResolver().getType(Uri.parse(uriStr));
            if (mime != null) {
                String e = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
                if (e != null && !e.isEmpty()) ext = "." + e;
            }
        } catch (Exception ignored) {
        }
        return ext;
    }

    // ------------------------------------------------------------ клиенты

    private void pushNewClients(ServerApi api, AppDatabase db, Result r)
            throws ServerApi.ApiException, JSONException {
        for (Client c : db.clientDao().getAll()) {
            if (c.serverId != 0) continue;
            // Без телефона сайт клиента не примет — там телефон это логин.
            if (TextUtils.isEmpty(c.phone)) continue;
            try {
                JSONObject saved = api.saveClient(clientToJson(c)).getJSONObject("client");
                c.serverId = saved.getLong("id");
                db.clientDao().update(c);
                r.clientsPushed++;
            } catch (ServerApi.ApiException e) {
                if (e.code == 0) throw e;
                // Городской номер или опечатка — сайт отвечает 400. Такой клиент
                // остаётся только в телефоне, остальной обмен продолжается.
                r.rejected++;
            }
        }
    }

    private void pullClients(ServerApi api, AppDatabase db, Result r)
            throws ServerApi.ApiException, JSONException {
        JSONArray arr = api.clients();

        Map<Long, Client> byServerId = new HashMap<>();
        Map<String, Client> byPhone = new HashMap<>();
        for (Client c : db.clientDao().getAll()) {
            if (c.serverId != 0) byServerId.put(c.serverId, c);
            if (!TextUtils.isEmpty(c.phone)) byPhone.put(digits(c.phone), c);
        }

        // Отмечаем, кого сайт вернул: клиент с serverId, которого нет в ответе,
        // удалён на сайте (админкой или с другого телефона) — убираем и у себя.
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject j = arr.getJSONObject(i);
            long serverId = j.getLong("id");
            String phone = j.optString("phone", "");

            Client c = byServerId.get(serverId);
            if (c == null && !TextUtils.isEmpty(phone)) c = byPhone.get(digits(phone));

            boolean isNew = c == null;
            if (isNew) c = new Client();
            c.name = j.optString("fullName", "");
            c.phone = phone;
            c.email = j.optString("email", "");
            c.address = j.optString("address", "");
            c.note = j.optString("note", "");
            c.archived = j.optBoolean("archived", false);
            c.blocked = j.optBoolean("blocked", false);
            String segment = j.optString("segment", "");
            if (!TextUtils.isEmpty(segment)) c.segment = segment;
            String source = j.optString("source", "");
            if (!TextUtils.isEmpty(source)) c.source = source;
            c.serverId = serverId;
            c.updatedAt = System.currentTimeMillis();

            if (isNew) {
                c.createdAt = j.optLong("createdAt", c.updatedAt);
                db.clientDao().insert(c);
            } else {
                db.clientDao().update(c);
            }
            seen.add(serverId);
            r.clientsPulled++;
        }

        // Сверка: то, что известно сайту, но не вернулось, — удалено там.
        for (Client c : new ArrayList<>(db.clientDao().getAll())) {
            if (c.serverId != 0 && !seen.contains(c.serverId)) {
                db.clientDao().delete(c);
            }
        }
    }

    private JSONObject clientToJson(Client c) throws JSONException {
        // На сайте ФИО разложено на три поля, в приложении оно одной строкой.
        String[] parts = c.name == null ? new String[0] : c.name.trim().split("\\s+");
        JSONObject j = new JSONObject();
        j.put("id", c.serverId);
        j.put("phone", c.phone);
        j.put("email", c.email);
        j.put("lastName", parts.length > 0 ? parts[0] : "");
        j.put("firstName", parts.length > 1 ? parts[1] : "");
        j.put("middleName", parts.length > 2 ? parts[2] : "");
        j.put("address", c.address);
        j.put("segment", c.segment);
        j.put("source", TextUtils.isEmpty(c.source) ? "Приложение" : c.source);
        j.put("note", c.note);
        j.put("archived", c.archived);
        j.put("blocked", c.blocked);
        return j;
    }

    /** Выгрузить одного клиента на сайт — вызывается после правки карточки в приложении. */
    public void pushClient(Client c, Callback callback) {
        repo.io().execute(() -> {
            Result r = new Result();
            SYNC_LOCK.lock();
            try {
                if (TextUtils.isEmpty(c.phone)) {
                    // Сайт без телефона клиента не примет: правка останется
                    // локальной и уйдёт при полной синхронизации, когда
                    // телефон появится.
                    r.error = "Без телефона сайт клиента не примет";
                } else {
                    ServerApi api = new ServerApi(app);
                    JSONObject saved = api.saveClient(clientToJson(c)).getJSONObject("client");
                    c.serverId = saved.getLong("id");
                    repo.db().clientDao().update(c);
                    r.clientsPushed = 1;
                }
            } catch (ServerApi.ApiException e) {
                if (e.code == 0) {
                    // Нет связи — сохраним правку в очередь, отправим при появлении интернета.
                    enqueueClient(app, c.id);
                    r.error = "Нет связи: правка сохранена, отправим при появлении интернета";
                } else {
                    r.error = e.getMessage();
                }
            } catch (JSONException e) {
                r.error = "Сайт прислал неожиданный ответ";
            } finally {
                SYNC_LOCK.unlock();
            }
            if (callback != null) main.post(() -> callback.onDone(r));
        });
    }

    /** Только цифры — чтобы «+7 999…» и «8 999…» считались одним номером. */
    private static String digits(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') sb.append(ch);
        }
        String d = sb.toString();
        if (d.length() == 11 && (d.charAt(0) == '8' || d.charAt(0) == '7')) d = d.substring(1);
        return d;
    }
}
