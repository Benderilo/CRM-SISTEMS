package com.crmsistems.app.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.crmsistems.app.R;
import com.crmsistems.app.util.Money;

import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.List;

/**
 * Столбчатая диаграмма на одну или две серии (например «доход / расход» по месяцам).
 * Написана без внешних библиотек, чтобы проект собирался без интернета.
 */
public class BarChartView extends View {

    private static final int GRID_LINES = 4;

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint legendPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private List<String> labels = new ArrayList<>();
    private List<Double> first = new ArrayList<>();
    private List<Double> second = new ArrayList<>();

    private String legendFirst = "";
    private String legendSecond = "";
    private int colorFirst;
    private int colorSecond;

    public BarChartView(Context context) {
        this(context, null);
    }

    public BarChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        colorFirst = ContextCompat.getColor(context, R.color.income);
        colorSecond = ContextCompat.getColor(context, R.color.expense);

        int onSurface = MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant);
        textPaint.setColor(onSurface);
        textPaint.setTextSize(sp(10));
        legendPaint.setTextSize(sp(11));
        legendPaint.setColor(onSurface);
        gridPaint.setColor(onSurface);
        gridPaint.setAlpha(45);
        gridPaint.setStrokeWidth(dp(1));
    }

    public void setColors(int first, int second) {
        this.colorFirst = first;
        this.colorSecond = second;
        invalidate();
    }

    public void setLegend(String first, String second) {
        this.legendFirst = first == null ? "" : first;
        this.legendSecond = second == null ? "" : second;
        invalidate();
    }

    public void setData(List<String> labels, List<Double> first, List<Double> second) {
        this.labels = labels == null ? new ArrayList<>() : labels;
        this.first = first == null ? new ArrayList<>() : first;
        this.second = second == null ? new ArrayList<>() : second;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();
        if (labels.isEmpty() || width == 0) {
            drawEmpty(canvas, width, height);
            return;
        }

        float legendHeight = (legendFirst.isEmpty() ? 0 : dp(18));
        float left = dp(44);
        float right = width - dp(8);
        float top = legendHeight + dp(8);
        float bottom = height - dp(20);

        double max = 0;
        for (Double v : first) max = Math.max(max, v == null ? 0 : v);
        for (Double v : second) max = Math.max(max, v == null ? 0 : v);
        max = niceCeil(max);

        // сетка и подписи значений
        textPaint.setTextAlign(Paint.Align.RIGHT);
        for (int i = 0; i <= GRID_LINES; i++) {
            float y = bottom - (bottom - top) * i / GRID_LINES;
            canvas.drawLine(left, y, right, y, gridPaint);
            canvas.drawText(Money.compact(max * i / GRID_LINES), left - dp(4), y + sp(3.5f), textPaint);
        }

        boolean paired = !second.isEmpty();
        int count = labels.size();
        float slot = (right - left) / count;
        float barWidth = Math.min(
                Math.max(dp(4), slot * (paired ? 0.28f : 0.5f)),
                dp(paired ? 32 : 56));
        float gap = paired ? dp(2) : 0;
        float radius = Math.min(barWidth / 2.5f, dp(7));

        textPaint.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < count; i++) {
            float center = left + slot * i + slot / 2f;
            double v1 = i < first.size() && first.get(i) != null ? first.get(i) : 0;
            double v2 = i < second.size() && second.get(i) != null ? second.get(i) : 0;

            if (paired) {
                drawBar(canvas, center - barWidth - gap / 2, center - gap / 2, v1, max, top, bottom, radius, colorFirst);
                drawBar(canvas, center + gap / 2, center + barWidth + gap / 2, v2, max, top, bottom, radius, colorSecond);
            } else {
                drawBar(canvas, center - barWidth / 2, center + barWidth / 2, v1, max, top, bottom, radius, colorFirst);
            }
            canvas.drawText(labels.get(i), center, height - dp(5), textPaint);
        }

        drawLegend(canvas, left, legendHeight);
    }

    /** Округляем максимум вверх до «красивого» числа, чтобы подписи оси были ровными. */
    private double niceCeil(double value) {
        if (value <= 0) return 1;
        double exponent = Math.floor(Math.log10(value));
        double base = Math.pow(10, exponent);
        double normalized = value / base;
        double nice = normalized <= 1 ? 1
                : normalized <= 2 ? 2
                : normalized <= 2.5 ? 2.5
                : normalized <= 5 ? 5 : 10;
        return nice * base;
    }

    private void drawBar(Canvas canvas, float x1, float x2, double value, double max,
                         float top, float bottom, float radius, int color) {
        if (value <= 0) return;
        float barTop = (float) (bottom - (bottom - top) * (value / max));
        rect.set(x1, barTop, x2, bottom);
        barPaint.setColor(color);
        canvas.drawRoundRect(rect, radius, radius, barPaint);
    }

    private void drawLegend(Canvas canvas, float left, float legendHeight) {
        if (legendFirst.isEmpty() || legendHeight == 0) return;
        float y = dp(11);
        float x = left;
        barPaint.setColor(colorFirst);
        canvas.drawCircle(x, y - sp(3.5f), dp(4), barPaint);
        legendPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(legendFirst, x + dp(8), y, legendPaint);

        if (!legendSecond.isEmpty()) {
            float x2 = x + dp(12) + legendPaint.measureText(legendFirst) + dp(16);
            barPaint.setColor(colorSecond);
            canvas.drawCircle(x2, y - sp(3.5f), dp(4), barPaint);
            canvas.drawText(legendSecond, x2 + dp(8), y, legendPaint);
        }
    }

    private void drawEmpty(Canvas canvas, int width, int height) {
        legendPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(getContext().getString(R.string.chart_no_data),
                width / 2f, height / 2f, legendPaint);
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    private float sp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                getResources().getDisplayMetrics());
    }
}
