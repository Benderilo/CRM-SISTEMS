package com.crmsistems.app.ui.orders;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.crmsistems.app.MainActivity;
import com.crmsistems.app.R;
import com.crmsistems.app.data.Repository;
import com.crmsistems.app.data.entity.OrderEntity;
import com.crmsistems.app.util.Money;
import com.crmsistems.app.util.Prefs;

import java.util.ArrayList;
import java.util.List;

/**
 * Сообщения о заказах с сайта, которые ещё не взяли в работу.
 * <p>
 * Уведомление показывает не «что приехало за последнюю минуту», а всё, что
 * висит в статусе «Новый». Поэтому оно само напоминает о себе при каждом обмене
 * с сайтом и в фоне (см. {@code OrderCheckWorker}) — и само пропадает, как
 * только продавец переводит заказы в любой другой статус.
 * <p>
 * Уведомление одно на всю пачку и несмахиваемое: пять заказов сразу дадут одну
 * строку со списком, а не пять отдельных звонков.
 */
public final class OrderNotifier {

    private static final String CHANNEL = "new_orders";

    /** Id фиксированный: свежее сообщение заменяет предыдущее, а не копится. */
    private static final int NOTIFICATION_ID = 1;

    private OrderNotifier() {
    }

    /** Канал уведомлений — создаём на старте, чтобы его было видно в настройках телефона. */
    public static void createChannel(Context ctx) {
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                ctx.getString(R.string.channel_orders), NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(ctx.getString(R.string.channel_orders_desc));
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    /**
     * Показать напоминание о необработанных заказах, а если таких не осталось —
     * убрать его. Разрешение на уведомления могли не дать или сообщения могли
     * выключить в настройках — тогда просто молчим, обмен с сайтом не страдает.
     */
    public static void notifyPending(Context ctx, List<ServerOrder> pending) {
        if (pending.isEmpty() || !Prefs.orderAlerts(ctx)) {
            cancel(ctx);
            return;
        }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify_order)
                .setContentIntent(openOrders(ctx))
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                // Несмахиваемое: пропадёт само, когда заказ уйдёт из статуса «Новый».
                .setOngoing(true);

        if (pending.size() == 1) {
            ServerOrder o = pending.get(0);
            b.setContentTitle(ctx.getString(R.string.new_order_title, o.number))
                    .setContentText(line(ctx, o))
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(details(ctx, o)));
        } else {
            NotificationCompat.InboxStyle style = new NotificationCompat.InboxStyle();
            for (ServerOrder o : pending) {
                style.addLine(o.number + " · " + line(ctx, o));
            }
            style.setSummaryText(ctx.getString(R.string.pending_order_hint));
            b.setContentTitle(ctx.getString(R.string.pending_orders_title, pending.size()))
                    .setContentText(ctx.getString(R.string.new_orders_sum, Money.format(total(pending))))
                    .setStyle(style)
                    .setNumber(pending.size());
        }

        NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, b.build());
    }

    /**
     * Пересобрать напоминание по локальному кэшу заказов — вызывается сразу
     * после смены статуса, чтобы сообщение исчезало не дожидаясь обмена с сайтом.
     * Блокирующий: вызывать только из фонового потока.
     */
    public static void refreshFromCache(Context ctx) {
        List<ServerOrder> pending = new ArrayList<>();
        for (OrderEntity e : Repository.get(ctx).db().orderDao().getAll()) {
            if (e.status == ServerOrder.STATUS_NEW) pending.add(ServerOrder.fromEntity(e));
        }
        notifyPending(ctx, pending);
    }

    public static void cancel(Context ctx) {
        NotificationManagerCompat.from(ctx).cancel(NOTIFICATION_ID);
    }

    /**
     * То же самое одной строкой — для всплывающей подсказки внутри приложения,
     * когда продавец и так смотрит на экран.
     */
    public static String summary(Context ctx, List<ServerOrder> orders) {
        if (orders.isEmpty()) return "";
        if (orders.size() == 1) {
            ServerOrder o = orders.get(0);
            return ctx.getString(R.string.new_order_title, o.number) + " · " + line(ctx, o);
        }
        return ctx.getString(R.string.new_orders_title, orders.size())
                + " · " + Money.format(total(orders));
    }

    /** «Иван Петров · 2 400 ₽» */
    private static String line(Context ctx, ServerOrder o) {
        String buyer = o.buyer();
        if (buyer.isEmpty()) buyer = ctx.getString(R.string.new_order_unknown_buyer);
        return buyer + " · " + Money.format(o.total);
    }

    /** Развёрнутый текст одного заказа: покупатель, сумма и что именно заказали. */
    private static String details(Context ctx, ServerOrder o) {
        StringBuilder sb = new StringBuilder(line(ctx, o));
        String items = o.itemsSummary();
        if (!items.isEmpty()) sb.append('\n').append(items);
        if (!o.address.isEmpty()) sb.append('\n').append(o.address);
        sb.append('\n').append(ctx.getString(R.string.pending_order_hint));
        return sb.toString();
    }

    private static double total(List<ServerOrder> orders) {
        double sum = 0;
        for (ServerOrder o : orders) sum += o.total;
        return sum;
    }

    /** По нажатию открываем приложение сразу на вкладке «Заказы». */
    private static PendingIntent openOrders(Context ctx) {
        Intent intent = new Intent(ctx, MainActivity.class)
                .putExtra(MainActivity.EXTRA_OPEN_ORDERS, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(ctx, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
