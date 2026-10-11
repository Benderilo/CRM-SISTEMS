package com.crmsistems.app.ui.sales;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.crmsistems.app.R;
import com.crmsistems.app.data.Repository;
import com.crmsistems.app.databinding.FragmentSalesBinding;
import com.crmsistems.app.net.ServerApi;
import com.crmsistems.app.net.SyncManager;
import com.crmsistems.app.ui.common.Chips;
import com.crmsistems.app.ui.common.SimpleTextWatcher;
import com.crmsistems.app.ui.common.StatusColors;
import com.crmsistems.app.ui.orders.OrderDetailActivity;
import com.crmsistems.app.ui.orders.OrderNotifier;
import com.crmsistems.app.ui.orders.ServerOrder;
import com.crmsistems.app.util.Money;
import com.crmsistems.app.util.Prefs;
import com.crmsistems.app.util.Ui;
import com.google.android.material.chip.Chip;

import java.util.List;

/**
 * Все заказы одним списком: сделки, заведённые в приложении, и заказы,
 * оформленные покупателями на сайте (на сайте и те и другие — заказы).
 * Сверху — поиск и фильтр-чипы по всем шести статусам, каждый в цвете
 * своей карточки.
 * <p>
 * Заказы берутся из локального кэша (он же питает аналитику), при открытии
 * вкладки и свайпом вниз кэш тихо обновляется с сайта — если связи нет,
 * остаётся последняя удачная копия.
 */
public class SalesFragment extends Fragment {

    private FragmentSalesBinding b;
    private SalesViewModel vm;
    private SaleAdapter adapter;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        b = FragmentSalesBinding.inflate(inflater, container, false);
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        vm = new ViewModelProvider(this).get(SalesViewModel.class);

        b.emptyView.emptyIcon.setImageResource(R.drawable.ic_sales);
        b.emptyView.emptyText.setText(R.string.sales_empty);

        adapter = new SaleAdapter(SaleAdapter.TITLE_PRODUCT, true, this::openRow);
        adapter.setOnLongClick(this::askDelete);
        b.list.setLayoutManager(new LinearLayoutManager(requireContext()));
        b.list.setAdapter(adapter);
        b.list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy > 8) b.fab.shrink();
                else if (dy < -8) b.fab.extend();
            }
        });

        b.fab.setOnClickListener(v ->
                startActivity(OrderEditorActivity.intent(requireContext(), 0, 0)));

        b.searchInput.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void onChanged(String text) {
                vm.setQuery(text);
            }
        });

        // Фильтр — по всем шести статусам, каждый чип в цвете своей карточки:
        // видно, какой статус как выглядит, а выбранный заливается цветом
        // плашки статуса, как на карточке заказа.
        paintStatusChip(b.chipNew, ServerOrder.STATUS_NEW);
        paintStatusChip(b.chipAccepted, ServerOrder.STATUS_ACCEPTED);
        paintStatusChip(b.chipPacking, ServerOrder.STATUS_PACKING);
        paintStatusChip(b.chipDelivery, ServerOrder.STATUS_DELIVERY);
        paintStatusChip(b.chipDone, ServerOrder.STATUS_DONE);
        paintStatusChip(b.chipCanceled, ServerOrder.STATUS_CANCELED);

        b.statusGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) {
                b.chipAll.setChecked(true);
                return;
            }
            vm.setFilter(statusOf(checkedIds.get(0)));
        });

        // Потянули вниз — полная синхронизация с сайтом: подтянутся новые
        // заказы покупателей и свежие статусы, список обновится сам.
        b.swipe.setOnRefreshListener(() -> {
            if (!Prefs.serverReady(requireContext())) {
                b.swipe.setRefreshing(false);
                Ui.snack(b.getRoot(), getString(R.string.server_not_configured));
                return;
            }
            new SyncManager(requireContext()).syncAll(result -> {
                if (b == null) return;
                b.swipe.setRefreshing(false);
                if (!result.ok()) {
                    Ui.snack(b.getRoot(), result.summary());
                } else if (!result.newOrders.isEmpty()) {
                    Ui.snack(b.getRoot(), OrderNotifier.summary(requireContext(), result.newOrders));
                }
            });
        });

        vm.visible().observe(getViewLifecycleOwner(), this::show);
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshOrders();
    }

    /**
     * Обновить кэш заказов с сайта в фоне; список сам перерисуется по LiveData.
     * Если с сайта приехали заказы, которых ещё не было, — говорим об этом
     * подсказкой (телефон параллельно покажет уведомление).
     * <p>
     * Контекст берём до ухода в фон: пока идёт обмен, вкладку могут закрыть,
     * и requireContext() из фонового потока уже упал бы.
     */
    private void refreshOrders() {
        if (b == null || !Prefs.serverReady(requireContext())) return;
        final Context ctx = requireContext().getApplicationContext();
        Repository.get(ctx).io().execute(() -> {
            SyncManager.Result r = new SyncManager.Result();
            try {
                new SyncManager(ctx).refreshOrdersBlocking(r);
            } catch (Exception ignored) {
                // Нет связи — показываем последнюю удачную копию из кэша.
            }
            if (r.newOrders.isEmpty()) return;
            String message = OrderNotifier.summary(ctx, r.newOrders);
            main.post(() -> {
                if (b != null) Ui.snack(b.getRoot(), message);
            });
        });
    }

    /** Сделку открываем в редакторе, заказ с сайта — в карточке заказа. */
    private void openRow(SaleRow row) {
        if (row.sale != null) {
            startActivity(SaleEditorActivity.intent(requireContext(), row.sale.id, 0, 0));
            return;
        }
        startActivity(new Intent(requireContext(), OrderDetailActivity.class)
                .putExtra(OrderDetailActivity.EXTRA_ORDER, row.order));
    }

    /** Статус фильтра по выбранному чипу; «Все» — без фильтра. */
    private int statusOf(int id) {
        if (id == R.id.chipNew) return ServerOrder.STATUS_NEW;
        if (id == R.id.chipAccepted) return ServerOrder.STATUS_ACCEPTED;
        if (id == R.id.chipPacking) return ServerOrder.STATUS_PACKING;
        if (id == R.id.chipDelivery) return ServerOrder.STATUS_DELIVERY;
        if (id == R.id.chipDone) return ServerOrder.STATUS_DONE;
        if (id == R.id.chipCanceled) return ServerOrder.STATUS_CANCELED;
        return SalesViewModel.FILTER_ALL;
    }

    /**
     * Чип статуса красится той же парой цветов, что и карточка заказа:
     * обычное состояние — заливка цветом фона карточки, выбранное — цветом
     * плашки статуса.
     */
    private void paintStatusChip(Chip chip, int status) {
        Context ctx = chip.getContext();
        chip.setText(ServerOrder.statusName(status));
        Chips.paint(chip, StatusColors.background(ctx, status),
                StatusColors.foreground(ctx, status));
    }

    /**
     * Долгое нажатие по строке — удаление. Спрашиваем подтверждение: заказ
     * пропадёт и с сайта, вернуть его можно будет только из копии на сервере.
     */
    private void askDelete(SaleRow row) {
        new AlertDialog.Builder(requireContext())
                .setTitle(row.title(SaleAdapter.TITLE_CLIENT))
                .setMessage(R.string.order_delete_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> delete(row))
                .show();
    }

    /**
     * Сделку из приложения удаляем в своей базе, заказ с сайта — на сервере
     * и следом в кэше телефона; список перерисуется сам по LiveData.
     */
    private void delete(SaleRow row) {
        if (row.sale != null) {
            Repository.get(requireContext()).deleteSale(row.sale, () -> {
                if (b != null) Ui.snack(b.getRoot(), getString(R.string.order_deleted));
            });
            return;
        }
        final Context ctx = requireContext().getApplicationContext();
        final long id = row.order.id;
        final ServerApi api = new ServerApi(ctx);
        Repository.get(ctx).io().execute(() -> {
            String message;
            try {
                api.deleteOrder(id);
                Repository.get(ctx).db().orderDao().markDeleted(id, System.currentTimeMillis());
                message = ctx.getString(R.string.order_deleted);
            } catch (ServerApi.ApiException e) {
                // Удаление на сервере в очередь не кладём: слишком дорогая
                // ошибка, если правка уйдёт не тогда, когда её ждали.
                message = e.code == 0
                        ? ctx.getString(R.string.order_delete_offline) : e.getMessage();
            }
            final String text = message;
            main.post(() -> {
                if (b != null) Ui.snack(b.getRoot(), text);
            });
        });
    }

    /** Итоги считаем по тому, что сейчас видно: поиск и фильтр влияют на цифры. */
    private void show(List<SaleRow> rows) {
        if (b == null) return;
        adapter.submitList(rows);
        b.emptyView.emptyRoot.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);

        double revenue = 0, profit = 0, pending = 0;
        int siteOrders = 0;
        for (SaleRow row : rows) {
            if (row.fromSite()) siteOrders++;
            if (row.counted()) {
                revenue += row.total();
                profit += row.profit();
            } else if (row.pending()) {
                pending += row.total();
            }
        }
        StringBuilder sb = new StringBuilder("Выручка ").append(Money.format(revenue))
                .append(" · прибыль ").append(Money.format(profit));
        if (pending > 0) sb.append(" · ожидает оплаты ").append(Money.format(pending));
        if (siteOrders > 0) sb.append(" · с сайта ").append(siteOrders);
        b.summary.setText(sb.toString());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        b = null;
    }
}
