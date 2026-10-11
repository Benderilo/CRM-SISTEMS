package com.crmsistems.app.ui.week;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.RecyclerView;

import com.crmsistems.app.R;
import com.crmsistems.app.databinding.ItemRowBinding;
import com.crmsistems.app.ui.common.StatusColors;
import com.crmsistems.app.ui.orders.ServerOrder;
import com.crmsistems.app.util.Money;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Список «сколько чего собрать» на неделю: товар, количество и сумма. */
public class WeekPickAdapter extends RecyclerView.Adapter<WeekPickAdapter.Holder> {

    /** Один товар, собранный из всех заказов недели. */
    public static class Line {
        public String name = "";
        public String unit = "";
        public double qty;
        public double sum;
        public int orders;
        /** Фото товара: своё с телефона и путь на сайте — что найдётся. */
        public String photoUri = "";
        public String serverImage = "";
        /** Кто заказал этот товар: ключ покупателя → сколько взял. */
        public final Map<String, Double> buyers = new LinkedHashMap<>();
        /** Ключ покупателя → ключ его галочки «положили» (см. {@link PackedMarks}). */
        public final Map<String, String> marks = new LinkedHashMap<>();
        /** Всем покупателям уже положили — карточка зелёная. */
        public boolean packed;
    }

    public interface OnClick {
        void onLine(Line line);
    }

    /** Поставили или сняли галочку «положили» у строки. */
    public interface OnToggle {
        void onToggle(Line line, boolean packed);
    }

    private final List<Line> items = new ArrayList<>();
    @Nullable private final OnClick onClick;
    @Nullable private OnToggle onToggle;

    /** Справочный список — строки не нажимаются. */
    public WeekPickAdapter() {
        this(null);
    }

    public WeekPickAdapter(@Nullable OnClick onClick) {
        this.onClick = onClick;
    }

    /** Строки с галочкой: нажатие на карточку ставит или снимает её. */
    public static WeekPickAdapter withChecks(OnToggle onToggle) {
        WeekPickAdapter a = new WeekPickAdapter(null);
        a.onToggle = onToggle;
        return a;
    }

    public List<Line> lines() {
        return items;
    }

    public void submit(List<Line> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(ItemRowBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        h.bind(items.get(position));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    class Holder extends RecyclerView.ViewHolder {
        private final ItemRowBinding b;
        private final ColorStateList plainBackground;
        private final ColorStateList plainStroke;

        Holder(ItemRowBinding binding) {
            super(binding.getRoot());
            b = binding;
            plainBackground = b.card.getCardBackgroundColor();
            plainStroke = b.card.getStrokeColorStateList();
            if (onClick == null && onToggle == null) {
                // Строка справочная, нажимать не на что.
                b.card.setClickable(false);
                b.card.setFocusable(false);
            }
        }

        void bind(Line line) {
            com.crmsistems.app.util.Photos.thumb(b.icon, line.photoUri, line.serverImage,
                    com.crmsistems.app.util.Prefs.serverUrl(b.getRoot().getContext()));
            b.title.setText(line.name);
            b.subtitle.setText(b.getRoot().getContext()
                    .getString(R.string.week_in_orders, line.orders));
            b.total.setText(Money.qty(line.qty) + " " + line.unit);
            b.status.setText(Money.format(line.sum));
            b.status.setVisibility(View.VISIBLE);
            if (onClick != null) b.card.setOnClickListener(v -> onClick.onLine(line));

            b.packed.setVisibility(onToggle != null ? View.VISIBLE : View.GONE);
            if (onToggle != null) {
                b.packed.setOnCheckedChangeListener(null);
                b.packed.setChecked(line.packed);
                b.packed.setOnCheckedChangeListener((v, checked) -> onToggle.onToggle(line, checked));
                b.card.setOnClickListener(v -> b.packed.toggle());
            }

            // Собрано всем — зелёная, как выполненный заказ.
            if (line.packed) {
                Context ctx = b.getRoot().getContext();
                int accent = StatusColors.foreground(ctx, ServerOrder.STATUS_DONE);
                b.card.setCardBackgroundColor(StatusColors.background(ctx, ServerOrder.STATUS_DONE));
                b.card.setStrokeColor(ColorUtils.setAlphaComponent(accent, 0x55));
            } else {
                b.card.setCardBackgroundColor(plainBackground);
                b.card.setStrokeColor(plainStroke);
            }
        }
    }
}
