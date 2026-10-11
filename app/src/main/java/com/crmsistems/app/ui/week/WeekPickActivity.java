package com.crmsistems.app.ui.week;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.crmsistems.app.R;
import com.crmsistems.app.data.Repository;
import com.crmsistems.app.data.entity.Client;
import com.crmsistems.app.data.entity.OrderEntity;
import com.crmsistems.app.data.entity.Product;
import com.crmsistems.app.databinding.ActivityListBinding;
import com.crmsistems.app.databinding.ItemWeekBuyerBinding;
import com.crmsistems.app.net.SyncManager;
import com.crmsistems.app.ui.common.SegmentColors;
import com.crmsistems.app.ui.common.StatusColors;
import com.crmsistems.app.ui.orders.ServerOrder;
import com.crmsistems.app.ui.sync.ServerSettingsActivity;
import com.crmsistems.app.util.Money;
import com.crmsistems.app.util.Phones;
import com.crmsistems.app.util.Prefs;
import com.crmsistems.app.util.Ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Сколько товара набралось к развозке.
 * <p>
 * Показываем всё, что ещё не собрано, без оглядки на даты. Раньше список
 * обрезался текущей неделей, и заказ, принятый в воскресенье, в понедельник
 * пропадал из сборки, хотя везти его никто не успел. Ограничение убрано:
 * заказ уходит из списка сам, когда его отмечают выполненным.
 * <p>
 * В сборку попадают подтверждённые заказы — «Принят», «Собирается»,
 * «В доставке». «Новый» ещё не подтверждён, «Выполнен» уже увезли,
 * «Отменён» не считается вовсе.
 */
public class WeekPickActivity extends AppCompatActivity {

    private ActivityListBinding b;
    private WeekPickAdapter adapter;
    private WeekClientAdapter clientAdapter;

    private List<ServerOrder> weekOrders = new ArrayList<>();
    private Map<Long, Product> byServer = new HashMap<>();
    private Map<String, Client> clientsByPhone = new HashMap<>();
    private boolean fromCache;
    private PackedMarks packed;
    /** Открытое окно «кто заказал товар» — его тоже обновляем, когда приходят чужие галочки. */
    @Nullable private ArrayAdapter<?> buyersRows;
    /** Пока экран открыт, подтягиваем галочки с других телефонов. */
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            packed.syncAsync(WeekPickActivity.this::refreshMarks);
            b.list.postDelayed(this, POLL_MS);
        }
    };
    private static final long POLL_MS = 15_000;
    /** Товар + покупатель → ключ галочки «положили». */
    private Map<String, String> markByItem = new HashMap<>();
    private int taken;
    private int waiting;
    private double sum;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityListBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        Ui.applyInsets(b.root, b.appbarInc.getRoot(), b.list);

        b.appbarInc.toolbar.setTitle(R.string.week_title);
        b.appbarInc.toolbar.setNavigationOnClickListener(v -> finish());
        b.fab.setVisibility(View.GONE);

        packed = PackedMarks.get(this);
        adapter = new WeekPickAdapter(this::showBuyers);
        clientAdapter = new WeekClientAdapter(this::openClient, this::shareClient, this::packClient);
        b.list.setLayoutManager(new LinearLayoutManager(this));
        b.list.setAdapter(adapter);

        b.modeGroup.setVisibility(View.VISIBLE);
        b.modeGroup.check(R.id.modeAll);
        b.modeGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            showMode();
        });

        b.emptyView.emptyTitle.setText(R.string.week_empty);
        b.emptyView.emptyText.setText(R.string.week_empty_hint);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
        b.list.postDelayed(poll, POLL_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        b.list.removeCallbacks(poll);
    }

    /** Пришли чужие галочки — перекрашиваем всё, что на экране. */
    private void refreshMarks() {
        for (WeekPickAdapter.Line line : adapter.lines()) updatePacked(line);
        adapter.notifyDataSetChanged();
        if (b.modeGroup.getCheckedButtonId() == R.id.modeClients) {
            clientAdapter.submit(buildClients());
        }
        if (buyersRows != null) buyersRows.notifyDataSetChanged();
    }

    private void load() {
        if (!Prefs.serverReady(this)) {
            b.summary.setText(R.string.server_not_configured);
            b.emptyView.getRoot().setVisibility(View.VISIBLE);
            startActivity(new Intent(this, ServerSettingsActivity.class));
            return;
        }
        b.summary.setText(R.string.server_syncing);

        Repository.get(this).io().execute(() -> {
            List<ServerOrder> orders;
            boolean offline = false;
            try {
                orders = new SyncManager(this).refreshOrdersBlocking();
            } catch (Exception e) {
                // Нет связи — считаем по последнему сохранённому списку.
                orders = new ArrayList<>();
                for (OrderEntity ce : Repository.get(this).db().orderDao().getAll()) {
                    orders.add(ServerOrder.fromEntity(ce));
                }
                offline = true;
            }
            // Товары читаем здесь же, в фоне: Room не разрешает ходить
            // в базу из главного потока, а фото для строк берутся оттуда.
            final Map<Long, Product> byServer = productsByServerId();
            final Map<String, Client> clientsByPhone = clientsByPhone();
            final List<ServerOrder> list = orders;
            final boolean fromCache = offline;
            // Галочки с других телефонов — до показа, чтобы список не мигал.
            packed.syncBlocking();
            runOnUiThread(() -> show(list, byServer, clientsByPhone, fromCache));
        });
    }

    /**
     * Клиенты телефона по ключу телефона — по ним берём сегмент для карточек
     * «кто заказал». Вызывать только из фонового потока.
     */
    private Map<String, Client> clientsByPhone() {
        Map<String, Client> map = new HashMap<>();
        for (Client c : Repository.get(this).db().clientDao().getAll()) {
            String key = Phones.key(c.phone);
            if (!key.isEmpty()) map.put(key, c);
        }
        return map;
    }

    /**
     * Товары телефона по их id на сайте — по ним берём фото для строк сборки.
     * Вызывать только из фонового потока.
     */
    private Map<Long, Product> productsByServerId() {
        Map<Long, Product> map = new HashMap<>();
        for (Product p : Repository.get(this).db().productDao().getAll()) {
            if (p.serverId != 0) map.put(p.serverId, p);
        }
        return map;
    }

    private void show(List<ServerOrder> orders, Map<Long, Product> byServer,
                      Map<String, Client> clientsByPhone, boolean fromCache) {
        this.byServer = byServer;
        this.clientsByPhone = clientsByPhone;
        this.fromCache = fromCache;
        this.weekOrders = new ArrayList<>();
        this.taken = 0;
        this.waiting = 0;
        this.sum = 0;

        Map<String, WeekPickAdapter.Line> lines = new LinkedHashMap<>();
        // Товар → покупатель → номера его заказов с этим товаром (для галочек).
        Map<String, Map<String, TreeSet<Long>>> orderIds = new HashMap<>();

        for (ServerOrder o : orders) {
            if (o.status == ServerOrder.STATUS_NEW) {
                waiting++;
                continue;
            }
            // Выполненные уже увезли, отменённые не считаем.
            if (o.status == ServerOrder.STATUS_DONE
                    || o.status == ServerOrder.STATUS_CANCELED) {
                continue;
            }
            taken++;
            weekOrders.add(o);
            for (ServerOrder.Item it : o.items) {
                // Позиции одного товара из разных заказов складываем в одну строку.
                String key = lineKey(it);
                WeekPickAdapter.Line line = lines.get(key);
                if (line == null) {
                    line = new WeekPickAdapter.Line();
                    line.name = it.name;
                    line.unit = it.unit;
                    Product p = byServer.get(it.productId);
                    if (p != null) {
                        line.photoUri = p.photoUri;
                        line.serverImage = p.serverImage;
                    }
                    lines.put(key, line);
                }
                line.qty += it.qty;
                line.sum += it.sum;
                line.orders++;
                String buyer = clientKey(o);
                Double had = line.buyers.get(buyer);
                line.buyers.put(buyer, (had == null ? 0 : had) + it.qty);
                orderIds.computeIfAbsent(key, k -> new HashMap<>())
                        .computeIfAbsent(buyer, k -> new TreeSet<>()).add(o.id);
            }
            sum += o.total;
        }

        markByItem = new HashMap<>();
        for (Map.Entry<String, WeekPickAdapter.Line> e : lines.entrySet()) {
            WeekPickAdapter.Line line = e.getValue();
            for (Map.Entry<String, TreeSet<Long>> ids : orderIds.get(e.getKey()).entrySet()) {
                String mark = e.getKey() + "|" + ids.getKey() + "|" + ids.getValue();
                line.marks.put(ids.getKey(), mark);
                markByItem.put(e.getKey() + "\n" + ids.getKey(), mark);
            }
            updatePacked(line);
        }
        List<WeekPickAdapter.Line> result = new ArrayList<>(lines.values());
        // По алфавиту — так удобнее собирать заказ по полкам.
        result.sort((x, y) -> x.name.compareToIgnoreCase(y.name));
        adapter.submit(result);

        b.summary.setText(summary(taken, waiting, sum, fromCache));
        showMode();
    }

    /** Переключает список между «все товары» и «кто заказал». */
    private void showMode() {
        boolean clients = b.modeGroup.getCheckedButtonId() == R.id.modeClients;
        if (clients) {
            b.list.setAdapter(clientAdapter);
            clientAdapter.submit(buildClients());
        } else {
            b.list.setAdapter(adapter);
        }
        boolean empty = clients ? clientAdapter.getItemCount() == 0 : adapter.getItemCount() == 0;
        b.emptyView.getRoot().setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    /** Собирает покупателей недели: имя, телефон, адрес, сегмент, состав и сумма. */
    private List<WeekClientAdapter.Client> buildClients() {
        List<WeekClientAdapter.Client> result = new ArrayList<>(buildClientsByKey().values());
        result.sort((x, y) -> x.name.compareToIgnoreCase(y.name));
        return result;
    }

    /** Позиции одного товара из разных заказов — одна строка сборки. */
    private static String lineKey(ServerOrder.Item it) {
        return it.productId > 0 ? "p" + it.productId : it.name + "|" + it.unit;
    }

    /** Покупатель заказа: по телефону, а без телефона — сам заказ. */
    private static String clientKey(ServerOrder o) {
        String key = o.phoneKey();
        return key.isEmpty() ? "id" + o.id : key;
    }

    /** Покупатели недели по ключу {@link #clientKey}. */
    private Map<String, WeekClientAdapter.Client> buildClientsByKey() {
        Map<String, WeekClientAdapter.Client> map = new LinkedHashMap<>();
        for (ServerOrder o : weekOrders) {
            String key = clientKey(o);
            WeekClientAdapter.Client c = map.get(key);
            if (c == null) {
                c = new WeekClientAdapter.Client();
                c.name = o.buyer();
                c.phone = o.phone;
                c.address = o.address;
                Client local = clientsByPhone.get(o.phoneKey());
                if (local != null) c.segment = local.segment;
                map.put(key, c);
            }
            for (ServerOrder.Item it : o.items) {
                WeekClientAdapter.Client.Item ci = new WeekClientAdapter.Client.Item();
                ci.name = it.name;
                ci.unit = it.unit;
                ci.qty = it.qty;
                ci.sum = it.sum;
                ci.mark = markByItem.get(lineKey(it) + "\n" + key);
                Product p = byServer.get(it.productId);
                if (p != null) {
                    ci.photoUri = p.photoUri;
                    ci.serverImage = p.serverImage;
                }
                c.items.add(ci);
            }
            c.total += o.total;
        }
        for (WeekClientAdapter.Client c : map.values()) applyPacked(c);
        return map;
    }

    /** Отмечает собранные позиции; покупатель зелёный, когда положили всё. */
    private void applyPacked(WeekClientAdapter.Client c) {
        boolean all = !c.items.isEmpty();
        for (WeekClientAdapter.Client.Item it : c.items) {
            it.packed = it.mark != null && packed.has(it.mark);
            if (!it.packed) all = false;
        }
        c.packed = all;
    }

    /** Товар зелёный, когда его положили всем, кто заказал. */
    private void updatePacked(WeekPickAdapter.Line line) {
        boolean all = !line.marks.isEmpty();
        for (String mark : line.marks.values()) {
            if (!packed.has(mark)) {
                all = false;
                break;
            }
        }
        line.packed = all;
    }

    /**
     * Нажали на товар — кто его заказал и сколько. Нажатие на покупателя
     * открывает весь его заказ, как в «Кто заказал».
     */
    private void showBuyers(WeekPickAdapter.Line line) {
        Map<String, WeekClientAdapter.Client> clients = buildClientsByKey();
        List<WeekClientAdapter.Client> who = new ArrayList<>();
        List<Double> qty = new ArrayList<>();
        List<String> whoMarks = new ArrayList<>();
        for (Map.Entry<String, Double> e : line.buyers.entrySet()) {
            WeekClientAdapter.Client c = clients.get(e.getKey());
            if (c == null) continue;
            who.add(c);
            qty.add(e.getValue());
            whoMarks.add(line.marks.get(e.getKey()));
        }
        // По алфавиту, как в «Кто заказал».
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < who.size(); i++) order.add(i);
        order.sort((x, y) -> who.get(x).name.compareToIgnoreCase(who.get(y).name));

        List<WeekClientAdapter.Client> sorted = new ArrayList<>();
        List<String> amounts = new ArrayList<>();
        List<String> marks = new ArrayList<>();
        for (int k : order) {
            sorted.add(who.get(k));
            amounts.add(Money.qty(qty.get(k)) + " " + line.unit);
            marks.add(whoMarks.get(k));
        }

        // Каждый покупатель — карточкой в цвете сегмента, как в «Кто заказал»;
        // положили — карточка зелёная.
        ArrayAdapter<WeekClientAdapter.Client> rows =
                new ArrayAdapter<WeekClientAdapter.Client>(this, 0, sorted) {
                    @NonNull
                    @Override
                    public View getView(int position, @Nullable View convertView,
                                        @NonNull ViewGroup parent) {
                        ItemWeekBuyerBinding rb = convertView == null
                                ? ItemWeekBuyerBinding.inflate(getLayoutInflater(), parent, false)
                                : ItemWeekBuyerBinding.bind(convertView);
                        WeekClientAdapter.Client c = sorted.get(position);
                        String mark = marks.get(position);
                        boolean done = packed.has(mark);
                        int accent = done
                                ? StatusColors.foreground(getContext(), ServerOrder.STATUS_DONE)
                                : SegmentColors.foreground(getContext(), c.segment);
                        rb.card.setCardBackgroundColor(done
                                ? StatusColors.background(getContext(), ServerOrder.STATUS_DONE)
                                : SegmentColors.background(getContext(), c.segment));
                        rb.card.setStrokeColor(ColorUtils.setAlphaComponent(accent, 0x55));
                        rb.packed.setOnCheckedChangeListener(null);
                        rb.packed.setChecked(done);
                        rb.packed.setButtonTintList(ColorStateList.valueOf(accent));
                        rb.packed.setOnCheckedChangeListener((v, checked) -> {
                            packed.set(Collections.singletonList(mark), checked,
                                    WeekPickActivity.this::refreshMarks);
                            updatePacked(line);
                            notifyDataSetChanged();
                            adapter.notifyDataSetChanged();
                        });
                        rb.name.setText(c.name);
                        rb.qty.setText(amounts.get(position));
                        rb.qty.setTextColor(accent);
                        return rb.getRoot();
                    }
                };

        buyersRows = rows;
        new MaterialAlertDialogBuilder(this)
                .setTitle(line.name)
                .setAdapter(rows, (d, which) -> openClient(sorted.get(which)))
                .setNegativeButton(R.string.week_buyers_close, null)
                .setOnDismissListener(d -> buyersRows = null)
                .show();
    }

    /** Галочка на карточке покупателя — отметить или снять весь его заказ. */
    private void packClient(WeekClientAdapter.Client client, boolean done) {
        List<String> marks = new ArrayList<>();
        for (WeekClientAdapter.Client.Item it : client.items) {
            if (it.mark != null) marks.add(it.mark);
        }
        packed.set(marks, done, this::refreshMarks);
        for (WeekPickAdapter.Line line : adapter.lines()) updatePacked(line);
        // Пересобираем после текущего нажатия: RecyclerView не любит,
        // когда список меняют прямо из привязки строки.
        b.list.post(() -> {
            clientAdapter.submit(buildClients());
            adapter.notifyDataSetChanged();
        });
    }

    /** Показать, что именно заказал этот покупатель — карточками, как в «Показать все». */
    private void openClient(WeekClientAdapter.Client client) {
        // Галочки могли поставить, пока окно товара было открыто.
        applyPacked(client);
        Intent i = new Intent(this, WeekClientDetailActivity.class);
        i.putExtra(WeekClientDetailActivity.EXTRA_CLIENT, client);
        startActivity(i);
    }

    /** Долгое нажатие — поделиться списком заказа в любой мессенджер. */
    private void shareClient(WeekClientAdapter.Client client) {
        StringBuilder sb = new StringBuilder();
        sb.append(client.name);
        if (!client.phone.isEmpty()) sb.append(" · ").append(Phones.pretty(client.phone));
        if (!client.address.isEmpty()) sb.append('\n').append(client.address);
        sb.append('\n');
        for (WeekClientAdapter.Client.Item it : client.items) {
            sb.append("• ").append(it.name).append(" — ")
                    .append(Money.qty(it.qty)).append(' ').append(it.unit)
                    .append(" (").append(Money.format(it.sum)).append(")\n");
        }
        sb.append(getString(R.string.order_total)).append(": ").append(Money.format(client.total));

        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, sb.toString());
        startActivity(Intent.createChooser(send, getString(R.string.week_share_title)));
    }

    /**
     * Строка над списком: сколько заказов и на какую сумму собирать. Ради
     * этого сюда и заходят, поэтому лишнего в неё не добавляем.
     */
    private String summary(int taken, int waiting, double sum, boolean fromCache) {
        StringBuilder sb = new StringBuilder();
        if (fromCache) sb.append(getString(R.string.orders_offline_prefix)).append(' ');
        sb.append(getString(R.string.week_summary, taken, Money.format(sum)));
        if (waiting > 0) {
            sb.append('\n').append(getString(R.string.week_waiting, waiting));
        }
        return sb.toString();
    }

}
