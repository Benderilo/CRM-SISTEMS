package com.crmsistems.app.ui.splash;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.crmsistems.app.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Неоновая вывеска: буквы «разгораются» как люминесцентные лампы —
 * несколько нервных вспышек, а потом ровное свечение с лёгким дрожанием.
 *
 * Свечение каждой буквы рисуется один раз в отдельный Bitmap (BlurMaskFilter работает
 * только на программном холсте), а в кадре мы лишь меняем прозрачность —
 * поэтому анимация идёт плавно даже на слабом телефоне.
 */
public class NeonSignView extends View {

    /** Три строки вывески — название открытого магазина (см. {@link #splitName}). */
    private final String[] LINES = {"", "", ""};
    private static final float[] SIZE_FACTOR = {1f, 1f, 1f};
    private static final long[] LINE_DELAY = {120, 470, 820};

    /** Одна буква вывески со своим расписанием «розжига». */
    private static class Glyph {
        String text;
        Bitmap glow;
        float x;          // левый край буквы
        float baseline;
        float drawX;      // куда класть bitmap со свечением
        float drawY;
        long[] toggles;   // моменты включения/выключения
        long settle;      // когда лампа разгорелась окончательно
        int seed;
        int line;
    }

    private final Paint glassPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint tubePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final List<Glyph> glyphs = new ArrayList<>();
    private final float[] lineSize = new float[LINES.length];
    private final int[] lineColor = new int[LINES.length];
    private final float[] lineWidth = new float[LINES.length];
    private final float[] lineStart = new float[LINES.length];
    private final float[] lineBaseline = new float[LINES.length];

    private long startTime;
    private float tubeTop;
    private float tubeBottom;

    public NeonSignView(Context context) {
        this(context, null);
    }

    public NeonSignView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        String[] lines = splitName(com.crmsistems.app.util.Shops.active(context).name);
        System.arraycopy(lines, 0, LINES, 0, LINES.length);
        Typeface typeface = Typeface.create("sans-serif-black", Typeface.BOLD);
        textPaint.setTypeface(typeface);
        textPaint.setStyle(Paint.Style.FILL);

        glassPaint.setTypeface(typeface);
        glassPaint.setStyle(Paint.Style.FILL);

        tubePaint.setStyle(Paint.Style.STROKE);
        tubePaint.setStrokeCap(Paint.Cap.ROUND);

        // все буквы одного тёмно-синего цвета
        int blue = ContextCompat.getColor(context, R.color.neon_blue);
        lineColor[0] = blue;
        lineColor[1] = blue;
        lineColor[2] = blue;

        setBackgroundColor(ContextCompat.getColor(context, R.color.neon_background));
    }

    /**
     * Название магазина — на три строки вывески, по возможности поровну.
     * Одно слово встаёт в середину, длинное название делится по словам.
     */
    static String[] splitName(String name) {
        String[] words = name.trim().toUpperCase().split("\\s+");
        if (words.length == 0 || words[0].isEmpty()) words = new String[]{"CRM"};
        if (words.length == 1) return new String[]{"", words[0], ""};
        if (words.length == 2) return new String[]{words[0], words[1], ""};
        int total = 0;
        for (String w : words) total += w.length() + 1;
        String[] out = {"", "", ""};
        int line = 0;
        int used = 0;
        for (int i = 0; i < words.length; i++) {
            int left = words.length - i;
            // Переходим на следующую строку, когда эта набрала свою треть,
            // но так, чтобы на оставшиеся строки хватило слов.
            if (line < 2 && !out[line].isEmpty()
                    && (used >= total * (line + 1) / 3 || left <= 2 - line)) {
                line++;
            }
            out[line] = out[line].isEmpty() ? words[i] : out[line] + " " + words[i];
            used += words[i].length() + 1;
        }
        return out;
    }

    /** Все лампы уже разгорелись? */
    public boolean isReady() {
        return elapsed() > 2000;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w == 0 || h == 0) return;
        layoutSign(w, h);
        startTime = System.currentTimeMillis();
        postInvalidateOnAnimation();
    }

    // ------------------------------------------------------------------ вёрстка

    private void layoutSign(int width, int height) {
        recycle();

        float available = width - dp(48);
        float base = Float.MAX_VALUE;
        for (int i = 0; i < LINES.length; i++) {
            textPaint.setTextSize(100f);
            float widthAt100 = textPaint.measureText(LINES[i]);
            float maxSize = available / widthAt100 * 100f;
            base = Math.min(base, maxSize / SIZE_FACTOR[i]);
        }
        base = Math.min(base, height * 0.19f);

        float totalHeight = 0;
        for (int i = 0; i < LINES.length; i++) {
            lineSize[i] = base * SIZE_FACTOR[i];
            totalHeight += lineSize[i] * 1.16f;
        }
        totalHeight += dp(18);

        float y = (height - totalHeight) / 2f;
        tubeTop = y - dp(26);

        Random random = new Random(20260811L);
        for (int i = 0; i < LINES.length; i++) {
            textPaint.setTextSize(lineSize[i]);
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float baseline = y - fm.ascent;

            lineWidth[i] = textPaint.measureText(LINES[i]);
            lineStart[i] = (width - lineWidth[i]) / 2f;
            lineBaseline[i] = baseline;

            float x = lineStart[i];
            for (int c = 0; c < LINES[i].length(); c++) {
                String ch = String.valueOf(LINES[i].charAt(c));
                float charWidth = textPaint.measureText(ch);
                if (!ch.equals(" ")) {
                    glyphs.add(createGlyph(ch, i, x, baseline, fm, random));
                }
                x += charWidth;
            }

            y += lineSize[i] * 1.16f;
            if (i == 1) y += dp(18);
        }
        tubeBottom = y + dp(10);
    }

    private Glyph createGlyph(String ch, int line, float x, float baseline,
                              Paint.FontMetrics fm, Random random) {
        Glyph glyph = new Glyph();
        glyph.text = ch;
        glyph.line = line;
        glyph.x = x;
        glyph.baseline = baseline;
        glyph.seed = random.nextInt(1000);

        float size = lineSize[line];
        int pad = (int) (size * 0.55f);
        int bw = (int) Math.ceil(textPaint.measureText(ch)) + pad * 2;
        int bh = (int) Math.ceil(fm.descent - fm.ascent) + pad * 2;

        glyph.glow = renderGlow(ch, size, lineColor[line], pad, bw, bh, -fm.ascent);
        glyph.drawX = x - pad;
        glyph.drawY = baseline + fm.ascent - pad;

        // расписание розжига: несколько вспышек, затем ровный свет
        List<Long> toggles = new ArrayList<>();
        long time = LINE_DELAY[line] + random.nextInt(320);
        int flashes = 1 + random.nextInt(3);
        for (int i = 0; i < flashes; i++) {
            toggles.add(time);
            time += 25 + random.nextInt(70);
            toggles.add(time);
            time += 45 + random.nextInt(150);
        }
        toggles.add(time);
        glyph.settle = time;

        glyph.toggles = new long[toggles.size()];
        for (int i = 0; i < toggles.size(); i++) glyph.toggles[i] = toggles.get(i);
        return glyph;
    }

    /** Рисуем свечение буквы в отдельный bitmap: три размытых слоя + белое ядро. */
    private Bitmap renderGlow(String ch, float size, int color, int pad, int bw, int bh, float ascent) {
        Bitmap bitmap = Bitmap.createBitmap(Math.max(bw, 1), Math.max(bh, 1), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(textPaint.getTypeface());
        paint.setTextSize(size);
        paint.setStyle(Paint.Style.FILL);

        float baseline = pad + ascent;

        paint.setColor(color);
        paint.setAlpha(90);
        paint.setMaskFilter(new BlurMaskFilter(Math.max(size * 0.34f, 1f), BlurMaskFilter.Blur.NORMAL));
        canvas.drawText(ch, pad, baseline, paint);

        paint.setAlpha(170);
        paint.setMaskFilter(new BlurMaskFilter(Math.max(size * 0.15f, 1f), BlurMaskFilter.Blur.NORMAL));
        canvas.drawText(ch, pad, baseline, paint);

        paint.setAlpha(255);
        paint.setMaskFilter(new BlurMaskFilter(Math.max(size * 0.05f, 1f), BlurMaskFilter.Blur.NORMAL));
        canvas.drawText(ch, pad, baseline, paint);

        // раскалённое ядро трубки
        paint.setMaskFilter(null);
        paint.setColor(ColorUtils.blendARGB(color, Color.WHITE, 0.55f));
        canvas.drawText(ch, pad, baseline, paint);
        return bitmap;
    }

    // ------------------------------------------------------------------ отрисовка

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (glyphs.isEmpty()) return;

        long t = elapsed();
        drawBackglow(canvas, t);

        for (Glyph glyph : glyphs) {
            float brightness = brightness(glyph, t);

            // погасшая трубка видна как тёмное стекло
            glassPaint.setTextSize(lineSize[glyph.line]);
            glassPaint.setColor(ColorUtils.setAlphaComponent(
                    ColorUtils.blendARGB(lineColor[glyph.line], Color.BLACK, 0.72f), 190));
            canvas.drawText(glyph.text, glyph.x, glyph.baseline, glassPaint);

            if (brightness > 0.02f && glyph.glow != null) {
                bitmapPaint.setAlpha((int) (Math.min(brightness, 1f) * 255));
                canvas.drawBitmap(glyph.glow, glyph.drawX, glyph.drawY, bitmapPaint);
            }
        }

        drawTubes(canvas, t);
        postInvalidateOnAnimation();
    }

    /** Мягкое зарево вывески на стене. */
    private void drawBackglow(Canvas canvas, long t) {
        float progress = Math.min(1f, t / 1800f);
        if (progress <= 0) return;
        float radius = Math.max(getWidth(), getHeight()) * 0.65f;
        int center = ColorUtils.setAlphaComponent(lineColor[1], (int) (46 * progress));
        backgroundPaint.setShader(new RadialGradient(getWidth() / 2f, getHeight() / 2f, radius,
                center, Color.TRANSPARENT, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, getWidth(), getHeight(), backgroundPaint);
        backgroundPaint.setShader(null);
    }

    /** Две неоновые линейки сверху и снизу — «рамка» вывески. */
    private void drawTubes(Canvas canvas, long t) {
        float on = t < 1500 ? 0f : Math.min(1f, (t - 1500) / 400f);
        if (on <= 0) return;

        float left = getWidth() * 0.12f;
        float right = getWidth() * 0.88f;
        int color = lineColor[0];

        for (int pass = 0; pass < 4; pass++) {
            float width = dp(2) + pass * dp(3.5f);
            int alpha = (int) ((pass == 0 ? 255 : 60 - pass * 12) * on);
            if (alpha <= 0) continue;
            tubePaint.setStrokeWidth(width);
            tubePaint.setColor(ColorUtils.setAlphaComponent(
                    pass == 0 ? ColorUtils.blendARGB(color, Color.WHITE, 0.6f) : color, alpha));
            canvas.drawLine(left, tubeTop, right, tubeTop, tubePaint);
            canvas.drawLine(left, tubeBottom, right, tubeBottom, tubePaint);
        }

        // светящиеся «пробки» на концах трубок
        tubePaint.setStrokeWidth(dp(2));
        tubePaint.setColor(ColorUtils.setAlphaComponent(color, (int) (200 * on)));
        drawCap(canvas, left, tubeTop);
        drawCap(canvas, right, tubeTop);
        drawCap(canvas, left, tubeBottom);
        drawCap(canvas, right, tubeBottom);
    }

    private void drawCap(Canvas canvas, float x, float y) {
        rect.set(x - dp(6), y - dp(6), x + dp(6), y + dp(6));
        canvas.drawArc(rect, 0, 360, false, tubePaint);
    }

    /** Яркость буквы в момент t: сначала вспышки, потом живое дрожание лампы. */
    private float brightness(Glyph glyph, long t) {
        if (t < glyph.settle) {
            int count = 0;
            for (long toggle : glyph.toggles) {
                if (t >= toggle) count++;
            }
            return count % 2 == 1 ? 1f : 0f;
        }
        float pulse = 0.93f + 0.07f * (float) Math.sin((t + glyph.seed * 37L) / 210.0);
        if (((t / 55 + glyph.seed) % 149) < 1) pulse *= 0.5f;
        return pulse;
    }

    private long elapsed() {
        return startTime == 0 ? 0 : System.currentTimeMillis() - startTime;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        recycle();
    }

    private void recycle() {
        for (Glyph glyph : glyphs) {
            if (glyph.glow != null && !glyph.glow.isRecycled()) glyph.glow.recycle();
        }
        glyphs.clear();
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }
}
