package com.crmsistems.app.ui.orders;

import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.RecyclerView;

import com.crmsistems.app.R;
import com.crmsistems.app.databinding.ItemOrderBinding;
import com.crmsistems.app.ui.common.StatusColors;
import com.crmsistems.app.util.Calls;
import com.crmsistems.app.util.Dates;
import com.crmsistems.app.util.Maps;
import com.crmsistems.app.util.Money;
import com.crmsistems.app.util.Phones;

import java.util.ArrayList;
import java.util.List;

/** Список заказов с сайта. */
public class OrderAdapter extends RecyclerView.Adapter<OrderAdapter.Holder> {

    public interface OnClick {
        void onOrder(ServerOrder order);
    }

    private final List<ServerOrder> items = new ArrayList<>();
    private final OnClick onClick;

    public OrderAdapter(OnClick onClick) {
        this.onClick = onClick;
    }

    public void submit(List<ServerOrder> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(ItemOrderBinding.inflate(
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
        private final ItemOrderBinding b;

        Holder(ItemOrderBinding binding) {
            super(binding.getRoot());
            this.b = binding;
        }

        void bind(ServerOrder o) {
            Context ctx = b.getRoot().getContext();
            b.number.setText(o.number);
            b.status.setText(o.statusName);

            // Номер телефона на кнопке звонка, поэтому здесь — только имя.
            String client = o.clientName == null || o.clientName.isEmpty()
                    ? ctx.getString(R.string.new_order_unknown_buyer) : o.clientName;
            b.client.setText(client);

            // Рядом с картой места мало, поэтому на кнопке только номер:
            // трубка на ней и так говорит, что это звонок.
            boolean hasPhone = !TextUtils.isEmpty(o.phone);
            b.call.setEnabled(hasPhone);
            b.call.setText(hasPhone
                    ? Phones.pretty(o.phone)
                    : ctx.getString(R.string.order_no_phone));
            b.call.setOnClickListener(v -> Calls.dial(ctx, o.phone));

            boolean hasAddress = !TextUtils.isEmpty(o.address)
                    && !o.address.trim().isEmpty();
            b.map.setVisibility(hasAddress ? View.VISIBLE : View.GONE);
            b.map.setOnClickListener(v -> Maps.show(ctx, o.address));

            b.items.setText(o.itemsSummary());
            b.date.setText(Dates.formatDateTime(o.createdAt));
            b.total.setText(Money.format(o.total));

            // Карточка целиком в цвете статуса — видно, что горит, не читая текст.
            int accent = StatusColors.foreground(ctx, o.status);
            b.card.setCardBackgroundColor(StatusColors.background(ctx, o.status));
            b.card.setStrokeColor(ColorUtils.setAlphaComponent(accent, 0x80));
            StatusColors.paintPill(b.status, o.status);
            b.total.setTextColor(accent);

            b.getRoot().setOnClickListener(v -> onClick.onOrder(o));
        }
    }
}
