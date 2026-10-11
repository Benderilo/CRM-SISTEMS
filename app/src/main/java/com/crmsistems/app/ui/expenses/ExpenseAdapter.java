package com.crmsistems.app.ui.expenses;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.crmsistems.app.R;
import com.crmsistems.app.data.entity.Expense;
import com.crmsistems.app.databinding.ItemRowBinding;
import com.crmsistems.app.util.Dates;
import com.crmsistems.app.util.Money;

public class ExpenseAdapter extends ListAdapter<Expense, ExpenseAdapter.VH> {

    public interface OnClick {
        void onExpense(Expense expense);
    }

    private final OnClick listener;

    public ExpenseAdapter(OnClick listener) {
        super(DIFF);
        this.listener = listener;
    }

    private static final DiffUtil.ItemCallback<Expense> DIFF = new DiffUtil.ItemCallback<Expense>() {
        @Override
        public boolean areItemsTheSame(@NonNull Expense a, @NonNull Expense b) {
            return a.id == b.id;
        }

        @Override
        public boolean areContentsTheSame(@NonNull Expense a, @NonNull Expense b) {
            return a.amount == b.amount && a.date == b.date
                    && a.title.equals(b.title) && a.category.equals(b.category);
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

        void bind(Expense e) {
            b.icon.setImageResource(R.drawable.ic_expenses);
            b.title.setText(e.title.isEmpty() ? e.category : e.title);
            b.subtitle.setText(Dates.formatDate(e.date) + " · " + e.category
                    + (e.note.isEmpty() ? "" : "\n" + e.note));
            b.total.setText("−" + Money.format(e.amount));
            b.total.setTextColor(ContextCompat.getColor(b.getRoot().getContext(), R.color.expense));
            b.status.setText("");
            b.getRoot().setOnClickListener(v -> {
                if (listener != null) listener.onExpense(e);
            });
        }
    }
}
