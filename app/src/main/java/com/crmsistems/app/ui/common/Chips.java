package com.crmsistems.app.ui.common;

import android.content.Context;
import android.content.res.ColorStateList;

import androidx.core.graphics.ColorUtils;

import com.google.android.material.chip.Chip;

/**
 * Покраска чипа-фильтра парой «фон + текст» из палитры статусов заказа
 * или сегментов клиента. Обычное состояние — бледная заливка цвета карточки,
 * выбранное — сплошная заливка цветом текста, как плашка статуса на карточке.
 * <p>
 * Галочка у выбранного чипа не нужна: он и так заливается целиком. Обводка
 * тонируется тем же цветом вполсилы, чтобы белые и серые чипы не терялись
 * на фоне экрана.
 */
public final class Chips {

    private Chips() {
    }

    /** Покрасить чип: {@code bg} — цвет фона карточки, {@code fg} — цвет текста к нему. */
    public static void paint(Chip chip, int bg, int fg) {
        Context ctx = chip.getContext();
        chip.setCheckedIcon(null);
        chip.setChipStrokeWidth(Math.round(ctx.getResources().getDisplayMetrics().density));
        chip.setChipStrokeColor(ColorStateList.valueOf(
                ColorUtils.setAlphaComponent(fg, 0x80)));
        int[][] states = {{android.R.attr.state_checked}, {}};
        chip.setChipBackgroundColor(new ColorStateList(states, new int[]{fg, bg}));
        chip.setTextColor(new ColorStateList(states, new int[]{bg, fg}));
    }
}
