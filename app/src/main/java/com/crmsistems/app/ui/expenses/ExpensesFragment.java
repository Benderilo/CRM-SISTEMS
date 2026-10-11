package com.crmsistems.app.ui.expenses;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.crmsistems.app.R;
import com.crmsistems.app.data.Repository;
import com.crmsistems.app.data.entity.Expense;
import com.crmsistems.app.databinding.DialogExpenseBinding;
import com.crmsistems.app.databinding.FragmentExpensesBinding;
import com.crmsistems.app.util.Dates;
import com.crmsistems.app.util.Money;
import com.crmsistems.app.util.Ui;

import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** Расходы: список и быстрое добавление через диалог. */
public class ExpensesFragment extends Fragment {

    private FragmentExpensesBinding b;
    private Repository repo;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        b = FragmentExpensesBinding.inflate(inflater, container, false);
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        repo = Repository.get(requireContext());

        b.emptyView.emptyIcon.setImageResource(R.drawable.ic_expenses);
        b.emptyView.emptyText.setText(R.string.expenses_empty);

        ExpenseAdapter adapter = new ExpenseAdapter(this::editExpense);
        b.list.setLayoutManager(new LinearLayoutManager(requireContext()));
        b.list.setAdapter(adapter);

        b.fab.setOnClickListener(v -> editExpense(null));

        repo.expenses().observe(getViewLifecycleOwner(), expenses -> {
            if (b == null) return;
            adapter.submitList(expenses);
            boolean empty = expenses == null || expenses.isEmpty();
            b.emptyView.emptyRoot.setVisibility(empty ? View.VISIBLE : View.GONE);
            double total = 0;
            if (expenses != null) for (Expense e : expenses) total += e.amount;
            b.summary.setText("Всего расходов: " + Money.format(total));
        });
    }

    private void editExpense(@Nullable Expense source) {
        if (b == null) return;
        Expense expense = source == null ? new Expense() : source;
        if (expense.date == 0) expense.date = System.currentTimeMillis();

        DialogExpenseBinding d = DialogExpenseBinding.inflate(getLayoutInflater());
        d.category.setSimpleItems(Expense.CATEGORIES);
        d.title.setText(expense.title);
        d.category.setText(expense.category.isEmpty() ? Expense.CATEGORIES[0] : expense.category, false);
        if (expense.amount > 0) d.amount.setText(Money.plain(expense.amount));
        d.note.setText(expense.note);
        d.date.setText(Dates.formatDate(expense.date));

        final long[] date = {expense.date};
        d.date.setOnClickListener(v -> {
            MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
                    .setTitleText(R.string.sale_date)
                    .setSelection(date[0])
                    .build();
            picker.addOnPositiveButtonClickListener(selection -> {
                date[0] = selection == null ? System.currentTimeMillis() : selection;
                d.date.setText(Dates.formatDate(date[0]));
            });
            picker.show(getParentFragmentManager(), "expense_date");
        });

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(source == null ? R.string.add_expense : R.string.expense_title)
                .setView(d.getRoot())
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    expense.title = Ui.text(d.title);
                    expense.category = Ui.text(d.category).isEmpty()
                            ? Expense.CATEGORIES[0] : Ui.text(d.category);
                    expense.amount = Ui.number(d.amount);
                    expense.note = Ui.text(d.note);
                    expense.date = date[0];
                    if (expense.amount <= 0) {
                        Ui.snack(b.getRoot(), "Укажите сумму расхода");
                        return;
                    }
                    repo.saveExpense(expense, id -> {
                    });
                });
        if (source != null) {
            builder.setNeutralButton(R.string.delete, (dialog, which) ->
                    repo.deleteExpense(expense, () -> {
                    }));
        }
        builder.show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        b = null;
    }
}
