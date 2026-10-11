package com.crmsistems.app.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Загружает картинку по URL в {@link ImageView} в фоне, без внешних библиотек.
 *
 * Кеширование двухуровневое:
 *   1. Память — LruCache на 1/8 доступной VM-памяти. Быстрый доступ при прокрутке списка.
 *   2. Диск   — getCacheDir()/img_cache/. Переживает пересоздание Activity и перезапуск приложения.
 *
 * Инвалидация: не нужна явно. Сервер присваивает уникальное имя файла при каждой
 * загрузке фото (/photo/abc123.jpg). Если фото товара поменялось — изменился serverImage
 * → изменился URL → новый ключ кеша → свежая загрузка автоматически.
 *
 * Публичный API не изменился: ImageLoader.load(imageView, url).
 */
public final class ImageLoader {

    private static final String CACHE_DIR = "img_cache";
    private static final int THREADS = 3;

    private static volatile ImageLoader instance;

    private final File diskDir;
    private final LruCache<String, Bitmap> memCache;
    private final ExecutorService executor = Executors.newFixedThreadPool(THREADS);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<ImageView, String> pending =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ImageLoader(Context ctx) {
        diskDir = new File(ctx.getCacheDir(), CACHE_DIR);
        //noinspection ResultOfMethodCallIgnored
        diskDir.mkdirs();

        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024);
        memCache = new LruCache<String, Bitmap>(maxKb / 8) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    private static ImageLoader get(Context ctx) {
        if (instance == null) {
            synchronized (ImageLoader.class) {
                if (instance == null) instance = new ImageLoader(ctx.getApplicationContext());
            }
        }
        return instance;
    }

    /** Точка входа — совместима со всеми существующими вызовами. */
    public static void load(ImageView view, String url) {
        if (url == null || url.isEmpty()) return;
        get(view.getContext()).loadImage(view, url);
    }

    private void loadImage(ImageView view, String url) {
        pending.put(view, url);

        // Уровень 1: память — синхронно, без потока
        Bitmap mem = memCache.get(url);
        if (mem != null) {
            Photos.showPhoto(view);
            view.setImageBitmap(mem);
            return;
        }

        executor.execute(() -> {
            Bitmap bmp = null;

            // Уровень 2: диск
            File cached = diskFile(url);
            if (cached.exists()) {
                bmp = BitmapFactory.decodeFile(cached.getAbsolutePath());
            }

            // Уровень 3: сеть
            if (bmp == null) {
                bmp = download(url);
                if (bmp != null) saveToDisk(bmp, cached);
            }

            if (bmp == null) return;

            memCache.put(url, bmp);
            final Bitmap result = bmp;
            mainHandler.post(() -> {
                if (url.equals(pending.get(view))) {
                    Photos.showPhoto(view);
                    view.setImageBitmap(result);
                }
            });
        });
    }

    /** Имя файла на диске — последний сегмент пути URL (например, "abc123.jpg"). */
    private File diskFile(String url) {
        String name = url.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (name.length() > 120) name = name.substring(name.length() - 120);
        return new File(diskDir, name);
    }

    private Bitmap download(String urlStr) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);
            conn.connect();
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            try (InputStream in = conn.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void saveToDisk(Bitmap bmp, File file) {
        try (FileOutputStream out = new FileOutputStream(file)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out);
        } catch (IOException ignored) {}
    }
}
