package com.crmsistems.app.ui.analytics;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.crmsistems.app.R;
import com.crmsistems.app.databinding.FragmentAnalyticsBinding;
import com.crmsistems.app.util.Money;

/** Аналитика: показатели за период и графики по товарам, клиентам, доходам и расходам. */
public class AnalyticsFragment extends Fragment {

    private FragmentAnalyticsBinding b;
    private AnalyticsViewModel vm;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        b = FragmentAnalyticsBinding.inflate(inflater, container, false);
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        vm = new ViewModelProvider(this).get(AnalyticsViewModel.class);

        b.statRevenue.label.setText(R.string.kpi_revenue);
        b.statExpense.label.setText(R.string.kpi_expense);
        b.statProfit.label.setText(R.string.kpi_profit);
        b.statPending.label.setText(R.string.kpi_pending);
        b.statOrders.label.setText(R.string.kpi_site_orders);
        b.statOrdersRevenue.label.setText(R.string.kpi_site_orders_revenue);
        b.statSales.label.setText(R.string.kpi_sales);
        b.statAvg.label.setText(R.string.kpi_avg_check);
        b.statClients.label.setText(R.string.kpi_clients);
        b.statProducts.label.setText(R.string.kpi_products);

        b.statRevenue.value.setTextColor(ContextCompat.getColor(requireContext(), R.color.income));
        b.statExpense.value.setTextColor(ContextCompat.getColor(requireContext(), R.color.expense));
        b.statProfit.value.setTextColor(ContextCompat.getColor(requireContext(), R.color.profit));
        b.statOrdersRevenue.value.setTextColor(ContextCompat.getColor(requireContext(), R.color.income));

        b.moneyChart.setLegend(getString(R.string.legend_income), getString(R.string.legend_expense));
        b.salesChart.setColors(
                ContextCompat.getColor(requireContext(), R.color.chart_1),
                ContextCompat.getColor(requireContext(), R.color.chart_1));
        b.topProducts.setMoney(true);
        b.topClients.setMoney(true);
        b.expenseDonut.setMoney(true);
        b.segmentDonut.setMoney(false);

        b.periodGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) {
                b.chipQuarter.setChecked(true);
                return;
            }
            int id = checkedIds.get(0);
            if (id == R.id.chipWeek) vm.setPeriod(AnalyticsViewModel.PERIOD_WEEK);
            else if (id == R.id.chipMonth) vm.setPeriod(AnalyticsViewModel.PERIOD_MONTH);
            else if (id == R.id.chipYear) vm.setPeriod(AnalyticsViewModel.PERIOD_YEAR);
            else if (id == R.id.chipAll) vm.setPeriod(AnalyticsViewModel.PERIOD_ALL);
            else vm.setPeriod(AnalyticsViewModel.PERIOD_QUARTER);
        });

        vm.data().observe(getViewLifecycleOwner(), this::render);
    }

    private void render(AnalyticsViewModel.Snapshot s) {
        if (s == null || b == null) return;

        b.statRevenue.value.setText(Money.format(s.revenue));
        b.statExpense.value.setText(Money.format(s.expense));
        b.statProfit.value.setText(Money.format(s.profit));
        b.statPending.value.setText(Money.format(s.pending));
        b.statOrders.value.setText(s.ordersCount + " (+" + s.ordersNew + ")");
        b.statOrdersRevenue.value.setText(Money.format(s.ordersRevenue));
        // «Заказов» — все сделки: свои плюс заказы с сайта (разбивка — в соседней плитке).
        b.statSales.value.setText(String.valueOf(s.dealsCount));
        b.statAvg.value.setText(Money.format(s.avgCheck));
        b.statClients.value.setText(s.clientsCount + " (+" + s.newClients + ")");
        b.statProducts.value.setText(s.productsCount + " · " + Money.compact(s.stockValue));
        b.statProducts.label.setText(R.string.kpi_products_stock);

        b.moneyChart.setData(s.chartLabels, s.chartRevenue, s.chartExpense);
        b.salesChart.setData(s.chartLabels, s.chartSales, null);
        b.topProducts.setItems(s.topProducts);
        b.topClients.setItems(s.topClients);
        b.expenseDonut.setItems(s.expenseCategories, Money.compact(s.expense));
        b.segmentDonut.setItems(s.clientSegments, String.valueOf(s.clientsCount));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        b = null;
    }
}
