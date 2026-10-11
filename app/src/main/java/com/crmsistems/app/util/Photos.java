package com.crmsistems.app.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.text.TextUtils;
import android.widget.ImageView;

import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.graphics.drawable.DrawableCompat;

import com.crmsistems.app.R;
import com.google.android.material.color.MaterialColors;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Подготовка фотографии товара к отправке на сайт.
 * <p>
 * Снимок с телефона весит 3–8 МБ, а в карточке товара он показывается размером
 * с ладонь. Отправлять оригинал — это лишний мобильный трафик у продавца и
 * лишние гигабайты на сервере, где диска всего 20 ГБ. Уменьшаем до 1600 пикселей
 * по длинной стороне и пережимаем в JPEG: выходит в 10–20 раз легче, а на глаз
 * разницы нет.
 * <p>
 * Поворот берём из EXIF и применяем сами: при пережатии EXIF теряется, и снятое
 * вертикально фото уехало бы на сайт лежащим на боку.
 */
public final class Photos {

    /** Длинная сторона готовой картинки. */
    private static final int MAX_SIDE = 1600;

    /** Качество JPEG: 85 — предел, за которым разница ещё видна на глаз. */
    private static final int QUALITY = 85;

    private Photos() {
    }

    // ------------------------------------------------------------ миниатюры

    /**
     * Миниатюра товара в списке: своё фото, фото с сайта или иконка-заглушка.
     * <p>
     * Отступы у самой картинки не трогаем никогда. Раньше заглушка ставила
     * 12dp, и если фото приходило в такой квадратик, скругление 14dp
     * оказывалось ровно половиной оставшихся 28dp — фотография превращалась
     * в кружок. Теперь картинка всегда во всю плитку, а иконка-заглушка
     * рисуется с внутренним полем, поэтому вид один и тот же независимо
     * от того, успела ли загрузиться фотография.
     */
    public static void thumb(ImageView view, String localUri, String serverImage, String base) {
        placeholder(view);
        if (!TextUtils.isEmpty(localUri)) {
            try {
                showPhoto(view);
                view.setImageURI(Uri.parse(localUri));
                return;
            } catch (Exception e) {
                placeholder(view);
            }
        }
        if (!TextUtils.isEmpty(serverImage) && !TextUtils.isEmpty(base)) {
            ImageLoader.load(view, base + serverImage);
        }
    }

    /** Готовим вид под фотографию: во всю плитку, без подкраски. */
    public static void showPhoto(ImageView view) {
        view.setPadding(0, 0, 0, 0);
        view.setImageTintList(null);
    }

    /** Иконка товара вместо фотографии: в цвет темы и с полем от краёв. */
    public static void placeholder(ImageView view) {
        view.setPadding(0, 0, 0, 0);
        view.setImageTintList(null);

        Drawable icon = AppCompatResources.getDrawable(view.getContext(), R.drawable.ic_products);
        if (icon == null) return;
        icon = DrawableCompat.wrap(icon.mutate());
        DrawableCompat.setTint(icon, MaterialColors.getColor(view,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        int inset = Math.round(11 * view.getResources().getDisplayMetrics().density);
        view.setImageDrawable(new InsetDrawable(icon, inset));
    }

    /**
     * Уменьшенная копия снимка в JPEG. Возвращает {@code null}, если картинку
     * не удалось прочитать или разобрать — тогда вызывающий отправляет оригинал,
     * это лучше, чем остаться без фотографии вовсе.
     */
    public static byte[] compress(Context ctx, String uriStr) {
        Uri uri = Uri.parse(uriStr);

        // Сначала только размеры: полноразмерный снимок с современного телефона
        // в памяти занимает под сотню мегабайт, декодировать его целиком незачем.
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = open(ctx, uri)) {
            if (in == null) return null;
            BitmapFactory.decodeStream(in, null, bounds);
        } catch (Exception e) {
            return null;
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);

        Bitmap bitmap;
        try (InputStream in = open(ctx, uri)) {
            if (in == null) return null;
            bitmap = BitmapFactory.decodeStream(in, null, opts);
        } catch (Exception e) {
            return null;
        }
        if (bitmap == null) return null;

        try {
            bitmap = rotate(bitmap, orientation(ctx, uri));
            bitmap = scale(bitmap);
            bitmap = onWhite(bitmap);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) return null;
            return out.toByteArray();
        } catch (Exception | OutOfMemoryError e) {
            return null;
        } finally {
            bitmap.recycle();
        }
    }

    private static InputStream open(Context ctx, Uri uri) throws Exception {
        return ctx.getContentResolver().openInputStream(uri);
    }

    /**
     * Во сколько раз уменьшать при чтении. Берём степень двойки — только их
     * понимает декодер — и с запасом: сначала грубо уменьшаем чтением, потом
     * точно доводим до нужного размера, так картинка не мылится.
     */
    private static int sampleSize(int width, int height) {
        int longSide = Math.max(width, height);
        int sample = 1;
        while (longSide / (sample * 2) >= MAX_SIDE) sample *= 2;
        return sample;
    }

    /** Точное уменьшение до MAX_SIDE. Картинку меньше этого не растягиваем. */
    private static Bitmap scale(Bitmap src) {
        int longSide = Math.max(src.getWidth(), src.getHeight());
        if (longSide <= MAX_SIDE) return src;
        float k = (float) MAX_SIDE / longSide;
        int w = Math.max(1, Math.round(src.getWidth() * k));
        int h = Math.max(1, Math.round(src.getHeight() * k));
        Bitmap scaled = Bitmap.createScaledBitmap(src, w, h, true);
        if (scaled != src) src.recycle();
        return scaled;
    }

    /** Угол поворота из EXIF, 0 — если тега нет или файл не читается. */
    private static int orientation(Context ctx, Uri uri) {
        try (InputStream in = open(ctx, uri)) {
            if (in == null) return 0;
            int tag = new ExifInterface(in).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (tag) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    return 90;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    return 180;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    return 270;
                default:
                    return 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private static Bitmap rotate(Bitmap src, int degrees) {
        if (degrees == 0) return src;
        Matrix m = new Matrix();
        m.postRotate(degrees);
        Bitmap turned = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        if (turned != src) src.recycle();
        return turned;
    }

    /**
     * JPEG прозрачности не знает и делает её чёрной, поэтому картинку с альфой
     * (обычно это PNG-логотип) подкладываем на белый фон.
     */
    private static Bitmap onWhite(Bitmap src) {
        if (!src.hasAlpha()) return src;
        Bitmap flat = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(flat);
        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(src, 0, 0, null);
        src.recycle();
        return flat;
    }
}
