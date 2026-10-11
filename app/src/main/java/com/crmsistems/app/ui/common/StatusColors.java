package com.crmsistems.app.ui.common;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.crmsistems.app.R;
import com.crmsistems.app.ui.orders.ServerOrder;

/**
 * Цвета карточки по статусу заказа: заливка фона и цвет текста к ней.
 * <p>
 * Шкала одна на всё приложение — та же, что у сайта (Новый, Принят,
 * Собирается, В доставке, Выполнен, Отменён). Продажи, заведённые в
 * приложении, приводятся к ней в {@code SaleRow.colorStatus()}, чтобы
 * одинаковые состояния красились одинаково в любом списке.
 * <p>
 * Сами цвета берутся из ресурсов и потому разные в светлой и тёмной теме
 * (values/colors.xml и values-night/colors.xml).
 */
public final class StatusColors {

    private static final int[] BACKGROUND = {
            R.color.status_new_bg,
            R.color.status_accepted_bg,
            R.color.status_packing_bg,
            R.color.status_delivery_bg,
            R.color.status_done_bg,
            R.color.status_canceled_bg,
    };

    private static final int[] FOREGROUND = {
            R.color.status_new_fg,
            R.color.status_accepted_fg,
            R.color.status_packing_fg,
            R.color.status_delivery_fg,
            R.color.status_done_fg,
            R.color.status_canceled_fg,
    };

    private StatusColors() {
    }

    /** Заливка карточки. */
    public static int background(Context ctx, int status) {
        return ContextCompat.getColor(ctx, BACKGROUND[clamp(status)]);
    }

    /** Цвет текста и ярлыка на этой заливке. */
    public static int foreground(Context ctx, int status) {
        return ContextCompat.getColor(ctx, FOREGROUND[clamp(status)]);
    }

    /**
     * Ярлык статуса — сплошная плашка цвета статуса с текстом цвета заливки
     * карточки. В светлой теме плашка тёмная, в тёмной — светлая, так что
     * контраст есть в обеих, и статус виден издалека, не вчитываясь.
     */
    public static void paintPill(TextView label, int status) {
        Context ctx = label.getContext();
        float dp = ctx.getResources().getDisplayMetrics().density;
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(foreground(ctx, status));
        pill.setCornerRadius(12 * dp);
        label.setBackground(pill);
        label.setTextColor(background(ctx, status));
        int h = Math.round(10 * dp), v = Math.round(3 * dp);
        label.setPadding(h, v, h, v);
    }

    /** Незнакомый статус (сайт добавил новый) красим как «Новый», а не падаем. */
    private static int clamp(int status) {
        return status >= 0 && status < BACKGROUND.length ? status : ServerOrder.STATUS_NEW;
    }
}
