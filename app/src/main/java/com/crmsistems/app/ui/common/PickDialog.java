package com.crmsistems.app.ui.common;

import android.app.Activity;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.crmsistems.app.R;
import com.crmsistems.app.data.entity.Product;
import com.crmsistems.app.databinding.DialogPickBinding;
import com.crmsistems.app.databinding.ItemPickBinding;
import com.crmsistems.app.util.Money;

import java.util.ArrayList;
import java.util.List;

/**
 * Список выбора в диалоге — например, товар при добавлении позиции.
 * <p>
 * Вместо простого {@code setItems} со столбцом текста показываем карточки
 * с обводкой: у товаров бывают похожие названия, и по сплошному списку легко
 * промахнуться. Правая колонка — цена, вторая строка — единица и остаток.
 */
public final class PickDialog {

    /** Что показать одной строкой списка. */
    public static class Option {
        public final String title;
        public final String subtitle;
        public final String value;

        public Option(String title, @Nullable String subtitle, @Nullable String value) {
            this.title = title;
            this.subtitle = subtitle == null ? "" : subtitle;
            this.value = value == null ? "" : value;
        }
    }

    public interface OnPick {
        void onPick(int index);
    }

    private PickDialog() {
    }

    public static void show(Activity activity, @StringRes int title,
                            List<Option> options, OnPick onPick) {
        show(activity, title, options, onPick, 0, null);
    }

    /**
     * То же самое, но с дополнительной кнопкой слева от «Отмена» — например,
     * «Создать клиента», когда никого не подошло в списке.
     */
    public static void show(Activity activity, @StringRes int title,
                            List<Option> options, OnPick onPick,
                            @StringRes int neutralLabel, @Nullable Runnable onNeutral) {
        DialogPickBinding view = DialogPickBinding.inflate(activity.getLayoutInflater());
        view.list.setLayoutManager(new LinearLayoutManager(activity));

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(view.getRoot())
                .setNegativeButton(R.string.cancel, null);
        if (neutralLabel != 0 && onNeutral != null) {
            builder.setNeutralButton(neutralLabel, (d, w) -> onNeutral.run());
        }
        AlertDialog dialog = builder.create();

        view.list.setAdapter(new Adapter(new ArrayList<>(options), index -> {
            dialog.dismiss();
            onPick.onPick(index);
        }));
        dialog.show();
    }

    /** Товары строками списка: цена справа, единица и остаток — подписью. */
    public static List<Option> products(Context ctx, List<Product> products) {
        List<Option> options = new ArrayList<>();
        for (Product p : products) {
            options.add(new Option(p.name,
                    ctx.getString(R.string.pick_product_stock, p.unit, Money.qty(p.stock)),
                    Money.format(p.price)));
        }
        return options;
    }

    private static class Adapter extends RecyclerView.Adapter<Adapter.VH> {

        private final List<Option> options;
        private final OnPick listener;

        Adapter(List<Option> options, OnPick listener) {
            this.options = options;
            this.listener = listener;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(ItemPickBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            holder.bind(options.get(position), position);
        }

        @Override
        public int getItemCount() {
            return options.size();
        }

        class VH extends RecyclerView.ViewHolder {

            private final ItemPickBinding b;

            VH(ItemPickBinding binding) {
                super(binding.getRoot());
                this.b = binding;
            }

            void bind(Option option, int index) {
                b.title.setText(option.title);
                b.subtitle.setText(option.subtitle);
                b.subtitle.setVisibility(option.subtitle.isEmpty() ? View.GONE : View.VISIBLE);
                b.value.setText(option.value);
                b.value.setVisibility(option.value.isEmpty() ? View.GONE : View.VISIBLE);
                b.getRoot().setOnClickListener(v -> listener.onPick(index));
            }
        }
    }
}
