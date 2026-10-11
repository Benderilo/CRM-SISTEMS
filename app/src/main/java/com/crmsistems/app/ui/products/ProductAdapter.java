package com.crmsistems.app.ui.products;

import android.net.Uri;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.crmsistems.app.R;
import com.crmsistems.app.data.entity.Product;
import com.crmsistems.app.databinding.ItemProductBinding;
import com.crmsistems.app.util.Money;

public class ProductAdapter extends ListAdapter<Product, ProductAdapter.VH> {

    public interface OnClick {
        void onProduct(Product product);
    }

    private final OnClick listener;

    public ProductAdapter(OnClick listener) {
        super(DIFF);
        this.listener = listener;
    }

    private static final DiffUtil.ItemCallback<Product> DIFF = new DiffUtil.ItemCallback<Product>() {
        @Override
        public boolean areItemsTheSame(@NonNull Product a, @NonNull Product b) {
            return a.id == b.id;
        }

        @Override
        public boolean areContentsTheSame(@NonNull Product a, @NonNull Product b) {
            return a.updatedAt == b.updatedAt
                    && a.name.equals(b.name)
                    && a.price == b.price
                    && a.stock == b.stock
                    && a.photoUri.equals(b.photoUri)
                    && a.serverImage.equals(b.serverImage);
        }
    };

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(ItemProductBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        holder.bind(getItem(position));
    }

    class VH extends RecyclerView.ViewHolder {

        private final ItemProductBinding b;

        VH(ItemProductBinding binding) {
            super(binding.getRoot());
            this.b = binding;
        }

        void bind(Product p) {
            b.name.setText(p.title());

            StringBuilder sub = new StringBuilder();
            if (!p.sku.isEmpty()) sub.append(p.sku);
            if (!p.category.isEmpty()) {
                if (sub.length() > 0) sub.append(" · ");
                sub.append(p.category);
            }
            b.subtitle.setText(sub.length() == 0 ? "—" : sub.toString());

            b.price.setText(Money.format(p.price));
            b.margin.setText(p.cost > 0
                    ? "наценка " + Money.percent(p.marginPercent())
                    : "себест. —");

            b.stock.setText("Остаток: " + Money.qty(p.stock) + " " + p.unit);
            int color = p.isLowStock()
                    ? androidx.core.content.ContextCompat.getColor(b.getRoot().getContext(), R.color.expense)
                    : com.google.android.material.color.MaterialColors.getColor(b.stock, com.google.android.material.R.attr.colorOnSurfaceVariant);
            b.stock.setTextColor(color);

            com.crmsistems.app.util.Photos.thumb(b.photo, p.photoUri, p.serverImage,
                    com.crmsistems.app.util.Prefs.serverUrl(b.getRoot().getContext()));

            b.getRoot().setOnClickListener(v -> {
                if (listener != null) listener.onProduct(p);
            });
        }
    }
}
