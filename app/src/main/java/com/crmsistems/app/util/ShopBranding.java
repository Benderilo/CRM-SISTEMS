package com.crmsistems.app.util;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import com.crmsistems.app.MainActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;

/**
 * Логотип магазина и ярлык магазина на рабочем столе.
 * <p>
 * Логотип — любая картинка из галереи: показывается в шапке меню и становится
 * значком ярлыка. Ярлык открывает приложение сразу на своём магазине, поэтому
 * у каждого магазина может быть свой значок со своим названием.
 */
public final class ShopBranding {

    /** Номер магазина в интенте ярлыка. */
    public static final String EXTRA_SHOP_ID = "shop_id";

    private static final int SIZE = 512;

    private ShopBranding() {
    }

    public static File logoFile(Context c, int shopId) {
        return new File(c.getFilesDir(), "shop_logo_" + shopId + ".png");
    }

    @Nullable
    public static Bitmap logo(Context c, int shopId) {
        File f = logoFile(c, shopId);
        return f.exists() ? BitmapFactory.decodeFile(f.getPath()) : null;
    }

    /** Сохранить картинку из галереи: обрезаем по центру в квадрат 512×512. */
    public static void saveLogo(Context c, int shopId, Uri uri) throws IOException {
        Bitmap src = ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.getContentResolver(), uri),
                (decoder, info, source) -> {
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    int min = Math.min(info.getSize().getWidth(), info.getSize().getHeight());
                    if (min > SIZE * 2) decoder.setTargetSampleSize(min / (SIZE * 2));
                });
        int side = Math.min(src.getWidth(), src.getHeight());
        int left = (src.getWidth() - side) / 2;
        int top = (src.getHeight() - side) / 2;
        Bitmap out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(src, new Rect(left, top, left + side, top + side),
                new Rect(0, 0, SIZE, SIZE), new Paint(Paint.FILTER_BITMAP_FLAG));
        try (FileOutputStream os = new FileOutputStream(logoFile(c, shopId))) {
            out.compress(Bitmap.CompressFormat.PNG, 100, os);
        }
        refreshShortcut(c, shopId);
    }

    public static void removeLogo(Context c, int shopId) {
        //noinspection ResultOfMethodCallIgnored
        logoFile(c, shopId).delete();
        refreshShortcut(c, shopId);
    }

    // ---------------------------------------------------------------- ярлыки

    public static boolean canPin(Context c) {
        return ShortcutManagerCompat.isRequestPinShortcutSupported(c);
    }

    /** Попросить лаунчер положить ярлык магазина на рабочий стол. */
    public static boolean pinShortcut(Context c, Shops.Shop shop) {
        return ShortcutManagerCompat.requestPinShortcut(c, shortcut(c, shop), null);
    }

    /** Название или логотип поменялись — обновить уже лежащий ярлык. */
    public static void refreshShortcut(Context c, int shopId) {
        Shops.Shop shop = Shops.find(c, shopId);
        if (shop == null) return;
        try {
            ShortcutManagerCompat.updateShortcuts(c, Collections.singletonList(shortcut(c, shop)));
        } catch (RuntimeException ignored) {
            // Ярлыка нет или лаунчер не умеет — не страшно.
        }
    }

    /** Магазин удалён — ярлык гаснет с понятной подписью. */
    public static void disableShortcut(Context c, int shopId) {
        try {
            ShortcutManagerCompat.disableShortcuts(c, Collections.singletonList(id(shopId)),
                    "Магазин удалён из приложения");
        } catch (RuntimeException ignored) {
        }
        //noinspection ResultOfMethodCallIgnored
        logoFile(c, shopId).delete();
    }

    private static String id(int shopId) {
        return "shop_" + shopId;
    }

    private static ShortcutInfoCompat shortcut(Context c, Shops.Shop shop) {
        Intent intent = new Intent(c, MainActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(EXTRA_SHOP_ID, shop.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Bitmap logo = logo(c, shop.id);
        IconCompat icon = logo != null
                ? IconCompat.createWithAdaptiveBitmap(adaptive(logo))
                : IconCompat.createWithResource(c, AppIcons.current(c).launcher);
        return new ShortcutInfoCompat.Builder(c, id(shop.id))
                .setShortLabel(shop.name)
                .setLongLabel(shop.name)
                .setIcon(icon)
                .setIntent(intent)
                .build();
    }

    /**
     * Картинка для значка: лаунчер обрезает края адаптивной иконки (кругом,
     * квадратом — как решит), поэтому кладём логотип в середину на белый фон,
     * чтобы надпись на логотипе не срезало.
     */
    private static Bitmap adaptive(Bitmap logo) {
        int size = 432; // 108dp при 4x — как у системных значков
        int inner = Math.round(size * 0.70f);
        int off = (size - inner) / 2;
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(logo, null, new Rect(off, off, off + inner, off + inner),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        return out;
    }
}
