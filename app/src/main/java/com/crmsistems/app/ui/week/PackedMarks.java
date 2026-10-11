package com.crmsistems.app.ui.week;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.crmsistems.app.data.Repository;
import com.crmsistems.app.net.ServerApi;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Галочки «положили» в сборке: какой товар какому покупателю уже собран.
 * <p>
 * Общие для всех телефонов: живут на сайте ({@code /api/v1/crm/packed}),
 * а здесь лежит копия, чтобы галочка ставилась сразу и без связи. Каждая
 * правка запоминается как неотправленная и уходит на сайт при первой
 * возможности; пока не ушла, она главнее того, что прислал сайт, — иначе
 * галочка, поставленная без связи, мигнула бы и пропала.
 * <p>
 * Ключ отметки заканчивается номерами заказов покупателя с этим товаром.
 * Если он дозакажет тот же товар новым заказом, ключ станет другим и
 * собирать придётся заново. Отметки выполненных и отменённых заказов сайт
 * вычищает сам.
 * <p>
 * Экземпляр один на приложение: экран сборки и заказ покупателя должны видеть
 * одни и те же галочки, а не каждый свою копию.
 */
final class PackedMarks {

    private static final String KEY = "marks";
    private static final String KEY_SET = "pending_set";
    private static final String KEY_UNSET = "pending_unset";

    private static PackedMarks instance;

    private final Context app;
    private final SharedPreferences sp;
    private final Set<String> marks;
    /** Неотправленные правки: ключ → поставили (true) или сняли (false). */
    private final Map<String, Boolean> pending = new HashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    static synchronized PackedMarks get(Context ctx) {
        if (instance == null) instance = new PackedMarks(ctx.getApplicationContext());
        return instance;
    }

    private PackedMarks(Context app) {
        this.app = app;
        sp = app.getSharedPreferences(com.crmsistems.app.util.Shops.packedFile(
                com.crmsistems.app.util.Shops.activeId(app)), Context.MODE_PRIVATE);
        marks = new HashSet<>(sp.getStringSet(KEY, new HashSet<>()));
        for (String m : sp.getStringSet(KEY_SET, new HashSet<>())) pending.put(m, true);
        for (String m : sp.getStringSet(KEY_UNSET, new HashSet<>())) pending.put(m, false);
    }

    synchronized boolean has(String mark) {
        return marks.contains(mark);
    }

    /**
     * Поставить или снять галочки — одну или весь заказ разом. На сайт
     * уходят в фоне сами; {@code onChanged} вызывается в главном потоке,
     * если сайт прислал чужие отметки.
     */
    void set(List<String> list, boolean packed, Runnable onChanged) {
        synchronized (this) {
            for (String mark : list) {
                if (packed) marks.add(mark);
                else marks.remove(mark);
                pending.put(mark, packed);
            }
            save();
        }
        syncAsync(onChanged);
    }

    /**
     * Отправить свои правки и забрать чужие — в фоне. {@code onChanged}
     * вызывается в главном потоке, только если отметки изменились.
     */
    void syncAsync(Runnable onChanged) {
        Repository.get(app).io().execute(() -> {
            if (syncBlocking() && onChanged != null) main.post(onChanged);
        });
    }

    /** То же в текущем фоновом потоке. Возвращает, изменились ли отметки. */
    boolean syncBlocking() {
        ServerApi api = new ServerApi(app);
        if (!api.configured()) return false;

        Map<String, Boolean> sent;
        synchronized (this) {
            sent = new HashMap<>(pending);
        }
        JSONArray server;
        try {
            if (sent.isEmpty()) {
                server = api.packed();
            } else {
                List<String> on = new ArrayList<>(), off = new ArrayList<>();
                for (Map.Entry<String, Boolean> e : sent.entrySet()) {
                    (e.getValue() ? on : off).add(e.getKey());
                }
                server = api.savePacked(on, off);
            }
        } catch (ServerApi.ApiException e) {
            // Нет связи или сайт ещё не умеет — правки ждут следующего раза.
            return false;
        }

        synchronized (this) {
            // Ушедшие правки больше не ждут — если их не успели перебить новыми.
            for (Map.Entry<String, Boolean> e : sent.entrySet()) {
                if (e.getValue().equals(pending.get(e.getKey()))) pending.remove(e.getKey());
            }
            Set<String> fresh = new HashSet<>();
            for (int i = 0; i < server.length(); i++) fresh.add(server.optString(i));
            for (Map.Entry<String, Boolean> e : pending.entrySet()) {
                if (e.getValue()) fresh.add(e.getKey());
                else fresh.remove(e.getKey());
            }
            boolean changed = !fresh.equals(marks);
            marks.clear();
            marks.addAll(fresh);
            save();
            return changed;
        }
    }

    private void save() {
        Set<String> on = new HashSet<>(), off = new HashSet<>();
        for (Map.Entry<String, Boolean> e : pending.entrySet()) {
            (e.getValue() ? on : off).add(e.getKey());
        }
        sp.edit()
                .putStringSet(KEY, new HashSet<>(marks))
                .putStringSet(KEY_SET, on)
                .putStringSet(KEY_UNSET, off)
                .apply();
    }
}
