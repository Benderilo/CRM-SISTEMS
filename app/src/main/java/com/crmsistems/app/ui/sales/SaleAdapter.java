package com.crmsistems.app.ui.sales;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.crmsistems.app.R;
import com.crmsistems.app.databinding.ItemRowBinding;
import com.crmsistems.app.ui.common.StatusColors;
import com.crmsistems.app.util.Calls;
import com.crmsistems.app.util.Maps;
import com.crmsistems.app.util.Money;
import com.crmsistems.app.util.Phones;

/**
 * Список продаж — и своих сделок, и заказов с сайта. Используется в карточке
 * клиента, карточке товара и в разделе «Продажи».
 */
public class SaleAdapter extends ListAdapter<SaleRow, SaleAdapter.VH> {

    public interface OnClick {
        void onRow(SaleRow row);
    }

    /** Долгое нажатие по строке — им удаляют заказ. */
    public interface OnLongClick {
        void onRowLong(SaleRow row);
    }

    /** Что показывать первой строкой. */
    public static final int TITLE_PRODUCT = SaleRow.TITLE_PRODUCT;
    public static final int TITLE_CLIENT = SaleRow.TITLE_CLIENT;

    private final OnClick listener;
    private final int titleMode;
    private final boolean withCall;
    private OnLongClick longListener;

    public SaleAdapter(int titleMode, OnClick listener) {
        this(titleMode, false, listener);
    }

    /** Что делать по долгому нажатию. Без этого оно просто не срабатывает. */
    public void setOnLongClick(OnLongClick listener) {
        this.longListener = listener;
    }

    /**
     * withCall — показывать ли на карточке кнопку звонка. Она нужна в разделе
     * «Заказы», где по заказу первым делом звонят покупателю; в карточке
     * клиента и товара номер и так под рукой, там кнопка только мешала бы.
     */
    public SaleAdapter(int titleMode, boolean withCall, OnClick listener) {
        super(DIFF);
        this.titleMode = titleMode;
        this.withCall = withCall;
        this.listener = listener;
    }

    private static final DiffUtil.ItemCallback<SaleRow> DIFF = new DiffUtil.ItemCallback<SaleRow>() {
        @Override
        public boolean areItemsTheSame(@NonNull SaleRow a, @NonNull SaleRow b) {
            return a.key().equals(b.key());
        }

        @Override
        public boolean areContentsTheSame(@NonNull SaleRow a, @NonNull SaleRow b) {
            return a.total() == b.total() && a.date() == b.date()
                    && a.statusName().equals(b.statusName())
                    && a.title(SaleRow.TITLE_PRODUCT).equals(b.title(SaleRow.TITLE_PRODUCT))
                    && a.title(SaleRow.TITLE_CLIENT).equals(b.title(SaleRow.TITLE_CLIENT));
        }
    };

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(ItemRowBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        holder.bind(getItem(position));
    }

    class VH extends RecyclerView.ViewHolder {

        private final ItemRowBinding b;

        VH(ItemRowBinding binding) {
            super(binding.getRoot());
            this.b = binding;
        }

        void bind(SaleRow row) {
            b.title.setText(row.title(titleMode));
            // Адрес показываем только там, где есть кнопка звонка, — в разделе
            // «Заказы»: по нему собирают развозку. В карточке клиента адрес и
            // так на виду, и в каждой строке он был бы лишним.
            String subtitle = row.subtitle(titleMode);
            String address = withCall ? row.address() : "";
            if (!address.isEmpty()) subtitle += "\n" + address;
            b.subtitle.setText(subtitle);
            b.total.setText(Money.format(row.total()));
            b.status.setText(row.statusName());
            b.icon.setImageResource(row.fromSite() ? R.drawable.ic_link : R.drawable.ic_sales);

            // Карточку красим по статусу: заливка светлая, сумма — насыщенным
            // цветом той же семьи, статус — сплошной плашкой этого цвета.
            Context ctx = b.getRoot().getContext();
            int status = row.colorStatus();
            int accent = StatusColors.foreground(ctx, status);
            b.card.setCardBackgroundColor(StatusColors.background(ctx, status));
            b.card.setStrokeColor(ColorUtils.setAlphaComponent(accent, 0x80));
            b.total.setTextColor(accent);
            StatusColors.paintPill(b.status, status);
            b.icon.setImageTintList(ColorStateList.valueOf(accent));
            b.icon.setBackgroundTintList(
                    ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 0x2E)));

            // Звонок и карта — одним рядом: номер слева, адрес на Яндекс Картах
            // справа. Нет адреса — звонок во всю ширину; нет телефона — ряд
            // остаётся ради карты.
            String phone = row.phone();
            boolean callable = withCall && !android.text.TextUtils.isEmpty(phone);
            boolean mappable = withCall && !address.trim().isEmpty();
            b.actions.setVisibility(callable || mappable
                    ? android.view.View.VISIBLE : android.view.View.GONE);
            b.call.setVisibility(callable ? android.view.View.VISIBLE : android.view.View.GONE);
            if (callable) {
                b.call.setText(Phones.pretty(phone));
                b.call.setOnClickListener(v -> Calls.dial(ctx, phone));
            }
            b.map.setVisibility(mappable ? android.view.View.VISIBLE : android.view.View.GONE);
            if (mappable) {
                b.map.setOnClickListener(v -> Maps.show(ctx, address));
            }

            b.getRoot().setOnClickListener(v -> {
                if (listener != null) listener.onRow(row);
            });
            b.getRoot().setOnLongClickListener(v -> {
                if (longListener == null) return false;
                longListener.onRowLong(row);
                return true;
            });
        }
    }
}
