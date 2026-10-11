package com.crmsistems.app.ui.orders;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.crmsistems.app.R;
import com.crmsistems.app.data.AppDatabase;
import com.crmsistems.app.data.Repository;
import com.crmsistems.app.data.entity.OrderEntity;
import com.crmsistems.app.databinding.ActivityListBinding;
import com.crmsistems.app.net.SyncManager;
import com.crmsistems.app.ui.sync.ServerSettingsActivity;
import com.crmsistems.app.util.Money;
import com.crmsistems.app.util.Prefs;
import com.crmsistems.app.util.Ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Заказы, оформленные покупателями на сайте. Список берётся с сервера
 * при каждом открытии — там он всегда свежее. Если связи нет, показываем
 * последний сохранённый список из кэша телефона с пометкой «офлайн»;
 * сайт остаётся главным, и при появлении интернета кэш заменяется свежим.
 */
public class OrdersActivity extends AppCompatActivity {

    private ActivityListBinding b;
    private OrderAdapter adapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityListBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        Ui.applyInsets(b.root, b.appbarInc.getRoot(), b.list);

        b.appbarInc.toolbar.setTitle(R.string.orders_title);
        b.appbarInc.toolbar.setNavigationOnClickListener(v -> finish());

        // Заказы создаются покупателями на сайте, добавлять их с телефона незачем.
        b.fab.setVisibility(View.GONE);

        adapter = new OrderAdapter(this::openOrder);
        b.list.setLayoutManager(new LinearLayoutManager(this));
        b.list.setAdapter(adapter);

        b.emptyView.emptyTitle.setText(R.string.orders_empty);
        b.emptyView.emptyText.setText(R.string.orders_empty_hint);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void openOrder(ServerOrder order) {
        Intent i = new Intent(this, OrderDetailActivity.class);
        i.putExtra(OrderDetailActivity.EXTRA_ORDER, order);
        startActivity(i);
    }

    private void load() {
        if (!Prefs.serverReady(this)) {
            b.summary.setText(R.string.server_not_configured);
            b.emptyView.getRoot().setVisibility(View.VISIBLE);
            startActivity(new Intent(this, ServerSettingsActivity.class));
            return;
        }
        b.summary.setText(R.string.server_syncing);

        AppDatabase db = Repository.get(this).db();
        Repository.get(this).io().execute(() -> {
            List<ServerOrder> orders = null;
            boolean offline = false;
            try {
                // Сначала уходят накопленные офлайн-правки, затем свежий список в кэш.
                orders = new SyncManager(this).refreshOrdersBlocking();
            } catch (Exception e) {
                // Нет связи (или сервер недоступен) — покажем кэш, если он есть.
                List<OrderEntity> cached = db.orderDao().getAll();
                if (!cached.isEmpty()) {
                    orders = new ArrayList<>(cached.size());
                    for (OrderEntity ce : cached) orders.add(ServerOrder.fromEntity(ce));
                    offline = true;
                }
            }
            final List<ServerOrder> result = orders;
            final boolean fromCache = offline;
            runOnUiThread(() -> show(result, fromCache));
        });
    }

    private void show(@Nullable List<ServerOrder> orders, boolean fromCache) {
        if (orders == null) {
            b.summary.setText(R.string.orders_offline_empty);
            b.emptyView.getRoot().setVisibility(View.VISIBLE);
            return;
        }
        adapter.submit(orders);

        boolean empty = orders.isEmpty();
        b.emptyView.getRoot().setVisibility(empty ? View.VISIBLE : View.GONE);

        if (empty) {
            b.summary.setText(fromCache ? getString(R.string.orders_offline_empty) : "");
            return;
        }
        double sum = 0;
        int active = 0;
        for (ServerOrder o : orders) {
            if (o.status != 5) sum += o.total;      // отменённые в сумму не берём
            if (o.status == 0) active++;
        }
        String summary = getString(R.string.orders_summary,
                orders.size(), active, Money.format(sum));
        if (fromCache) {
            summary = getString(R.string.orders_offline_prefix) + "  " + summary;
        }
        b.summary.setText(summary);
    }
}
