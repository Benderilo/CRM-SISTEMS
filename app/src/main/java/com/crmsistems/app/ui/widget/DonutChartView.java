package com.crmsistems.app.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.crmsistems.app.R;
import com.crmsistems.app.data.model.NameValue;
import com.crmsistems.app.util.Money;

import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.List;

/** Кольцевая диаграмма с легендой справа: доли сегментов клиентов, категорий расходов и т.п. */
public class DonutChartView extends View {

    private static final int[] PALETTE = {
            R.color.chart_1, R.color.chart_2, R.color.chart_3,
            R.color.chart_4, R.color.chart_5, R.color.chart_6
    };

    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    private List<NameValue> items = new ArrayList<>();
    private String centerLabel = "";
    private boolean money = false;

    public DonutChartView(Context context) {
        this(context, null);
    }

    public DonutChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.BUTT);
        textPaint.setTextSize(sp(12));
        textPaint.setColor(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        centerPaint.setTextSize(sp(15));
        centerPaint.setFakeBoldText(true);
        centerPaint.setTextAlign(Paint.Align.CENTER);
        centerPaint.setColor(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnSurface));
    }

    public void setMoney(boolean money) {
        this.money = money;
    }

    public void setItems(List<NameValue> items, String centerLabel) {
        this.items = items == null ? new ArrayList<>() : items;
        this.centerLabel = centerLabel == null ? "" : centerLabel;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();

        double total = 0;
        for (NameValue item : items) total += item.value;

        if (items.isEmpty() || total <= 0) {
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(getContext().getString(R.string.chart_no_data), width / 2f, height / 2f, textPaint);
            textPaint.setTextAlign(Paint.Align.LEFT);
            return;
        }

        float size = Math.min(height - dp(8), width * 0.45f);
        float stroke = size * 0.26f;
        arcPaint.setStrokeWidth(stroke);
        float cx = dp(8) + size / 2f;
        float cy = height / 2f;
        oval.set(cx - size / 2f + stroke / 2f, cy - size / 2f + stroke / 2f,
                cx + size / 2f - stroke / 2f, cy + size / 2f - stroke / 2f);

        float start = -90f;
        for (int i = 0; i < items.size(); i++) {
            float sweep = (float) (items.get(i).value / total * 360f);
            arcPaint.setColor(ContextCompat.getColor(getContext(), PALETTE[i % PALETTE.length]));
            canvas.drawArc(oval, start + 1, Math.max(sweep - 2, 1), false, arcPaint);
            start += sweep;
        }

        canvas.drawText(centerLabel, cx, cy + sp(5), centerPaint);

        // легенда
        float legendLeft = cx + size / 2f + dp(16);
        float available = width - legendLeft - dp(8);
        int shown = Math.min(items.size(), 6);
        float rowHeight = dp(22);
        float top = cy - (shown * rowHeight) / 2f + dp(12);
        textPaint.setTextAlign(Paint.Align.LEFT);

        for (int i = 0; i < shown; i++) {
            NameValue item = items.get(i);
            float y = top + i * rowHeight;
            dotPaint.setColor(ContextCompat.getColor(getContext(), PALETTE[i % PALETTE.length]));
            canvas.drawCircle(legendLeft + dp(4), y - sp(4), dp(4), dotPaint);

            String value = money ? Money.compact(item.value) : Money.qty(item.value);
            String text = item.safeName() + " — " + value;
            canvas.drawText(TextUtils.ellipsize(text, textPaint,
                            available - dp(16), TextUtils.TruncateAt.END).toString(),
                    legendLeft + dp(14), y, textPaint);
        }
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
