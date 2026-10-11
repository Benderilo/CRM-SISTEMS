package com.crmsistems.app.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
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

/**
 * Горизонтальный рейтинг: «топ товаров», «топ клиентов», «структура расходов».
 * Высота подбирается автоматически под количество строк.
 */
public class RankBarsView extends View {

    private static final int[] PALETTE = {
            R.color.chart_1, R.color.chart_2, R.color.chart_3,
            R.color.chart_4, R.color.chart_5, R.color.chart_6
    };

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint namePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private List<NameValue> items = new ArrayList<>();
    private boolean money = true;

    public RankBarsView(Context context) {
        this(context, null);
    }

    public RankBarsView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        int onSurface = MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant);
        namePaint.setTextSize(sp(13));
        namePaint.setColor(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnSurface));
        valuePaint.setTextSize(sp(12));
        valuePaint.setColor(onSurface);
        valuePaint.setTextAlign(Paint.Align.RIGHT);
        trackPaint.setColor(onSurface);
        trackPaint.setAlpha(30);
    }

    public void setMoney(boolean money) {
        this.money = money;
    }

    public void setItems(List<NameValue> items) {
        this.items = items == null ? new ArrayList<>() : items;
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int rows = Math.max(items.size(), 1);
        int height = (int) (rows * dp(46) + dp(8));
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        if (items.isEmpty()) {
            valuePaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(getContext().getString(R.string.chart_no_data),
                    width / 2f, getHeight() / 2f, valuePaint);
            valuePaint.setTextAlign(Paint.Align.RIGHT);
            return;
        }

        double max = 0;
        for (NameValue item : items) max = Math.max(max, item.value);
        if (max <= 0) max = 1;

        float left = dp(4);
        float right = width - dp(4);
        float rowHeight = dp(46);

        for (int i = 0; i < items.size(); i++) {
            NameValue item = items.get(i);
            float top = dp(4) + i * rowHeight;

            String value = money ? Money.format(item.value) : Money.qty(item.value);
            float valueWidth = valuePaint.measureText(value) + dp(8);
            String name = TextUtils.ellipsize(item.safeName(), new android.text.TextPaint(namePaint),
                    right - left - valueWidth, TextUtils.TruncateAt.END).toString();

            canvas.drawText(name, left, top + sp(13), namePaint);
            canvas.drawText(value, right, top + sp(13), valuePaint);

            float barTop = top + dp(22);
            float barBottom = barTop + dp(10);
            rect.set(left, barTop, right, barBottom);
            canvas.drawRoundRect(rect, dp(5), dp(5), trackPaint);

            float barWidth = (float) ((right - left) * (item.value / max));
            rect.set(left, barTop, left + Math.max(barWidth, dp(10)), barBottom);
            barPaint.setColor(ContextCompat.getColor(getContext(), PALETTE[i % PALETTE.length]));
            canvas.drawRoundRect(rect, dp(5), dp(5), barPaint);
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
