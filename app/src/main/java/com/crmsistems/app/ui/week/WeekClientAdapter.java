package com.crmsistems.app.ui.week;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.RecyclerView;

import com.crmsistems.app.R;
import com.crmsistems.app.databinding.ItemWeekClientBinding;
import com.crmsistems.app.ui.common.SegmentColors;
import com.crmsistems.app.ui.common.StatusColors;
import com.crmsistems.app.ui.orders.ServerOrder;
import com.crmsistems.app.util.Calls;
import com.crmsistems.app.util.Money;
import com.crmsistems.app.util.Phones;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** Список «кто заказал» на неделю: покупатель, его состав и сумма. */
public class WeekClientAdapter extends RecyclerView.Adapter<WeekClientAdapter.Holder> {

    /** Один покупатель со всем, что он заказал за неделю. */
    public static class Client implements Serializable {
        public String name = "";
        public String phone = "";
        public String address = "";
        public String segment = "";
        public final List<Item> items = new ArrayList<>();
        public double total;
        /** Положили всё, что он заказал, — карточка зелёная. */
        public boolean packed;

        /** Позиция заказа покупателя. */
        public static class Item implements Serializable {
            public String name = "";
            public String unit = "";
            public double qty;
            public double sum;
            public String photoUri = "";
            public String serverImage = "";
            /** Ключ галочки «положили» (см. {@link PackedMarks}) и стоит ли она. */
            public String mark;
            public boolean packed;
        }
    }

    public interface OnClick {
        void onClient(Client client);
    }

    public interface OnLongClick {
        void onClientLong(Client client);
    }

    /** Галочка «весь заказ собран» у покупателя. */
    public interface OnToggle {
        void onClientPacked(Client client, boolean packed);
    }

    private final List<Client> items = new ArrayList<>();
    private final OnClick onClick;
    private final OnLongClick onLongClick;
    private final OnToggle onToggle;

    public WeekClientAdapter(OnClick onClick, OnLongClick onLongClick, OnToggle onToggle) {
        this.onClick = onClick;
        this.onLongClick = onLongClick;
        this.onToggle = onToggle;
    }

    public void submit(List<Client> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(ItemWeekClientBinding.inflate(
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
        private final ItemWeekClientBinding b;

        Holder(ItemWeekClientBinding binding) {
            super(binding.getRoot());
            b = binding;
        }

        void bind(Client c) {
            b.title.setText(c.name);
            // Номер — на кнопке звонка, отдельной строкой его не пишем:
            // так же, как в заказах и в списке клиентов.
            boolean hasPhone = !c.phone.isEmpty();
            b.call.setVisibility(hasPhone ? android.view.View.VISIBLE : android.view.View.GONE);
            if (hasPhone) {
                b.call.setText(b.getRoot().getContext().getString(
                        R.string.order_call_number, Phones.pretty(c.phone)));
                b.call.setOnClickListener(v -> Calls.dial(v.getContext(), c.phone));
            }
            b.address.setText(c.address);
            b.address.setVisibility(c.address.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
            b.total.setText(Money.format(c.total));

            // Карточка в цвете сегмента — как в списке клиентов, а когда
            // всё положили — зелёная, как выполненный заказ.
            Context ctx = b.getRoot().getContext();
            int accent = c.packed
                    ? StatusColors.foreground(ctx, ServerOrder.STATUS_DONE)
                    : SegmentColors.foreground(ctx, c.segment);
            b.card.setCardBackgroundColor(c.packed
                    ? StatusColors.background(ctx, ServerOrder.STATUS_DONE)
                    : SegmentColors.background(ctx, c.segment));
            b.card.setStrokeColor(ColorUtils.setAlphaComponent(accent, 0x55));
            b.total.setTextColor(accent);

            boolean hasSegment = c.segment != null && !c.segment.isEmpty();
            b.segment.setVisibility(hasSegment ? android.view.View.VISIBLE : android.view.View.GONE);
            if (hasSegment) {
                b.segment.setText(c.segment);
                b.segment.setTextColor(accent);
                b.segment.setBackgroundTintList(
                        ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 0x2E)));
            }

            b.packed.setOnCheckedChangeListener(null);
            b.packed.setChecked(c.packed);
            b.packed.setButtonTintList(ColorStateList.valueOf(accent));
            b.packed.setOnCheckedChangeListener((v, checked) -> onToggle.onClientPacked(c, checked));

            b.getRoot().setOnClickListener(v -> onClick.onClient(c));
            b.getRoot().setOnLongClickListener(v -> {
                onLongClick.onClientLong(c);
                return true;
            });
        }
    }
}
