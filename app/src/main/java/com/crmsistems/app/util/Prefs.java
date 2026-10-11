package com.crmsistems.app.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

/** Настройки приложения — свои у каждого магазина (см. {@link Shops}). */
public final class Prefs {

    private static final String KEY_THEME = "theme";          // 0 — системная, 1 — светлая, 2 — тёмная
    private static final String KEY_CURRENCY = "currency";
    private static final String KEY_COMPANY = "company";
    private static final String KEY_LOW_STOCK_ALERT = "low_stock_alert";
    private static final String KEY_ORDER_ALERTS = "order_alerts";
    private static final String KEY_ORDER_REMINDER = "order_reminder";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_LAST_SYNC = "last_sync";
    private static final String KEY_WEB_SITE_URL = "web_site_url";
    private static final String KEY_WEB_ADMIN_URL = "web_admin_url";
    private static final String KEY_WEB_ADMIN_LOGIN = "web_admin_login";
    private static final String KEY_WEB_ADMIN_PASS = "web_admin_pass";

    private static final String KEY_SSH_HOST = "ssh_host";
    private static final String KEY_SSH_PORT = "ssh_port";
    private static final String KEY_SSH_USER = "ssh_user";
    private static final String KEY_SSH_HOST_KEY = "ssh_host_key";
    private static final String KEY_SLUG = "shop_slug";
    private static final String KEY_DOMAIN = "shop_domain";
    private static final String KEY_EMAIL = "shop_email";
    private static final String KEY_SETUP_OFFERED = "setup_offered";

    /** Адреса по умолчанию нет: сервер ставится из приложения или вписывается руками. */
    public static final String DEFAULT_SERVER = "";

    /** Ключ доступа к /api/v1/crm приложение получает само при установке сервера. */
    public static final String DEFAULT_API_KEY = "";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(
                Shops.prefsFile(Shops.activeId(c)), Context.MODE_PRIVATE);
    }

    public static int theme(Context c) {
        return sp(c).getInt(KEY_THEME, 0);
    }

    public static void setTheme(Context c, int mode) {
        sp(c).edit().putInt(KEY_THEME, mode).apply();
        applyTheme(mode);
    }

    public static void applyTheme(int mode) {
        switch (mode) {
            case 1:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case 2:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            default:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        }
    }

    public static String currency(Context c) {
        return sp(c).getString(KEY_CURRENCY, "₽");
    }

    public static void setCurrency(Context c, String value) {
        sp(c).edit().putString(KEY_CURRENCY, value).apply();
        Money.setCurrency(value);
    }

    public static String company(Context c) {
        return sp(c).getString(KEY_COMPANY, "");
    }

    public static void setCompany(Context c, String value) {
        sp(c).edit().putString(KEY_COMPANY, value).apply();
    }

    public static boolean lowStockAlert(Context c) {
        return sp(c).getBoolean(KEY_LOW_STOCK_ALERT, true);
    }

    public static void setLowStockAlert(Context c, boolean value) {
        sp(c).edit().putBoolean(KEY_LOW_STOCK_ALERT, value).apply();
    }

    // ------------------------------------------ сообщения о заказах с сайта

    /** Сообщать ли о заказах, пришедших с сайта. По умолчанию — да. */
    public static boolean orderAlerts(Context c) {
        return sp(c).getBoolean(KEY_ORDER_ALERTS, true);
    }

    public static void setOrderAlerts(Context c, boolean value) {
        sp(c).edit().putBoolean(KEY_ORDER_ALERTS, value).apply();
    }

    /**
     * Ходить ли на сайт в фоне раз в 15 минут, чтобы напомнить о заказах,
     * которые всё ещё в статусе «Новый». По умолчанию — да.
     */
    public static boolean orderReminder(Context c) {
        return sp(c).getBoolean(KEY_ORDER_REMINDER, true);
    }

    public static void setOrderReminder(Context c, boolean value) {
        sp(c).edit().putBoolean(KEY_ORDER_REMINDER, value).apply();
    }

    // ------------------------------------------------ связь с сайтом

    /** Адрес сайта магазина, например https://example.ru (без косой черты в конце). */
    public static String serverUrl(Context c) {
        return sp(c).getString(KEY_SERVER_URL, DEFAULT_SERVER);
    }

    public static void setServerUrl(Context c, String value) {
        String v = value == null ? "" : value.trim();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        sp(c).edit().putString(KEY_SERVER_URL, v).apply();
    }

    /** Ключ доступа к разделу /api/v1/crm — приложение получает при установке сервера (crmshop.env). */
    public static String apiKey(Context c) {
        return sp(c).getString(KEY_API_KEY, DEFAULT_API_KEY);
    }

    public static void setApiKey(Context c, String value) {
        sp(c).edit().putString(KEY_API_KEY, value == null ? "" : value.trim()).apply();
    }

    /** Настроена ли связь с сайтом. */
    public static boolean serverReady(Context c) {
        return !serverUrl(c).isEmpty() && !apiKey(c).isEmpty();
    }

    /** Когда последний раз успешно синхронизировались, 0 — ни разу. */
    public static long lastSync(Context c) {
        return sp(c).getLong(KEY_LAST_SYNC, 0L);
    }

    public static void setLastSync(Context c, long value) {
        sp(c).edit().putLong(KEY_LAST_SYNC, value).apply();
    }

    // -------------------------------------- браузер сайта и админ-панели

    /** Адрес сайта для встроенного браузера; пусто — ещё не задавали. */
    public static String webSiteUrl(Context c) {
        return sp(c).getString(KEY_WEB_SITE_URL, "");
    }

    public static void setWebSiteUrl(Context c, String value) {
        sp(c).edit().putString(KEY_WEB_SITE_URL, value == null ? "" : value.trim()).apply();
    }

    /** Адрес админ-панели для встроенного браузера; пусто — ещё не задавали. */
    public static String webAdminUrl(Context c) {
        return sp(c).getString(KEY_WEB_ADMIN_URL, "");
    }

    public static void setWebAdminUrl(Context c, String value) {
        sp(c).edit().putString(KEY_WEB_ADMIN_URL, value == null ? "" : value.trim()).apply();
    }

    /** Логин от админки — чтобы встроенный браузер входил сам. */
    public static String webAdminLogin(Context c) {
        return sp(c).getString(KEY_WEB_ADMIN_LOGIN, "");
    }

    public static void setWebAdminLogin(Context c, String value) {
        sp(c).edit().putString(KEY_WEB_ADMIN_LOGIN, value == null ? "" : value.trim()).apply();
    }

    /** Пароль от админки. Храним как есть, без обрезки пробелов. */
    public static String webAdminPass(Context c) {
        return sp(c).getString(KEY_WEB_ADMIN_PASS, "");
    }

    public static void setWebAdminPass(Context c, String value) {
        sp(c).edit().putString(KEY_WEB_ADMIN_PASS, value == null ? "" : value).apply();
    }

    /** Забыть адреса и доступы — их спросят заново при следующем открытии. */
    public static void clearWeb(Context c) {
        sp(c).edit().remove(KEY_WEB_SITE_URL).remove(KEY_WEB_ADMIN_URL)
                .remove(KEY_WEB_ADMIN_LOGIN).remove(KEY_WEB_ADMIN_PASS).apply();
    }

    // ------------------------------------------- установка сервера по SSH

    /** IP или имя VPS, куда ставили магазин. Пароль не храним никогда. */
    public static String sshHost(Context c) {
        return sp(c).getString(KEY_SSH_HOST, "");
    }

    public static int sshPort(Context c) {
        return sp(c).getInt(KEY_SSH_PORT, 22);
    }

    public static String sshUser(Context c) {
        return sp(c).getString(KEY_SSH_USER, "root");
    }

    public static void setSsh(Context c, String host, int port, String user) {
        sp(c).edit().putString(KEY_SSH_HOST, host.trim()).putInt(KEY_SSH_PORT, port)
                .putString(KEY_SSH_USER, user.trim()).apply();
    }

    /**
     * Отпечаток ключа сервера, запомненный при первом подключении. Если он
     * вдруг сменится — подключение не пойдёт: так видно подмену сервера.
     */
    public static String sshHostKey(Context c) {
        return sp(c).getString(KEY_SSH_HOST_KEY, "");
    }

    public static void setSshHostKey(Context c, String value) {
        sp(c).edit().putString(KEY_SSH_HOST_KEY, value).apply();
    }

    /** Короткое имя магазина на сервере: папка /opt/crm/ИМЯ и служба crm-ИМЯ. */
    public static String slug(Context c) {
        return sp(c).getString(KEY_SLUG, "shop");
    }

    public static String domain(Context c) {
        return sp(c).getString(KEY_DOMAIN, "");
    }

    public static String email(Context c) {
        return sp(c).getString(KEY_EMAIL, "");
    }

    public static void setInstall(Context c, String slug, String domain, String email) {
        sp(c).edit().putString(KEY_SLUG, slug.trim()).putString(KEY_DOMAIN, domain.trim())
                .putString(KEY_EMAIL, email.trim()).apply();
    }

    /** Предлагали ли уже подключить сервер при первом запуске магазина. */
    public static boolean setupOffered(Context c) {
        return sp(c).getBoolean(KEY_SETUP_OFFERED, false);
    }

    public static void setSetupOffered(Context c) {
        sp(c).edit().putBoolean(KEY_SETUP_OFFERED, true).apply();
    }
}
