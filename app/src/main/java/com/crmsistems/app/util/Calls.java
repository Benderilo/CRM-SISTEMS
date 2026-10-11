package com.crmsistems.app.util;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.widget.Toast;

import com.crmsistems.app.R;

/**
 * Звонок покупателю из карточки заказа или клиента.
 * <p>
 * Открываем набор номера (ACTION_DIAL), а не звоним сразу: разрешение на
 * звонки приложению не нужно, а продавец видит номер перед вызовом и может
 * передумать. Из списка заказов кнопка вызывается вне активити, поэтому
 * при необходимости добавляем флаг новой задачи.
 */
public final class Calls {

    private Calls() {
    }

    /** Набрать номер. Пустой или нерабочий номер — просто сообщение, без падения. */
    public static void dial(Context ctx, String phone) {
        String number = phone == null ? "" : phone.replaceAll("[^+0-9]", "");
        if (TextUtils.isEmpty(number)) {
            Toast.makeText(ctx, R.string.order_no_phone, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number));
        if (!(ctx instanceof android.app.Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        try {
            ctx.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            // Планшет без модуля связи — звонилки в системе может не быть.
            Toast.makeText(ctx, R.string.order_call_failed, Toast.LENGTH_SHORT).show();
        }
    }
}
