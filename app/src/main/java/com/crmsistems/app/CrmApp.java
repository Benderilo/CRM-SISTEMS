package com.crmsistems.app;

import android.app.Application;
import android.app.LocaleManager;
import android.os.LocaleList;

import com.crmsistems.app.net.OrderCheckWorker;
import com.crmsistems.app.ui.orders.OrderNotifier;
import com.crmsistems.app.util.Money;
import com.crmsistems.app.util.Prefs;

/** Точка входа: применяем тему и валюту до создания экранов. */
public class CrmApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        applyRussian();
        Prefs.applyTheme(Prefs.theme(this));
        Money.setCurrency(Prefs.currency(this));
        // Канал заводим сразу: так «Новые заказы» видно в настройках телефона
        // ещё до первого уведомления.
        OrderNotifier.createChannel(this);
        // Фоновая проверка заказов — если она включена в настройках.
        OrderCheckWorker.apply(this);
    }

    /**
     * Приложение целиком на русском, а язык телефона может быть любым —
     * тогда календарь и названия месяцев приезжали по-английски. Задаём
     * язык приложения один раз: система его запоминает, и если продавец
     * сам выберет другой в настройках телефона, мы это не перебьём.
     * <p>
     * Через LocaleManager напрямую, а не через AppCompatDelegate: тот в
     * onCreate приложения ещё не знает контекста и молча ничего не делает.
     */
    private void applyRussian() {
        LocaleManager locales = getSystemService(LocaleManager.class);
        if (locales != null && locales.getApplicationLocales().isEmpty()) {
            locales.setApplicationLocales(LocaleList.forLanguageTags("ru"));
        }
    }
}
