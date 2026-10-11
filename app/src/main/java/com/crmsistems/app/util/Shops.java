package com.crmsistems.app.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Магазины, с которыми работает приложение.
 * <p>
 * У каждого магазина своё всё: база на телефоне (товары, клиенты, продажи),
 * настройки (адрес сервера, ключ, тема) и отметки сборки. Переключение
 * магазина — это просто смена «активного» номера: файлы остальных магазинов
 * лежат рядом и не трогаются.
 * <p>
 * Список хранится отдельно от настроек магазинов — в файле {@code crm_shops}.
 */
public final class Shops {

    private static final String FILE = "crm_shops";
    private static final String KEY_LIST = "list";
    private static final String KEY_ACTIVE = "active";

    /** Один магазин в списке. */
    public static final class Shop {
        public final int id;
        public String name;

        Shop(int id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private Shops() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Все магазины по порядку добавления. Пустым список не бывает. */
    public static List<Shop> list(Context c) {
        List<Shop> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString(KEY_LIST, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Shop(o.getInt("id"), o.optString("name")));
            }
        } catch (JSONException ignored) {
            // Испорченный список — начнём заново, файлы магазинов при этом целы.
        }
        if (out.isEmpty()) {
            out.add(new Shop(1, "Мой магазин"));
            save(c, out);
        }
        return out;
    }

    private static void save(Context c, List<Shop> shops) {
        JSONArray a = new JSONArray();
        try {
            for (Shop s : shops) a.put(new JSONObject().put("id", s.id).put("name", s.name));
        } catch (JSONException ignored) {
        }
        sp(c).edit().putString(KEY_LIST, a.toString()).apply();
    }

    /** Запомненный номер активного магазина: настройки читаются часто, а список — JSON. */
    private static volatile int cachedActive;

    /** Номер магазина, с которым сейчас работаем. */
    public static int activeId(Context c) {
        int cached = cachedActive;
        if (cached != 0) return cached;
        int id = sp(c).getInt(KEY_ACTIVE, 0);
        List<Shop> shops = list(c);
        int found = shops.get(0).id;
        for (Shop s : shops) if (s.id == id) found = id;
        cachedActive = found;
        return found;
    }

    public static Shop active(Context c) {
        int id = activeId(c);
        for (Shop s : list(c)) if (s.id == id) return s;
        return list(c).get(0);
    }

    @Nullable
    public static Shop find(Context c, int id) {
        for (Shop s : list(c)) if (s.id == id) return s;
        return null;
    }

    /** Добавить магазин и вернуть его номер. Активным он не становится. */
    public static int add(Context c, String name) {
        List<Shop> shops = list(c);
        int id = 1;
        for (Shop s : shops) id = Math.max(id, s.id + 1);
        shops.add(new Shop(id, name.trim().isEmpty() ? "Магазин " + id : name.trim()));
        save(c, shops);
        return id;
    }

    public static void rename(Context c, int id, String name) {
        List<Shop> shops = list(c);
        for (Shop s : shops) if (s.id == id && !name.trim().isEmpty()) s.name = name.trim();
        save(c, shops);
    }

    /**
     * Сделать магазин активным. Открытую базу прежнего магазина закрывает
     * вызывающий (см. ShopsActivity) — здесь только отметка.
     */
    public static void setActive(Context c, int id) {
        sp(c).edit().putInt(KEY_ACTIVE, id).commit();
        cachedActive = id;
    }

    /**
     * Удалить магазин с телефона: его базу и настройки. Сервер не трогается.
     * Последний магазин удалить нельзя — возвращает false.
     */
    public static boolean remove(Context c, int id) {
        List<Shop> shops = list(c);
        if (shops.size() < 2) return false;
        shops.removeIf(s -> s.id == id);
        save(c, shops);
        Context app = c.getApplicationContext();
        app.deleteDatabase(dbName(id));
        app.deleteSharedPreferences(prefsFile(id));
        app.deleteSharedPreferences(packedFile(id));
        if (sp(c).getInt(KEY_ACTIVE, 0) == id) setActive(c, shops.get(0).id);
        return true;
    }

    // -------------------------------------------- файлы конкретного магазина

    public static String dbName(int id) {
        return "crm_" + id + ".db";
    }

    public static String prefsFile(int id) {
        return "crm_prefs_" + id;
    }

    public static String packedFile(int id) {
        return "week_packed_" + id;
    }
}
