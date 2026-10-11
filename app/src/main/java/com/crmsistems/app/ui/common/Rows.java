package com.crmsistems.app.ui.common;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.crmsistems.app.util.Ui;

/**
 * Строки состава — «название · количество × цена ... сумма». Такими показан
 * состав заказа в карточке заказа и состав продажи в карточке клиента:
 * места одни и те же, поэтому и вид один.
 */
public final class Rows {

    private Rows() {
    }

    /** Строка «название — сумма». {@code bold} — итоговая строка. */
    public static View line(Context ctx, String title, @Nullable String subtitle,
                            String value, boolean bold) {
        LinearLayout line = new LinearLayout(ctx);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setPadding(0, Ui.dp(ctx, 6), 0, Ui.dp(ctx, 6));

        LinearLayout left = new LinearLayout(ctx);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(ctx);
        name.setText(title);
        name.setTextAppearance(bold
                ? com.google.android.material.R.style.TextAppearance_Material3_TitleMedium
                : com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        left.addView(name);

        if (subtitle != null) {
            TextView sub = new TextView(ctx);
            sub.setText(subtitle);
            sub.setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            sub.setAlpha(0.7f);
            left.addView(sub);
        }
        line.addView(left);

        TextView sum = new TextView(ctx);
        sum.setText(value);
        sum.setGravity(Gravity.END);
        sum.setTextAppearance(bold
                ? com.google.android.material.R.style.TextAppearance_Material3_TitleMedium
                : com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        line.addView(sum);

        return line;
    }

    /** Подсветка нажатия для строки, по которой можно постучать. */
    public static int selectableBackground(Context ctx) {
        TypedValue out = new TypedValue();
        ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, out, true);
        return out.resourceId;
    }
}
