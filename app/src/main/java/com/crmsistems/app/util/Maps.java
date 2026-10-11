package com.crmsistems.app.util;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.widget.Toast;

import com.crmsistems.app.R;

/**
 * Адрес покупателя в Яндекс Навигаторе или Картах — чтобы быстро доехать с заказом.
 * <p>
 * Сначала пробуем Навигатор: поиск по адресу (map_search), Навигатор ставит
 * точку клиента, дальше одна кнопка «Поехали». Нет Навигатора — то же самое
 * в приложении Карт: Яндекс ставит точку клиента
 * и показывает карточку адреса, а маршрут от текущего места — одна кнопка
 * «Маршрут», дальше «Поехали», и ведут голосом, как навигатор.
 * <p>
 * Сразу маршрутом ни одно из приложений не открыть: build_route_on_map в
 * Навигаторе и rtext в Картах понимают только координаты (Навигатор ещё и
 * требует подписи ключом от Яндекса), адрес текстом молча выбрасывает. А переводить адрес в
 * координаты на телефоне нельзя — системный геокодер (Google) отправил
 * «Наталино ул. Карла Маркса 4» в одноимённое село на Украине. Поиск Яндекса
 * начинает с региона, где находится телефон, и находит правильный адрес.
 * <p>
 * Нет приложения Карт — открываем карты в браузере; веб-версия понимает
 * адрес текстом и в маршруте, так что там маршрут строится сразу.
 */
public final class Maps {

    private Maps() {
    }

    /** Показать адрес на карте. Пустой адрес — просто сообщение, без падения. */
    public static void show(Context ctx, String address) {
        String query = address == null ? "" : address.trim();
        if (TextUtils.isEmpty(query)) {
            Toast.makeText(ctx, R.string.order_no_address, Toast.LENGTH_SHORT).show();
            return;
        }
        String encoded = Uri.encode(query);
        if (open(ctx, "yandexnavi://map_search?text=" + encoded)) return;
        if (open(ctx, "yandexmaps://maps.yandex.ru/?text=" + encoded)) return;
        // Пустая точка до «~» — «моё местоположение».
        if (open(ctx, "https://yandex.ru/maps/?rtext=~" + encoded + "&rtt=auto")) return;
        Toast.makeText(ctx, R.string.order_map_failed, Toast.LENGTH_SHORT).show();
    }

    private static boolean open(Context ctx, String uri) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
        if (!(ctx instanceof android.app.Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        try {
            ctx.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        }
    }
}
