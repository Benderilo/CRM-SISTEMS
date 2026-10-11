package com.crmsistems.app.net;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.crmsistems.app.util.Prefs;

import java.util.concurrent.TimeUnit;

/**
 * Фоновая проверка заказов: телефон сам ходит на сайт и напоминает о заказах,
 * которые всё ещё в статусе «Новый», даже когда приложение закрыто.
 * <p>
 * Пятнадцать минут — минимальный период, который разрешает система; реальный
 * интервал она может растянуть, экономя батарею. Это не страшно: при открытии
 * приложения обмен всё равно происходит сразу.
 */
public class OrderCheckWorker extends Worker {

    private static final String NAME = "order-check";
    private static final long PERIOD_MINUTES = 15;

    public OrderCheckWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /**
     * Включить или выключить фоновую проверку по настройке. Вызывается на старте
     * приложения и когда переключатель меняют в настройках.
     */
    public static void apply(Context ctx) {
        WorkManager wm = WorkManager.getInstance(ctx.getApplicationContext());
        if (!Prefs.orderReminder(ctx) || !Prefs.orderAlerts(ctx)) {
            wm.cancelUniqueWork(NAME);
            return;
        }
        PeriodicWorkRequest request =
                new PeriodicWorkRequest.Builder(OrderCheckWorker.class, PERIOD_MINUTES, TimeUnit.MINUTES)
                        .setConstraints(new Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build())
                        .build();
        // KEEP: уже запущенную очередь не сбрасываем, иначе отсчёт начинался бы
        // заново при каждом запуске приложения и напоминание бы не приходило.
        wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        if (!Prefs.serverReady(ctx) || !Prefs.orderAlerts(ctx) || !Prefs.orderReminder(ctx)) {
            return Result.success();
        }
        try {
            // Тот же путь, что и при обновлении списка: отправляем накопленные
            // правки, забираем заказы, а уведомление ставит сам SyncManager.
            new SyncManager(ctx).refreshOrdersBlocking();
        } catch (Exception e) {
            // Сайт не ответил — попробуем в следующий раз, ничего не ломается.
            return Result.success();
        }
        return Result.success();
    }
}
