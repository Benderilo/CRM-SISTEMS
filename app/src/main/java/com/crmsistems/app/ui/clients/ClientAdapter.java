package com.crmsistems.app.ui.clients;

import android.content.Context;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.crmsistems.app.R;
import com.crmsistems.app.data.entity.Client;
import com.crmsistems.app.databinding.ItemClientBinding;
import com.crmsistems.app.ui.common.SegmentColors;
import com.crmsistems.app.util.Calls;
import com.crmsistems.app.util.Dates;
import com.crmsistems.app.util.Money;
import com.crmsistems.app.util.Phones;

public class ClientAdapter extends ListAdapter<ClientRow, ClientAdapter.VH> {

    public interface OnClick {
        void onClient(Client client);
    }

    private final OnClick listener;

    public ClientAdapter(OnClick listener) {
        super(DIFF);
        this.listener = listener;
    }

    private static final DiffUtil.ItemCallback<ClientRow> DIFF = new DiffUtil.ItemCallback<ClientRow>() {
        @Override
        public boolean areItemsTheSame(@NonNull ClientRow a, @NonNull ClientRow b) {
            return a.client.id == b.client.id;
        }

        @Override
        public boolean areContentsTheSame(@NonNull ClientRow a, @NonNull ClientRow b) {
            return a.client.updatedAt == b.client.updatedAt
                    && a.client.segment.equals(b.client.segment)
                    && a.client.name.equals(b.client.name)
                    && a.total == b.total
                    && a.salesCount == b.salesCount;
        }
    };

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(ItemClientBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        holder.bind(getItem(position));
    }

    class VH extends RecyclerView.ViewHolder {

        private final ItemClientBinding b;

        VH(ItemClientBinding binding) {
            super(binding.getRoot());
            this.b = binding;
        }

        void bind(ClientRow row) {
            Client c = row.client;
            b.name.setText(c.title());
            b.initials.setText(c.initials());

            // Телефон в подпись не пишем: он крупно написан на кнопке звонка
            // ниже — так же, как в карточке заказа. Дважды один номер, да
            // ещё и в разном виде, только мельтешит.
            StringBuilder sub = new StringBuilder();
            if (!c.company.isEmpty()) {
                if (sub.length() > 0) sub.append(" · ");
                sub.append(c.company);
            }
            if (c.blocked) {
                if (sub.length() > 0) sub.append(" · ");
                sub.append(b.getRoot().getContext().getString(R.string.client_blocked));
            }
            if (sub.length() == 0 && !c.email.isEmpty()) sub.append(c.email);
            if (sub.length() > 0) {
                b.subtitle.setText(sub.toString());
                b.subtitle.setVisibility(View.VISIBLE);
            } else if (c.phone.isEmpty()) {
                b.subtitle.setText("Контакты не заполнены");
                b.subtitle.setVisibility(View.VISIBLE);
            } else {
                // Кроме номера ничего нет, а он и так на кнопке.
                b.subtitle.setVisibility(View.GONE);
            }

            b.segment.setText(c.segment);

            // Карточка в цвете сегмента — постоянного от проблемного видно
            // сразу, не вчитываясь в подпись.
            Context ctx = b.getRoot().getContext();
            int accent = SegmentColors.foreground(ctx, c.segment);
            b.card.setCardBackgroundColor(SegmentColors.background(ctx, c.segment));
            b.card.setStrokeColor(ColorUtils.setAlphaComponent(accent, 0x55));
            b.segment.setTextColor(accent);
            b.segment.setBackgroundTintList(
                    ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 0x2E)));
            b.initials.setTextColor(accent);
            b.initials.setBackgroundTintList(
                    ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 0x33)));
            b.total.setTextColor(accent);

            // Адрес — над кнопкой звонка. Пустой прячем: пустая строка
            // только раздвигала бы карточки в списке.
            boolean hasAddress = !c.address.isEmpty();
            b.address.setVisibility(hasAddress ? View.VISIBLE : View.GONE);
            if (hasAddress) {
                b.address.setText(c.address);
            }

            // Номер прямо на кнопке: звонок — первое, что делают с клиентом,
            // и ради него не нужно открывать карточку. Нет номера — нет и
            // кнопки: неактивная только занимала бы место в списке.
            boolean hasPhone = !c.phone.isEmpty();
            b.call.setVisibility(hasPhone ? View.VISIBLE : View.GONE);
            if (hasPhone) {
                b.call.setText(ctx.getString(R.string.order_call_number,
                        Phones.pretty(c.phone)));
                b.call.setOnClickListener(v -> Calls.dial(ctx, c.phone));
            }

            b.total.setText(row.total > 0 ? Money.format(row.total) : "—");
            b.salesCount.setText(row.salesCount > 0
                    ? row.salesCount + " покупок · " + Dates.formatDate(row.lastSale)
                    : "нет покупок");

            if (!c.photoUri.isEmpty()) {
                try {
                    b.photo.setImageURI(Uri.parse(c.photoUri));
                    b.photo.setVisibility(View.VISIBLE);
                } catch (Exception e) {
                    b.photo.setVisibility(View.GONE);
                }
            } else {
                b.photo.setVisibility(View.GONE);
            }

            b.getRoot().setOnClickListener(v -> {
                if (listener != null) listener.onClient(c);
            });
        }
    }
}
