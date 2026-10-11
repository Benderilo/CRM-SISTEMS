package com.crmsistems.app.ui.products;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.crmsistems.app.R;
import com.crmsistems.app.databinding.FragmentProductsBinding;
import com.crmsistems.app.net.SyncManager;
import com.crmsistems.app.ui.common.SimpleTextWatcher;
import com.crmsistems.app.util.Money;
import com.crmsistems.app.util.Prefs;
import com.crmsistems.app.util.Ui;

/** Список товаров: поиск, сортировка, быстрый переход в карточку, FAB «Добавить товар». */
public class ProductsFragment extends Fragment {

    private FragmentProductsBinding b;
    private ProductsViewModel vm;
    private ProductAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        b = FragmentProductsBinding.inflate(inflater, container, false);
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        vm = new ViewModelProvider(this).get(ProductsViewModel.class);

        adapter = new ProductAdapter(product ->
                startActivity(ProductDetailActivity.intent(requireContext(), product.id)));
        b.list.setLayoutManager(new LinearLayoutManager(requireContext()));
        b.list.setAdapter(adapter);
        b.list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy > 8) b.fab.shrink();
                else if (dy < -8) b.fab.extend();
            }
        });

        b.emptyView.emptyIcon.setImageResource(R.drawable.ic_products);
        b.emptyView.emptyText.setText(R.string.products_empty);

        b.searchInput.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void onChanged(String text) {
                vm.setQuery(text);
            }
        });

        b.sortGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) {
                b.chipName.setChecked(true);
                return;
            }
            int id = checkedIds.get(0);
            if (id == R.id.chipPrice) vm.setMode(ProductsViewModel.SORT_PRICE);
            else if (id == R.id.chipStock) vm.setMode(ProductsViewModel.SORT_STOCK);
            else if (id == R.id.chipLow) vm.setMode(ProductsViewModel.ONLY_LOW_STOCK);
            else vm.setMode(ProductsViewModel.SORT_NAME);
        });

        b.fab.setOnClickListener(v ->
                startActivity(ProductEditorActivity.intent(requireContext(), 0)));

        // Потянули список вниз — полная синхронизация с сайтом: подтянутся
        // свежие остатки, цены, новые товары. Список обновится сам через LiveData.
        b.swipe.setOnRefreshListener(() -> {
            if (!Prefs.serverReady(requireContext())) {
                b.swipe.setRefreshing(false);
                Ui.snack(b.getRoot(), getString(R.string.server_not_configured));
                return;
            }
            new SyncManager(requireContext()).syncAll(result -> {
                if (b == null) return;
                b.swipe.setRefreshing(false);
                if (!result.ok()) Ui.snack(b.getRoot(), result.summary());
            });
        });

        vm.visible().observe(getViewLifecycleOwner(), products -> {
            adapter.submitList(products);
            boolean empty = products == null || products.isEmpty();
            b.emptyView.emptyRoot.setVisibility(empty ? View.VISIBLE : View.GONE);
            b.summary.setText(getString(R.string.nav_products) + ": " + vm.all().size()
                    + " · склад " + Money.format(vm.totalStockValue())
                    + (vm.lowStockCount() > 0 ? " · заканчивается: " + vm.lowStockCount() : ""));
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        b = null;
    }
}
