package com.crmsistems.app.io;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.provider.MediaStore;

import com.crmsistems.app.data.AppDatabase;
import com.crmsistems.app.data.entity.Client;
import com.crmsistems.app.data.entity.Expense;
import com.crmsistems.app.data.entity.OrderEntity;
import com.crmsistems.app.data.entity.Product;
import com.crmsistems.app.data.entity.Sale;
import com.crmsistems.app.ui.orders.ServerOrder;
import com.crmsistems.app.util.Dates;
import com.crmsistems.app.util.Money;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Обмен данными с компьютером: выгрузка в Excel/CSV, загрузка из CSV
 * и полная резервная копия в JSON.
 * Все методы выполняются в фоновом потоке.
 */
public final class DataExporter {

    public static final int TABLE_PRODUCTS = 0;
    public static final int TABLE_CLIENTS = 1;
    public static final int TABLE_SALES = 2;
    public static final int TABLE_EXPENSES = 3;

    public static final String[] TABLE_NAMES = {"Товары", "Клиенты", "Заказы", "Расходы"};

    private static final String[] PRODUCT_HEADERS = {"ID", "Наименование", "Артикул", "Категория",
            "Ед.изм.", "Цена", "Себестоимость", "Остаток", "Мин.остаток", "Штрихкод", "Описание",
            "Архив", "Создан"};

    private static final String[] CLIENT_HEADERS = {"ID", "Имя", "Телефон", "Email", "Компания",
            "Адрес", "Сегмент", "Источник", "Метки", "Заметки", "День рождения", "Архив", "Создан"};

    /**
     * Заказы выгружаются из кэша заказов сайта: там лежат все покупки —
     * с витрины, из бота MAX и продажи, отправленные из приложения.
     * Локальная таблица продаж для этого не годится: она хранит только то,
     * что ещё не ушло на сайт, и после синхронизации пустеет.
     * <p>
     * В файл идёт вся история, включая удалённые заказы: выполненные заказы
     * стирают, чтобы не мешали в приложении, но в выгрузке они должны
     * оставаться — она и нужна, чтобы посмотреть всё, что когда-либо было.
     */
    private static final String[] ORDER_HEADERS = {"ID", "Номер", "Дата", "Покупатель", "Телефон",
            "Адрес", "Получение", "Оплата", "Статус", "Сумма", "Прибыль", "Комментарий", "Состав"};

    /** Отдельный лист: по строке на каждую позицию заказа. */
    static final String ORDER_ITEMS_SHEET = "Состав заказов";

    private static final String[] ORDER_ITEM_HEADERS = {"ID заказа", "Номер", "Дата", "Товар",
            "Ед.изм.", "Количество", "Цена", "Сумма"};

    private static final String[] EXPENSE_HEADERS = {"ID", "Дата", "Назначение", "Категория",
            "Сумма", "Комментарий"};

    private DataExporter() {
    }

    // ------------------------------------------------------------------ выгрузка

    /** Вся база одной книгой Excel: четыре листа. */
    public static void exportXlsx(Context context, Uri target) throws Exception {
        AppDatabase db = AppDatabase.get(context);
        List<XlsxWriter.Sheet> sheets = new ArrayList<>();
        for (int table = 0; table < TABLE_NAMES.length; table++) {
            sheets.add(new XlsxWriter.Sheet(TABLE_NAMES[table], headers(table), rows(db, table)));
        }
        sheets.add(new XlsxWriter.Sheet(ORDER_ITEMS_SHEET, ORDER_ITEM_HEADERS, orderItemRows(db)));
        OutputStream out = context.getContentResolver().openOutputStream(target, "wt");
        if (out == null) throw new IllegalStateException("Не удалось открыть файл");
        try {
            XlsxWriter.write(out, sheets);
        } finally {
            out.close();
        }
    }

    /** Одна таблица в CSV. */
    public static void exportCsv(Context context, Uri target, int table) throws Exception {
        AppDatabase db = AppDatabase.get(context);
        List<String[]> lines = new ArrayList<>();
        lines.add(headers(table));
        for (Object[] row : rows(db, table)) {
            String[] line = new String[row.length];
            for (int i = 0; i < row.length; i++) {
                Object value = row[i];
                line[i] = value == null ? ""
                        : (value instanceof Number ? Money.csv(((Number) value).doubleValue())
                        : String.valueOf(value));
            }
            lines.add(line);
        }
        writeText(context, target, Csv.write(lines));
    }

    /** Полная резервная копия по выбранному адресу (системный диалог SAF). */
    public static void exportJson(Context context, Uri target) throws Exception {
        writeText(context, target, backupJsonString(context));
    }

    /**
     * Резервная копия в папку «Загрузки/CRMBackups» без диалога SAF —
     * для автоматического сохранения перед очисткой базы. Разрешений не требует.
     * Возвращает человекочитаемый путь к файлу.
     */
    public static String autoBackup(Context context) throws Exception {
        String json = backupJsonString(context);
        String name = "CRM_backup_"
                + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Dates.RU)
                        .format(new java.util.Date())
                + ".json";

        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, name);
        v.put(MediaStore.Downloads.MIME_TYPE, "application/json");
        v.put(MediaStore.Downloads.RELATIVE_PATH, "Download/CRMBackups");
        ContentResolver cr = context.getContentResolver();
        Uri item = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (item == null) {
            throw new IllegalStateException("Не удалось создать файл в Загрузках");
        }
        try (OutputStream out = cr.openOutputStream(item, "wt")) {
            if (out == null) throw new IllegalStateException("Не удалось открыть файл");
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
        return "Загрузки/CRMBackups/" + name;
    }

    /** Содержимое резервной копии одним JSON-объектом. */
    private static String backupJsonString(Context context) throws Exception {
        AppDatabase db = AppDatabase.get(context);
        JSONObject root = new JSONObject();
        root.put("version", 1);
        root.put("exportedAt", System.currentTimeMillis());

        JSONArray products = new JSONArray();
        for (Product p : db.productDao().getAll()) {
            JSONObject o = new JSONObject();
            o.put("id", p.id);
            o.put("name", p.name);
            o.put("sku", p.sku);
            o.put("category", p.category);
            o.put("unit", p.unit);
            o.put("price", p.price);
            o.put("cost", p.cost);
            o.put("stock", p.stock);
            o.put("minStock", p.minStock);
            o.put("barcode", p.barcode);
            o.put("description", p.description);
            o.put("photoUri", p.photoUri);
            o.put("archived", p.archived);
            o.put("createdAt", p.createdAt);
            o.put("updatedAt", p.updatedAt);
            products.put(o);
        }
        root.put("products", products);

        JSONArray clients = new JSONArray();
        for (Client c : db.clientDao().getAll()) {
            JSONObject o = new JSONObject();
            o.put("id", c.id);
            o.put("name", c.name);
            o.put("phone", c.phone);
            o.put("email", c.email);
            o.put("company", c.company);
            o.put("address", c.address);
            o.put("segment", c.segment);
            o.put("source", c.source);
            o.put("tags", c.tags);
            o.put("note", c.note);
            o.put("photoUri", c.photoUri);
            o.put("birthday", c.birthday);
            o.put("archived", c.archived);
            o.put("createdAt", c.createdAt);
            o.put("updatedAt", c.updatedAt);
            clients.put(o);
        }
        root.put("clients", clients);

        JSONArray sales = new JSONArray();
        for (Sale s : db.saleDao().getAll()) {
            JSONObject o = new JSONObject();
            o.put("id", s.id);
            o.put("clientId", s.clientId == null ? JSONObject.NULL : s.clientId);
            o.put("productId", s.productId == null ? JSONObject.NULL : s.productId);
            o.put("clientName", s.clientName);
            o.put("productName", s.productName);
            o.put("qty", s.qty);
            o.put("unitPrice", s.unitPrice);
            o.put("unitCost", s.unitCost);
            o.put("discount", s.discount);
            o.put("date", s.date);
            o.put("status", s.status);
            o.put("note", s.note);
            o.put("createdAt", s.createdAt);
            sales.put(o);
        }
        root.put("sales", sales);

        JSONArray expenses = new JSONArray();
        for (Expense e : db.expenseDao().getAll()) {
            JSONObject o = new JSONObject();
            o.put("id", e.id);
            o.put("title", e.title);
            o.put("category", e.category);
            o.put("amount", e.amount);
            o.put("date", e.date);
            o.put("note", e.note);
            o.put("createdAt", e.createdAt);
            expenses.put(o);
        }
        root.put("expenses", expenses);

        return root.toString(2);
    }

    // ------------------------------------------------------------------- загрузка

    /** Импорт одной таблицы из CSV. Возвращает количество обработанных строк. */
    public static int importCsv(Context context, Uri source, int table) throws Exception {
        AppDatabase db = AppDatabase.get(context);
        List<String[]> rows = Csv.parse(readText(context, source));
        if (rows.size() < 2) return 0;

        String[] header = rows.get(0);
        int count = 0;
        for (int i = 1; i < rows.size(); i++) {
            String[] row = rows.get(i);
            switch (table) {
                case TABLE_CLIENTS:
                    importClient(db, header, row);
                    break;
                case TABLE_SALES:
                    importSale(db, header, row);
                    break;
                case TABLE_EXPENSES:
                    importExpense(db, header, row);
                    break;
                default:
                    importProduct(db, header, row);
            }
            count++;
        }
        return count;
    }

    /**
     * Загрузка всей базы из книги Excel — той самой, которую отдаёт
     * «Выгрузить в Excel». Листы разбираются по названиям («Товары», «Клиенты»,
     * «Заказы», «Расходы»), лишние листы пропускаются.
     * <p>
     * Строки с известным ID (или артикулом товара, телефоном клиента) обновляют
     * существующие записи, остальные добавляются. Ничего не удаляется: чтобы
     * убрать записи, есть отдельная очистка базы.
     *
     * @return сколько строк принято по каждой таблице, в порядке TABLE_NAMES
     */
    public static int[] importXlsx(Context context, Uri source) throws Exception {
        AppDatabase db = AppDatabase.get(context);
        List<XlsxReader.Sheet> sheets;
        InputStream in = context.getContentResolver().openInputStream(source);
        if (in == null) throw new IllegalStateException("Не удалось открыть файл");
        try {
            sheets = XlsxReader.read(in);
        } finally {
            in.close();
        }

        int[] counts = new int[TABLE_NAMES.length];
        boolean known = false;
        for (XlsxReader.Sheet sheet : sheets) {
            int table = tableOf(sheet.name);
            if (table < 0 || sheet.rows.size() < 2) continue;
            known = true;
            String[] header = sheet.rows.get(0);
            // Вся книга одной транзакцией на лист: наполовину загруженной таблицы не будет.
            db.runInTransaction(() -> {
                for (int i = 1; i < sheet.rows.size(); i++) {
                    String[] row = sheet.rows.get(i);
                    if (empty(row)) continue;
                    switch (table) {
                        case TABLE_CLIENTS:
                            importClient(db, header, row);
                            break;
                        case TABLE_SALES:
                            // Заказы живут на сайте: он ведёт их статусы и склад.
                            // Лист «Заказы» только для просмотра, обратно не грузим.
                            continue;
                        case TABLE_EXPENSES:
                            importExpense(db, header, row);
                            break;
                        default:
                            importProduct(db, header, row);
                    }
                    counts[table]++;
                }
            });
        }
        if (!known) {
            throw new IllegalArgumentException("В книге нет листов «"
                    + TextUtils.join("», «", TABLE_NAMES) + "»");
        }
        return counts;
    }

    /** Номер таблицы по названию листа, -1 — лист не наш. */
    private static int tableOf(String sheetName) {
        String name = sheetName == null ? "" : sheetName.trim();
        for (int i = 0; i < TABLE_NAMES.length; i++) {
            if (TABLE_NAMES[i].equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    private static boolean empty(String[] row) {
        for (String cell : row) {
            if (cell != null && !cell.trim().isEmpty()) return false;
        }
        return true;
    }

    /** Восстановление из резервной копии: текущие данные заменяются. */
    public static int importJson(Context context, Uri source) throws Exception {
        AppDatabase db = AppDatabase.get(context);
        JSONObject root = new JSONObject(readText(context, source));

        List<Product> products = new ArrayList<>();
        JSONArray productsJson = root.optJSONArray("products");
        if (productsJson != null) {
            for (int i = 0; i < productsJson.length(); i++) {
                JSONObject o = productsJson.getJSONObject(i);
                Product p = new Product();
                p.id = o.optLong("id");
                p.name = o.optString("name");
                p.sku = o.optString("sku");
                p.category = o.optString("category");
                p.unit = o.optString("unit", "шт");
                p.price = o.optDouble("price", 0);
                p.cost = o.optDouble("cost", 0);
                p.stock = o.optDouble("stock", 0);
                p.minStock = o.optDouble("minStock", 0);
                p.barcode = o.optString("barcode");
                p.description = o.optString("description");
                p.photoUri = o.optString("photoUri");
                p.archived = o.optBoolean("archived");
                p.createdAt = o.optLong("createdAt");
                p.updatedAt = o.optLong("updatedAt");
                products.add(p);
            }
        }

        List<Client> clients = new ArrayList<>();
        JSONArray clientsJson = root.optJSONArray("clients");
        if (clientsJson != null) {
            for (int i = 0; i < clientsJson.length(); i++) {
                JSONObject o = clientsJson.getJSONObject(i);
                Client c = new Client();
                c.id = o.optLong("id");
                c.name = o.optString("name");
                c.phone = o.optString("phone");
                c.email = o.optString("email");
                c.company = o.optString("company");
                c.address = o.optString("address");
                c.segment = o.optString("segment", Client.SEGMENTS[0]);
                c.source = o.optString("source");
                c.tags = o.optString("tags");
                c.note = o.optString("note");
                c.photoUri = o.optString("photoUri");
                c.birthday = o.optLong("birthday");
                c.archived = o.optBoolean("archived");
                c.createdAt = o.optLong("createdAt");
                c.updatedAt = o.optLong("updatedAt");
                clients.add(c);
            }
        }

        List<Sale> sales = new ArrayList<>();
        JSONArray salesJson = root.optJSONArray("sales");
        if (salesJson != null) {
            for (int i = 0; i < salesJson.length(); i++) {
                JSONObject o = salesJson.getJSONObject(i);
                Sale s = new Sale();
                s.id = o.optLong("id");
                s.clientId = o.isNull("clientId") ? null : o.optLong("clientId");
                s.productId = o.isNull("productId") ? null : o.optLong("productId");
                s.clientName = o.optString("clientName");
                s.productName = o.optString("productName");
                s.qty = o.optDouble("qty", 1);
                s.unitPrice = o.optDouble("unitPrice", 0);
                s.unitCost = o.optDouble("unitCost", 0);
                s.discount = o.optDouble("discount", 0);
                s.date = o.optLong("date");
                s.status = o.optInt("status", Sale.STATUS_PAID);
                s.note = o.optString("note");
                s.createdAt = o.optLong("createdAt");
                sales.add(s);
            }
        }

        List<Expense> expenses = new ArrayList<>();
        JSONArray expensesJson = root.optJSONArray("expenses");
        if (expensesJson != null) {
            for (int i = 0; i < expensesJson.length(); i++) {
                JSONObject o = expensesJson.getJSONObject(i);
                Expense e = new Expense();
                e.id = o.optLong("id");
                e.title = o.optString("title");
                e.category = o.optString("category", "Прочее");
                e.amount = o.optDouble("amount", 0);
                e.date = o.optLong("date");
                e.note = o.optString("note");
                e.createdAt = o.optLong("createdAt");
                expenses.add(e);
            }
        }

        db.runInTransaction(() -> {
            db.saleDao().clear();
            db.expenseDao().clear();
            db.productDao().clear();
            db.clientDao().clear();
            // Очередь правок ссылалась на прежние id — после восстановления они чужие.
            db.pendingChangeDao().clearLocalRefs();
            db.productDao().insertAll(products);
            db.clientDao().insertAll(clients);
            db.saleDao().insertAll(sales);
            db.expenseDao().insertAll(expenses);
        });
        return products.size() + clients.size() + sales.size() + expenses.size();
    }

    // ------------------------------------------------------------------- таблицы

    private static String[] headers(int table) {
        switch (table) {
            case TABLE_CLIENTS:
                return CLIENT_HEADERS;
            case TABLE_SALES:
                return ORDER_HEADERS;
            case TABLE_EXPENSES:
                return EXPENSE_HEADERS;
            default:
                return PRODUCT_HEADERS;
        }
    }

    private static List<Object[]> rows(AppDatabase db, int table) {
        List<Object[]> rows = new ArrayList<>();
        switch (table) {
            case TABLE_CLIENTS:
                for (Client c : db.clientDao().getAll()) {
                    rows.add(new Object[]{c.id, c.name, c.phone, c.email, c.company, c.address,
                            c.segment, c.source, c.tags, c.note,
                            c.birthday > 0 ? Dates.formatDate(c.birthday) : "",
                            c.archived ? "да" : "нет", Dates.formatDate(c.createdAt)});
                }
                break;
            case TABLE_SALES:
                for (OrderEntity e : db.orderDao().getAllWithHistory()) {
                    ServerOrder o = ServerOrder.fromEntity(e);
                    rows.add(new Object[]{o.id, o.number, Dates.formatDateTime(o.createdAt),
                            o.clientName, o.phone, o.address, o.delivery, o.payMethod,
                            o.statusName, o.total, o.profit, o.comment, itemsLine(o)});
                }
                // Продажи, ещё не улетевшие на сайт, тоже показываем — иначе
                // в выгрузке их не видно вовсе.
                for (Sale s : db.saleDao().getAll()) {
                    rows.add(new Object[]{s.id, "—", Dates.formatDate(s.date), s.clientName,
                            "", "", "", "", "Не отправлено на сайт", s.total(), s.profit(),
                            s.note, s.productName + " — " + Money.plain(s.qty)});
                }
                break;
            case TABLE_EXPENSES:
                for (Expense e : db.expenseDao().getAll()) {
                    rows.add(new Object[]{e.id, Dates.formatDate(e.date), e.title, e.category,
                            e.amount, e.note});
                }
                break;
            default:
                for (Product p : db.productDao().getAll()) {
                    rows.add(new Object[]{p.id, p.name, p.sku, p.category, p.unit, p.price, p.cost,
                            p.stock, p.minStock, p.barcode, p.description,
                            p.archived ? "да" : "нет", Dates.formatDate(p.createdAt)});
                }
        }
        return rows;
    }

    /** Состав заказа одной строкой: «Пельмени — 1,5 кг; Сало — 1 кг». */
    private static String itemsLine(ServerOrder o) {
        StringBuilder sb = new StringBuilder();
        for (ServerOrder.Item it : o.items) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(it.name).append(" — ").append(Money.plain(it.qty)).append(' ').append(it.unit);
        }
        return sb.toString();
    }

    /** Строки листа «Состав заказов»: по одной на каждую позицию. */
    private static List<Object[]> orderItemRows(AppDatabase db) {
        List<Object[]> rows = new ArrayList<>();
        for (OrderEntity e : db.orderDao().getAllWithHistory()) {
            ServerOrder o = ServerOrder.fromEntity(e);
            String date = Dates.formatDateTime(o.createdAt);
            for (ServerOrder.Item it : o.items) {
                rows.add(new Object[]{o.id, o.number, date, it.name, it.unit,
                        it.qty, it.unitPrice, it.sum});
            }
        }
        return rows;
    }

    // ------------------------------------------------------------------- импорт

    private static void importProduct(AppDatabase db, String[] header, String[] row) {
        long id = (long) number(value(header, row, "ID"));
        String sku = value(header, row, "Артикул");
        Product p = id > 0 ? db.productDao().getById(id) : null;
        if (p == null && !sku.isEmpty()) p = db.productDao().findBySku(sku);
        boolean isNew = p == null;
        if (isNew) p = new Product();

        p.name = firstNotEmpty(value(header, row, "Наименование"), p.name);
        p.sku = sku.isEmpty() ? p.sku : sku;
        p.category = value(header, row, "Категория");
        p.unit = firstNotEmpty(value(header, row, "Ед.изм."), "шт");
        p.price = number(value(header, row, "Цена"));
        p.cost = number(value(header, row, "Себестоимость"));
        p.stock = number(value(header, row, "Остаток"));
        p.minStock = number(value(header, row, "Мин.остаток"));
        p.barcode = value(header, row, "Штрихкод");
        p.description = value(header, row, "Описание");
        p.archived = flag(value(header, row, "Архив"));
        p.updatedAt = System.currentTimeMillis();
        if (p.name.isEmpty()) return;

        if (isNew) {
            p.createdAt = System.currentTimeMillis();
            db.productDao().insert(p);
        } else {
            db.productDao().update(p);
        }
    }

    private static void importClient(AppDatabase db, String[] header, String[] row) {
        long id = (long) number(value(header, row, "ID"));
        String phone = value(header, row, "Телефон");
        Client c = id > 0 ? db.clientDao().getById(id) : null;
        if (c == null && !phone.isEmpty()) c = db.clientDao().findByPhone(phone);
        boolean isNew = c == null;
        if (isNew) c = new Client();

        c.name = firstNotEmpty(value(header, row, "Имя"), c.name);
        c.phone = phone.isEmpty() ? c.phone : phone;
        c.email = value(header, row, "Email");
        c.company = value(header, row, "Компания");
        c.address = value(header, row, "Адрес");
        c.segment = firstNotEmpty(value(header, row, "Сегмент"), Client.SEGMENTS[0]);
        c.source = value(header, row, "Источник");
        c.tags = value(header, row, "Метки");
        c.note = value(header, row, "Заметки");
        c.birthday = date(value(header, row, "День рождения"));
        c.archived = flag(value(header, row, "Архив"));
        c.updatedAt = System.currentTimeMillis();
        if (c.name.isEmpty()) return;

        if (isNew) {
            c.createdAt = System.currentTimeMillis();
            db.clientDao().insert(c);
        } else {
            db.clientDao().update(c);
        }
    }

    private static void importSale(AppDatabase db, String[] header, String[] row) {
        long id = (long) number(value(header, row, "ID"));
        Sale s = id > 0 ? db.saleDao().getById(id) : null;
        boolean isNew = s == null;
        if (isNew) s = new Sale();

        s.date = date(value(header, row, "Дата"));
        if (s.date == 0) s.date = System.currentTimeMillis();
        s.clientName = value(header, row, "Клиент");
        s.productName = value(header, row, "Товар");

        long clientId = (long) number(value(header, row, "ID клиента"));
        if (clientId > 0 && db.clientDao().getById(clientId) != null) s.clientId = clientId;
        long productId = (long) number(value(header, row, "ID товара"));
        if (productId > 0 && db.productDao().getById(productId) != null) s.productId = productId;

        s.qty = Math.max(number(value(header, row, "Количество")), 0);
        s.unitPrice = number(value(header, row, "Цена"));
        s.unitCost = number(value(header, row, "Себестоимость"));
        s.discount = number(value(header, row, "Скидка"));
        s.note = value(header, row, "Комментарий");
        s.status = status(value(header, row, "Статус"));
        if (s.qty <= 0) s.qty = 1;

        if (isNew) {
            s.createdAt = System.currentTimeMillis();
            db.saleDao().insert(s);
        } else {
            db.saleDao().update(s);
        }
    }

    private static void importExpense(AppDatabase db, String[] header, String[] row) {
        long id = (long) number(value(header, row, "ID"));
        Expense e = id > 0 ? db.expenseDao().getById(id) : null;
        boolean isNew = e == null;
        if (isNew) e = new Expense();

        e.date = date(value(header, row, "Дата"));
        if (e.date == 0) e.date = System.currentTimeMillis();
        e.title = value(header, row, "Назначение");
        e.category = firstNotEmpty(value(header, row, "Категория"), "Прочее");
        e.amount = number(value(header, row, "Сумма"));
        e.note = value(header, row, "Комментарий");
        if (e.amount <= 0) return;

        if (isNew) {
            e.createdAt = System.currentTimeMillis();
            db.expenseDao().insert(e);
        } else {
            db.expenseDao().update(e);
        }
    }

    private static int status(String text) {
        for (int i = 0; i < Sale.STATUS_NAMES.length; i++) {
            if (Sale.STATUS_NAMES[i].equalsIgnoreCase(text.trim())) return i;
        }
        return Sale.STATUS_PAID;
    }

    // ------------------------------------------------------------------ утилиты

    private static String value(String[] header, String[] row, String column) {
        for (int i = 0; i < header.length && i < row.length; i++) {
            if (header[i] != null && header[i].trim().equalsIgnoreCase(column)) {
                return row[i] == null ? "" : row[i].trim();
            }
        }
        return "";
    }

    private static double number(String text) {
        return Money.parse(text);
    }

    /**
     * Дата из ячейки. Текст вида «17.08.2026» разбирает {@link Dates}, а Excel
     * при правке часто превращает такую ячейку в число — количество дней
     * с 30.12.1899. Диапазон 1970…2100 отсекает случайные числа вроде суммы.
     */
    private static long date(String text) {
        long parsed = Dates.parse(text);
        if (parsed != 0) return parsed;
        double serial = Money.parse(text);
        if (serial >= 25569 && serial <= 73415) {
            return Math.round((serial - 25569) * 86400000.0);
        }
        return 0;
    }

    private static boolean flag(String text) {
        String value = text.trim().toLowerCase(Locale.ROOT);
        return value.equals("да") || value.equals("1") || value.equals("true") || value.equals("yes");
    }

    private static String firstNotEmpty(String first, String fallback) {
        return first == null || first.isEmpty() ? (fallback == null ? "" : fallback) : first;
    }

    private static void writeText(Context context, Uri target, String content) throws Exception {
        OutputStream out = context.getContentResolver().openOutputStream(target, "wt");
        if (out == null) throw new IllegalStateException("Не удалось открыть файл");
        try {
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } finally {
            out.close();
        }
    }

    private static String readText(Context context, Uri source) throws Exception {
        InputStream in = context.getContentResolver().openInputStream(source);
        if (in == null) throw new IllegalStateException("Не удалось открыть файл");
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) buffer.write(chunk, 0, read);
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }
}
