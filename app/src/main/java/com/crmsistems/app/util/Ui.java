package com.crmsistems.app.util;

import android.content.Context;
import android.view.View;
import android.widget.EditText;

import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.snackbar.Snackbar;

/** Мелкие помощники для экранов. */
public final class Ui {

    private Ui() {
    }

    /**
     * Приложение рисуется под системными панелями (Android 15+ включает это принудительно),
     * поэтому отступы добавляем сами: верхний — шапке, нижний — содержимому.
     */
    public static void applyInsets(View root, @Nullable View top, @Nullable View bottom) {
        final int[] topBase = base(top);
        final int[] bottomBase = base(bottom);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            if (top != null) {
                top.setPadding(topBase[0] + bars.left, topBase[1] + bars.top,
                        topBase[2] + bars.right, topBase[3]);
            }
            if (bottom != null) {
                bottom.setPadding(bottomBase[0] + bars.left, bottomBase[1],
                        bottomBase[2] + bars.right, bottomBase[3] + bars.bottom);
            }
            return windowInsets;
        });
    }

    private static int[] base(@Nullable View view) {
        if (view == null) return new int[]{0, 0, 0, 0};
        return new int[]{view.getPaddingLeft(), view.getPaddingTop(),
                view.getPaddingRight(), view.getPaddingBottom()};
    }

    public static String text(EditText field) {
        return field.getText() == null ? "" : field.getText().toString().trim();
    }

    public static double number(EditText field) {
        return Money.parse(text(field));
    }

    public static void snack(View anchor, String message) {
        Snackbar.make(anchor, message, Snackbar.LENGTH_LONG).show();
    }

    public static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
