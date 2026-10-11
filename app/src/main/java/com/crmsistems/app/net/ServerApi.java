package com.crmsistems.app.net;

import android.content.Context;
import android.text.TextUtils;

import com.crmsistems.app.util.Prefs;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

/**
 * Клиент к сайту магазина: раздел /api/v1/crm.
 * <p>
 * Написан на {@link HttpURLConnection} — новых зависимостей не тянем.
 * Все методы блокирующие, поэтому вызывать их можно только из фонового потока
 * (см. {@link SyncManager}, он всё делает через пул потоков репозитория).
 */
public final class ServerApi {

    /** Ошибка обмена с сервером с человеческим текстом для показа пользователю. */
    public static class ApiException extends Exception {
        public final int code;

        public ApiException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private static final int CONNECT_TIMEOUT = 15_000;
    private static final int READ_TIMEOUT = 30_000;

    private final String base;
    private final String apiKey;

    public ServerApi(Context context) {
        this(Prefs.serverUrl(context), Prefs.apiKey(context));
    }

    public ServerApi(String base, String apiKey) {
        this.base = base == null ? "" : base.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public boolean configured() {
        return !TextUtils.isEmpty(base) && !TextUtils.isEmpty(apiKey);
    }

    // ------------------------------------------------------------ запросы

    /** Проверка адреса и ключа: сколько чего лежит на сайте. */
    public JSONObject ping() throws ApiException {
        return get("/api/v1/crm/ping");
    }

    public JSONArray products() throws ApiException {
        return array(get("/api/v1/crm/products"), "products");
    }

    public JSONArray clients() throws ApiException {
        return array(get("/api/v1/crm/clients"), "clients");
    }

    public JSONArray orders() throws ApiException {
        return array(get("/api/v1/crm/orders"), "orders");
    }

    public JSONObject stats() throws ApiException {
        return get("/api/v1/crm/stats");
    }

    /** Галочки «положили» из «Сборки» — общие для всех телефонов. */
    public JSONArray packed() throws ApiException {
        return array(get("/api/v1/crm/packed"), "marks");
    }

    /** Поставить и снять галочки «положили»; в ответ — все отметки после правки. */
    public JSONArray savePacked(java.util.Collection<String> set,
                                java.util.Collection<String> unset) throws ApiException {
        try {
            JSONObject body = new JSONObject();
            body.put("set", new JSONArray(set));
            body.put("unset", new JSONArray(unset));
            return array(post("/api/v1/crm/packed", body), "marks");
        } catch (JSONException e) {
            throw new ApiException(0, "Не удалось собрать запрос");
        }
    }

    /**
     * Удалить один заказ на сайте. Сервер перед удалением сам сохраняет копию
     * заказа рядом с базой и возвращает на склад товар, если он был списан.
     */
    public JSONObject deleteOrder(long serverId) throws ApiException {
        try {
            JSONObject body = new JSONObject();
            body.put("id", serverId);
            return post("/api/v1/crm/orders/delete", body);
        } catch (JSONException e) {
            throw new ApiException(0, "Не удалось собрать запрос");
        }
    }

    /** Создать (id = 0) или обновить товар на сайте. */
    public JSONObject saveProduct(JSONObject product) throws ApiException {
        return post("/api/v1/crm/products", product);
    }

    /** Создать или обновить карточку клиента на сайте. */
    public JSONObject saveClient(JSONObject client) throws ApiException {
        return post("/api/v1/crm/clients/save", client);
    }

    /** Удалить клиента на сайте (заказы остаются, связь с клиентом обнуляется). */
    public JSONObject deleteClient(long serverId) throws ApiException {
        try {
            JSONObject body = new JSONObject();
            body.put("id", serverId);
            return post("/api/v1/crm/clients/delete", body);
        } catch (JSONException e) {
            throw new ApiException(0, "Не удалось собрать запрос");
        }
    }

    /**
     * Задать покупателю новый пароль от личного кабинета — он свой забыл.
     * Пустой пароль означает «пусть сайт придумает сам»: готовый вернётся
     * в ответе, его и диктуют покупателю. Открытые сессии закрываются.
     */
    public JSONObject setClientPassword(long serverId, String password) throws ApiException {
        try {
            JSONObject body = new JSONObject();
            body.put("id", serverId);
            body.put("password", password == null ? "" : password);
            return post("/api/v1/crm/clients/password", body);
        } catch (JSONException e) {
            throw new ApiException(0, "Не удалось собрать запрос");
        }
    }

    /** Отправить продажу из приложения — на сайте она становится заказом. */
    public JSONObject createOrder(JSONObject order) throws ApiException {
        return post("/api/v1/crm/orders", order);
    }

    /** Сменить статус заказа. */
    public JSONObject setOrderStatus(long orderId, int status) throws ApiException {
        try {
            JSONObject body = new JSONObject();
            body.put("id", orderId);
            body.put("status", status);
            return post("/api/v1/crm/orders/status", body);
        } catch (JSONException e) {
            throw new ApiException(0, "Не удалось собрать запрос");
        }
    }

    /**
     * Свести заказы одного покупателя в один. Сайт оставит самый ранний из них
     * (его номер покупатель уже знает), перенесёт туда весь состав и вернёт
     * общий заказ. Склад при этом не двигается: товар просто переехал.
     */
    public JSONObject mergeOrders(java.util.List<Long> ids) throws ApiException {
        try {
            JSONArray arr = new JSONArray();
            for (Long id : ids) arr.put((long) id);
            JSONObject body = new JSONObject();
            body.put("ids", arr);
            return post("/api/v1/crm/orders/merge", body);
        } catch (JSONException e) {
            throw new ApiException(0, "Не удалось собрать запрос");
        }
    }

    /**
     * Изменить количество в позиции заказа; qty = 0 убирает позицию.
     * Сервер сам пересчитает сумму заказа и вернёт разницу на склад,
     * а в ответе отдаст заказ целиком.
     */
    public JSONObject setOrderItemQty(long orderId, long itemId, double qty) throws ApiException {
        try {
            JSONObject body = new JSONObject();
            body.put("orderId", orderId);
            body.put("itemId", itemId);
            body.put("qty", qty);
            return post("/api/v1/crm/orders/item", body);
        } catch (JSONException e) {
            throw new ApiException(0, "Не удалось собрать запрос");
        }
    }

    /**
     * Дописать товар в уже оформленный заказ — покупатель решил взять ещё.
     * productId — id товара на сайте (Product.serverId).
     */
    /** Привязать заявку к карточке клиента: телефон и адрес возьмутся оттуда. */
    public JSONObject setOrderClient(long orderId, long clientId) throws ApiException {
        try {
            JSONObject body = new JSONObject();
            body.put("orderId", orderId);
            body.put("clientId", clientId);
            return post("/api/v1/crm/orders/client", body);
        } catch (JSONException e) {
            throw new ApiException(0, "Не удалось собрать запрос");
        }
    }

    public JSONObject addOrderItem(long orderId, long productId, double qty) throws ApiException {
        try {
            JSONObject body = new JSONObject();
            body.put("orderId", orderId);
            body.put("productId", productId);
            body.put("qty", qty);
            return post("/api/v1/crm/orders/item/add", body);
        } catch (JSONException e) {
            throw new ApiException(0, "Не удалось собрать запрос");
        }
    }

    /**
     * Удалить заказы на сайте: scope = «done» (выполненные) или «all» (все).
     * Сервер сохраняет копию удалённого рядом с базой и склад не двигает.
     */
    public JSONObject purgeOrders(String scope) throws ApiException {
        try {
            JSONObject body = new JSONObject();
            body.put("scope", scope);
            return post("/api/v1/crm/orders/purge", body);
        } catch (JSONException e) {
            throw new ApiException(0, "Не удалось собрать запрос");
        }
    }

    /** Настройки бота MAX: включён ли, маска токена, группа, состояние опроса. */
    public JSONObject bot() throws ApiException {
        return get("/api/v1/crm/bot");
    }

    /** Сохранить настройки бота. Пустой token означает «оставить прежний». */
    public JSONObject saveBot(JSONObject settings) throws ApiException {
        return post("/api/v1/crm/bot", settings);
    }

    /** Спросить у MAX, чей это токен, — кнопка «Проверить связь». */
    public JSONObject botCheck(String token) throws ApiException {
        try {
            JSONObject body = new JSONObject();
            body.put("token", token == null ? "" : token);
            return post("/api/v1/crm/bot/check", body);
        } catch (JSONException e) {
            throw new ApiException(0, "Не удалось собрать запрос");
        }
    }

    /** Поднять опрос заново — кнопка «Перезапустить сейчас». */
    public JSONObject botRestart() throws ApiException {
        return post("/api/v1/crm/bot/restart", new JSONObject());
    }

    /** Чаты, где состоит бот, — из них выбирается группа для новостей. */
    public JSONArray botChats() throws ApiException {
        return array(get("/api/v1/crm/bot/chats"), "chats");
    }

    /** Прислать в группу карточку с кнопками — её потом закрепляют. */
    public JSONObject botMenu() throws ApiException {
        return post("/api/v1/crm/bot/menu", new JSONObject());
    }

    /** Отправить новость в группу MAX. */
    public JSONObject botAnnounce(long newsId, String text) throws ApiException {
        try {
            JSONObject body = new JSONObject();
            body.put("newsId", newsId);
            body.put("text", text == null ? "" : text);
            return post("/api/v1/crm/bot/announce", body);
        } catch (JSONException e) {
            throw new ApiException(0, "Не удалось собрать запрос");
        }
    }

    /** Кто сейчас администратор сайта. */
    public JSONObject adminInfo() throws ApiException {
        return get("/api/v1/crm/admin");
    }

    /** Тексты витрины (контакты, верхняя полоска) и все новости, включая скрытые. */
    public JSONObject site() throws ApiException {
        return get("/api/v1/crm/site");
    }

    /** Сохранить тексты витрины. */
    public JSONObject saveSite(JSONObject site) throws ApiException {
        return post("/api/v1/crm/site", site);
    }

    /** Создать (id = 0) или изменить новость для бегущей строки. */
    public JSONObject saveNews(JSONObject news) throws ApiException {
        return post("/api/v1/crm/news", news);
    }

    public JSONObject deleteNews(long id) throws ApiException {
        try {
            JSONObject body = new JSONObject();
            body.put("id", id);
            return post("/api/v1/crm/news/delete", body);
        } catch (JSONException e) {
            throw new ApiException(0, "Не удалось собрать запрос");
        }
    }

    /**
     * Загрузить фотографию товара на сайт (multipart, поле «photo»).
     * Возвращает путь вида /photo/xxx.jpg, который потом кладётся в поле image товара.
     *
     * @param data байты картинки
     * @param ext  расширение с точкой, например «.jpg» — по нему сервер выбирает тип
     */
    public String uploadPhoto(byte[] data, String ext) throws ApiException {
        if (!configured()) {
            throw new ApiException(0, "Не заданы адрес сайта и ключ доступа");
        }
        if (ext == null || ext.isEmpty()) ext = ".jpg";
        String mime = ext.equalsIgnoreCase(".png") ? "image/png"
                : ext.equalsIgnoreCase(".webp") ? "image/webp"
                : ext.equalsIgnoreCase(".gif") ? "image/gif"
                : "image/jpeg";
        String boundary = "----crm" + System.currentTimeMillis();

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeUtf8(body, "--" + boundary + "\r\n");
        writeUtf8(body, "Content-Disposition: form-data; name=\"photo\"; filename=\"photo"
                + ext + "\"\r\n");
        writeUtf8(body, "Content-Type: " + mime + "\r\n\r\n");
        body.write(data, 0, data.length);
        writeUtf8(body, "\r\n--" + boundary + "--\r\n");
        byte[] payload = body.toByteArray();

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(base + "/api/v1/crm/products/photo").openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestProperty("X-API-Key", apiKey);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setFixedLengthStreamingMode(payload.length);
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }

            int code = conn.getResponseCode();
            String text = read(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            JSONObject json;
            try {
                json = new JSONObject(text);
            } catch (JSONException e) {
                throw new ApiException(code, "Сервер ответил не по правилам (код " + code + ")");
            }
            if (code >= 400 || !json.optBoolean("ok", false)) {
                throw new ApiException(code, json.optString("error", "Ошибка сервера, код " + code));
            }
            return json.optString("image", "");
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw new ApiException(0, offlineMessage(e));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void writeUtf8(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.write(b, 0, b.length);
    }

    /** Сменить логин и пароль от панели управления сайтом. */
    public JSONObject changeAdminPassword(String login, String oldPassword, String newPassword)
            throws ApiException {
        try {
            JSONObject body = new JSONObject();
            body.put("login", login);
            body.put("oldPassword", oldPassword);
            body.put("newPassword", newPassword);
            return post("/api/v1/crm/admin/password", body);
        } catch (JSONException e) {
            throw new ApiException(0, "Не удалось собрать запрос");
        }
    }

    // ------------------------------------------------------------ механика

    private JSONObject get(String path) throws ApiException {
        return request("GET", path, null);
    }

    private JSONObject post(String path, JSONObject body) throws ApiException {
        return request("POST", path, body);
    }

    private JSONObject request(String method, String path, JSONObject body) throws ApiException {
        if (!configured()) {
            throw new ApiException(0, "Не заданы адрес сайта и ключ доступа");
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(base + path).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestProperty("X-API-Key", apiKey);
            conn.setRequestProperty("Accept", "application/json");

            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(data.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(data);
                }
            }

            int code = conn.getResponseCode();
            String text = read(code >= 400 ? conn.getErrorStream() : conn.getInputStream());

            JSONObject json;
            try {
                json = new JSONObject(text);
            } catch (JSONException e) {
                // Обычно это страница ошибки от nginx, а не наш JSON.
                throw new ApiException(code, "Сервер ответил не по правилам (код " + code + ")");
            }
            if (code >= 400 || !json.optBoolean("ok", false)) {
                throw new ApiException(code, json.optString("error", "Ошибка сервера, код " + code));
            }
            return json;

        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw new ApiException(0, offlineMessage(e));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Человеческий текст вместо системного. Без интернета сюда прилетало
     * «Unable to resolve host … No address associated with hostname» — продавцу
     * это ничего не говорит, а происходит чаще всего именно оно. Код 0 при этом
     * не меняется: по нему приложение и решает, что правку надо отложить.
     */
    private static String offlineMessage(IOException e) {
        if (e instanceof UnknownHostException) {
            return "Нет связи: сайт не найден. Проверьте интернет и адрес сайта в настройках";
        }
        if (e instanceof SocketTimeoutException) {
            return "Сайт не ответил вовремя — похоже, связь слабая";
        }
        if (e instanceof ConnectException) {
            return "Не удалось соединиться с сайтом — проверьте интернет";
        }
        String detail = e.getMessage();
        return detail == null || detail.isEmpty()
                ? "Нет связи с сайтом"
                : "Нет связи с сайтом: " + detail;
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private static JSONArray array(JSONObject json, String field) throws ApiException {
        JSONArray a = json.optJSONArray(field);
        if (a == null) throw new ApiException(0, "В ответе нет поля «" + field + "»");
        return a;
    }

    /** Полный адрес картинки товара: сервер отдаёт путь вида /photo/xxx.jpg. */
    public String imageUrl(String path) {
        if (TextUtils.isEmpty(path)) return "";
        if (path.startsWith("http")) return path;
        return base + path;
    }
}
