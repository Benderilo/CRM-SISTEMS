package com.crmsistems.app.ui.week;

import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.crmsistems.app.R;
import com.crmsistems.app.databinding.ActivityListBinding;
import com.crmsistems.app.util.Money;
import com.crmsistems.app.util.Phones;
import com.crmsistems.app.util.Ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Что именно заказал один покупатель за неделю — теми же карточками,
 * что и в «Показать все»: товар, количество и сумма.
 */
public class WeekClientDetailActivity extends AppCompatActivity {

    public static final String EXTRA_CLIENT = "client";

    private ActivityListBinding b;
    private WeekPickAdapter adapter;
    private PackedMarks packed;
    private final List<WeekPickAdapter.Line> lines = new ArrayList<>();
    private String header = "";
    private MenuItem packAll;
    /** Пока заказ открыт, подтягиваем галочки с других телефонов. */
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            packed.syncAsync(WeekClientDetailActivity.this::refreshMarks);
            b.list.postDelayed(this, POLL_MS);
        }
    };
    private static final long POLL_MS = 15_000;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityListBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        Ui.applyInsets(b.root, b.appbarInc.getRoot(), b.list);

        WeekClientAdapter.Client client =
                (WeekClientAdapter.Client) getIntent().getSerializableExtra(EXTRA_CLIENT);
        if (client == null) {
            finish();
            return;
        }

        b.appbarInc.toolbar.setTitle(client.name);
        b.appbarInc.toolbar.setNavigationOnClickListener(v -> finish());
        b.fab.setVisibility(View.GONE);
        b.modeGroup.setVisibility(View.GONE);

        packed = PackedMarks.get(this);
        adapter = WeekPickAdapter.withChecks(this::toggle);
        b.list.setLayoutManager(new LinearLayoutManager(this));
        b.list.setAdapter(adapter);

        b.emptyView.getRoot().setVisibility(View.GONE);

        StringBuilder who = new StringBuilder();
        if (!client.phone.isEmpty()) who.append(Phones.pretty(client.phone));
        if (!client.address.isEmpty()) {
            if (who.length() > 0) who.append('\n');
            who.append(client.address);
        }
        if (who.length() > 0) who.append('\n');
        who.append(getString(R.string.order_total)).append(": ").append(Money.format(client.total));
        header = who.toString();

        for (WeekClientAdapter.Client.Item it : client.items) {
            WeekPickAdapter.Line line = new WeekPickAdapter.Line();
            line.name = it.name;
            line.unit = it.unit;
            line.qty = it.qty;
            line.sum = it.sum;
            line.orders = 1;
            line.photoUri = it.photoUri;
            line.serverImage = it.serverImage;
            if (it.mark != null) line.marks.put("", it.mark);
            line.packed = it.mark != null && packed.has(it.mark);
            lines.add(line);
        }
        adapter.submit(lines);

        // Весь заказ разом — та же галочка, что на карточке в «Кто заказал».
        packAll = b.appbarInc.toolbar.getMenu().add(R.string.week_pack_all);
        packAll.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        packAll.setOnMenuItemClickListener(item -> {
            boolean done = !allPacked();
            List<String> marks = new ArrayList<>();
            for (WeekPickAdapter.Line l : lines) {
                String mark = l.marks.get("");
                if (mark == null) continue;
                marks.add(mark);
                l.packed = done;
            }
            packed.set(marks, done, this::refreshMarks);
            adapter.notifyDataSetChanged();
            showProgress();
            return true;
        });
        showProgress();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (packed == null) return;
        refreshMarks();
        packed.syncAsync(this::refreshMarks);
        b.list.postDelayed(poll, POLL_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (b != null) b.list.removeCallbacks(poll);
    }

    /** Галочки поменяли — здесь или на другом телефоне. */
    private void refreshMarks() {
        for (WeekPickAdapter.Line l : lines) {
            String mark = l.marks.get("");
            l.packed = mark != null && packed.has(mark);
        }
        adapter.notifyDataSetChanged();
        showProgress();
    }

    private boolean allPacked() {
        for (WeekPickAdapter.Line l : lines) if (!l.packed) return false;
        return true;
    }

    /**
     * Галочка та же, что в окне товара: отметили здесь — товар и покупатель
     * в «Сборке» зеленеют сами, когда собрано всё.
     */
    private void toggle(WeekPickAdapter.Line line, boolean done) {
        String mark = line.marks.get("");
        if (mark == null) return;
        packed.set(Collections.singletonList(mark), done, this::refreshMarks);
        // Один товар из двух заказов — две строки с общей галочкой.
        for (WeekPickAdapter.Line l : lines) {
            if (mark.equals(l.marks.get(""))) l.packed = done;
        }
        b.list.post(adapter::notifyDataSetChanged);
        showProgress();
    }

    /** Над списком — сколько уже положили. */
    private void showProgress() {
        int done = 0;
        for (WeekPickAdapter.Line l : lines) if (l.packed) done++;
        String progress = done == lines.size()
                ? getString(R.string.week_packed_all)
                : getString(R.string.week_packed_progress, done, lines.size());
        b.summary.setText(header + "\n" + progress);
        if (packAll != null) {
            packAll.setTitle(allPacked() ? R.string.week_unpack_all : R.string.week_pack_all);
        }
    }
}
