package com.crmsistems.app.util;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.StringRes;

import com.crmsistems.app.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;

/**
 * Подсказки «куда что вводить»: значок «?» у поля, по нажатию — окно
 * с объяснением, что это и где его взять.
 * <p>
 * Значок встаёт в конец поля. Если там уже что-то есть (глазок у пароля,
 * крестик очистки) — в начало, чтобы не отнимать у поля его кнопку.
 */
public final class Hints {

    private Hints() {
    }

    /** Повесить подсказку на поле ввода. Заголовок окна — подпись поля. */
    public static void field(TextInputLayout layout, @StringRes int text) {
        View.OnClickListener show = v -> show(layout.getContext(),
                layout.getHint() == null ? "" : layout.getHint().toString(),
                layout.getContext().getString(text));
        if (layout.getEndIconMode() == TextInputLayout.END_ICON_NONE) {
            layout.setEndIconMode(TextInputLayout.END_ICON_CUSTOM);
            layout.setEndIconDrawable(R.drawable.ic_help);
            layout.setEndIconContentDescription(R.string.hint_help);
            layout.setEndIconOnClickListener(show);
        } else {
            layout.setStartIconDrawable(R.drawable.ic_help);
            layout.setStartIconContentDescription(R.string.hint_help);
            layout.setStartIconOnClickListener(show);
        }
    }

    /**
     * Подсказка к заголовку, переключателю или любой подписи: справа от
     * текста появляется «?», нажатие на подпись открывает объяснение.
     */
    public static void label(TextView view, @StringRes int text) {
        view.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_help, 0);
        view.setCompoundDrawablePadding(Ui.dp(view.getContext(), 6));
        view.setOnClickListener(v -> show(v.getContext(), view.getText().toString(),
                v.getContext().getString(text)));
    }

    public static void show(Context c, CharSequence title, CharSequence text) {
        new MaterialAlertDialogBuilder(c)
                .setIcon(R.drawable.ic_help)
                .setTitle(title)
                .setMessage(text)
                .setPositiveButton(R.string.hint_understood, null)
                .show();
    }
}
