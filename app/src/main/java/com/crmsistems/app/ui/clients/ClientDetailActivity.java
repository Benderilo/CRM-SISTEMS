package com.crmsistems.app.ui.clients;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.crmsistems.app.R;
import com.crmsistems.app.data.Repository;
import com.crmsistems.app.data.entity.Client;
import com.crmsistems.app.data.entity.OrderEntity;
import com.crmsistems.app.data.entity.Sale;
import com.crmsistems.app.databinding.ActivityClientDetailBinding;
import com.crmsistems.app.net.ServerApi;
import com.crmsistems.app.net.SyncManager;
import com.crmsistems.app.ui.common.PickDialog;
import com.crmsistems.app.ui.common.Rows;
import com.crmsistems.app.ui.common.SegmentColors;
import com.crmsistems.app.ui.orders.OrderDetailActivity;
import com.crmsistems.app.ui.sales.SaleAdapter;
import com.crmsistems.app.ui.sales.SaleDraft;
import com.crmsistems.app.ui.sales.SaleEditorActivity;
import com.crmsistems.app.ui.sales.SaleRow;
import com.crmsistems.app.util.Calls;
import com.crmsistems.app.util.Dates;
import com.crmsistems.app.util.Money;
import com.crmsistems.app.util.Phones;
import com.crmsistems.app.util.Prefs;
import com.crmsistems.app.util.Ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Карточка клиента: контакты, показатели и все его покупки — и сделки из
 * приложения, и заказы, оформленные этим же телефоном на сайте.
 */
public class ClientDetailActivity extends AppCompatActivity {

    public static final String EXTRA_ID = "client_id";

    private ActivityClientDetailBinding b;
    private Repository repo;
    private long clientId;
    private Client client;

    private SaleAdapter adapter;
    private List<Sale> sales;
    private List<OrderEntity> orders;

    /** Позиции собираемой продажи — пока не нажали «Сохранить продажу». */
    private SaleDraft draft;

    public static Intent intent(Context context, long id) {
        return new Intent(context, ClientDetailActivity.class).putExtra(EXTRA_ID, id);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityClientDetailBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        Ui.applyInsets(b.root, b.appbarInc.getRoot(), b.bottomBar);

        repo = Repository.get(this);
        clientId = getIntent().getLongExtra(EXTRA_ID, 0);

        b.appbarInc.toolbar.setTitle(R.string.client_card);
        b.appbarInc.toolbar.setNavigationOnClickListener(v -> finish());
        b.appbarInc.toolbar.inflateMenu(R.menu.menu_detail);
        b.appbarInc.toolbar.setOnMenuItemClickListener(this::onMenu);

        b.statTotal.label.setText("Сумма покупок");
        b.statCount.label.setText("Сделок");
        b.statAvg.label.setText(R.string.kpi_avg_check);
        b.statLast.label.setText("Последняя покупка");

        adapter = new SaleAdapter(SaleAdapter.TITLE_PRODUCT, this::openRow);
        b.salesList.setLayoutManager(new LinearLayoutManager(this));
        b.salesList.setAdapter(adapter);

        draft = new SaleDraft(this, this::showDraft);
        b.addItem.setOnClickListener(v -> draft.askProduct());
        b.saveSale.setOnClickListener(v -> saveDraft());
        showDraft();

        b.call.setOnClickListener(v -> dial());
        b.mail.setOnClickListener(v -> mail());
        b.password.setOnClickListener(v -> askPassword());

        repo.client(clientId).observe(this, loaded -> {
            if (loaded == null) {
                finish();
                return;
            }
            client = loaded;
            bind(loaded);
            // Заказы ищем по телефону клиента — он же логин покупателя на сайте.
            showSales();
        });

        repo.salesOfClient(clientId).observe(this, list -> {
            sales = list;
            showSales();
        });

        repo.orders().observe(this, list -> {
            orders = list;
            showSales();
        });
    }

    /** Сделку открываем в редакторе, заказ с сайта — в карточке заказа. */
    private void openRow(SaleRow row) {
        if (row.sale != null) {
            startActivity(SaleEditorActivity.intent(this, row.sale.id, 0, 0));
            return;
        }
        // Продажа из нескольких позиций: сначала спрашиваем, какую открыть.
        if (row.group != null) {
            List<PickDialog.Option> options = new ArrayList<>();
            for (Sale s : row.group) {
                options.add(new PickDialog.Option(
                        s.productName.isEmpty() ? getString(R.string.sale_card) : s.productName,
                        Money.qty(s.qty) + " × " + Money.format(s.unitPrice),
                        Money.format(s.total())));
            }
            List<Sale> group = row.group;
            PickDialog.show(this, R.string.sale_card, options, which ->
                    startActivity(SaleEditorActivity.intent(this, group.get(which).id, 0, 0)));
            return;
        }
        startActivity(new Intent(this, OrderDetailActivity.class)
                .putExtra(OrderDetailActivity.EXTRA_ORDER, row.order));
    }

    private void showSales() {
        List<SaleRow> rows = SaleRow.forClient(sales, orders, client == null ? "" : client.phone);
        adapter.submitList(rows);
        b.salesEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        bindStats(rows);
    }

    private boolean onMenu(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_edit) {
            startActivity(ClientEditorActivity.intent(this, clientId));
            return true;
        }
        if (id == R.id.action_delete && client != null) {
            new AlertDialog.Builder(this)
                    .setTitle(client.title())
                    .setMessage(R.string.confirm_delete)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.delete, (d, w) ->
                            repo.deleteClient(client, this::finish))
                    .show();
            return true;
        }
        return false;
    }

    private void bind(Client c) {
        b.name.setText(c.title());
        b.initials.setText(c.initials());

        StringBuilder sub = new StringBuilder(c.segment);
        if (!c.company.isEmpty()) sub.append(" · ").append(c.company);
        if (!c.source.isEmpty()) sub.append(" · ").append(c.source);
        if (c.blocked) sub.append(" · ").append(getString(R.string.client_blocked));
        b.subtitle.setText(sub.toString());

        StringBuilder contacts = new StringBuilder();
        if (!c.phone.isEmpty()) contacts.append("Телефон: ").append(c.phone).append('\n');
        if (!c.email.isEmpty()) contacts.append("E-mail: ").append(c.email).append('\n');
        if (!c.address.isEmpty()) contacts.append("Адрес: ").append(c.address).append('\n');
        if (c.birthday > 0) contacts.append("День рождения: ").append(Dates.formatDate(c.birthday)).append('\n');
        if (!c.tags.isEmpty()) contacts.append("Метки: ").append(c.tags);
        b.contacts.setText(contacts.length() == 0 ? "Контакты не заполнены" : contacts.toString().trim());
        b.note.setText(c.note);
        b.note.setVisibility(c.note.isEmpty() ? View.GONE : View.VISIBLE);

        // Шапка в цвете сегмента — тот же язык, что и в списке клиентов.
        int accent = SegmentColors.foreground(this, c.segment);
        b.headerCard.setCardBackgroundColor(SegmentColors.background(this, c.segment));
        b.headerCard.setStrokeColor(ColorUtils.setAlphaComponent(accent, 0x55));
        b.subtitle.setTextColor(accent);
        b.initials.setTextColor(accent);
        b.initials.setBackgroundTintList(
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 0x33)));

        // Номер пишем прямо на кнопке, как в карточке заказа: так его видно,
        // не вчитываясь в блок контактов. Без номера кнопку прячем совсем —
        // неактивная кнопка «Позвонить» только сбивает с толку.
        boolean hasPhone = !c.phone.isEmpty();
        b.call.setVisibility(hasPhone ? View.VISIBLE : View.GONE);
        if (hasPhone) {
            b.call.setText(getString(R.string.order_call_number, Phones.pretty(c.phone)));
        }
        b.mail.setVisibility(c.email.isEmpty() ? View.GONE : View.VISIBLE);

        if (!c.photoUri.isEmpty()) {
            try {
                b.photo.setImageURI(Uri.parse(c.photoUri));
                b.photo.setVisibility(View.VISIBLE);
            } catch (Exception ignored) {
            }
        } else {
            b.photo.setVisibility(View.GONE);
        }
    }

    private void bindStats(List<SaleRow> rows) {
        double total = 0;
        int count = 0;
        long last = 0;
        for (SaleRow row : rows) {
            if (!row.counted()) continue;
            total += row.total();
            count++;
            last = Math.max(last, row.date());
        }
        b.statTotal.value.setText(Money.format(total));
        b.statCount.value.setText(String.valueOf(count));
        b.statAvg.value.setText(count > 0 ? Money.format(total / count) : "—");
        b.statLast.value.setText(last > 0 ? Dates.formatDate(last) : "—");
    }

    // ------------------------------------------------- продажа по позициям

    /** Состав собираемой продажи — так же, как состав заказа в его карточке. */
    private void showDraft() {
        boolean empty = draft.isEmpty();
        b.draftCard.setVisibility(empty ? View.GONE : View.VISIBLE);
        b.saveSale.setVisibility(empty ? View.GONE : View.VISIBLE);
        draft.render(b.draftBox);
    }

    /**
     * Сохранить собранные позиции одной продажей: на сайт она уйдёт одним
     * заказом со всем составом, а не россыпью заказов по товару.
     */
    private void saveDraft() {
        if (draft.isEmpty() || client == null) return;
        b.saveSale.setEnabled(false);

        List<Sale> items = draft.toSales(clientId, client.title(),
                System.currentTimeMillis(), "");
        repo.saveSales(items, true, () -> {
            draft.clear();
            b.saveSale.setEnabled(true);
            Ui.snack(b.getRoot(), getString(R.string.sale_saved));
            // Сайт узнаёт о продаже сразу, а не при следующем обмене.
            if (Prefs.serverReady(this)) {
                new SyncManager(this).pushSalesNow(result -> {
                    if (!result.ok()) Ui.snack(b.getRoot(), result.error);
                });
            }
        });
    }

    private void dial() {
        if (client == null || client.phone.isEmpty()) return;
        Calls.dial(this, client.phone);
    }

    // ------------------------------------------------- пароль от кабинета

    /**
     * Покупатель забыл пароль от личного кабинета. Почты и СМС у магазина нет,
     * поэтому «восстановление» тут человеческое: он звонит, продавец задаёт
     * новый пароль и диктует его прямо в разговоре.
     */
    private void askPassword() {
        if (client == null) return;
        if (client.serverId == 0) {
            Ui.snack(b.getRoot(), getString(R.string.client_password_no_server));
            return;
        }

        View view = getLayoutInflater().inflate(R.layout.dialog_client_password, null, false);
        com.google.android.material.textfield.TextInputEditText field =
                view.findViewById(R.id.password);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.client_password_title)
                .setView(view)
                .setPositiveButton(R.string.client_password_set, (d, w) ->
                        savePassword(Ui.text(field)))
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.show();
    }

    private void savePassword(String password) {
        final long serverId = client.serverId;
        ServerApi api = new ServerApi(this);
        repo.io().execute(() -> {
            String error = null;
            String saved = null;
            try {
                saved = api.setClientPassword(serverId, password).optString("password", password);
            } catch (ServerApi.ApiException e) {
                // Пароль живёт только на сайте: в офлайн-очередь его не кладём —
                // покупателю нужно услышать пароль сейчас, а не когда-нибудь.
                error = e.code == 0 ? getString(R.string.client_password_offline) : e.getMessage();
            }
            final String err = error;
            final String result = saved;
            runOnUiThread(() -> {
                if (err != null) {
                    Ui.snack(b.getRoot(), err);
                    return;
                }
                showPassword(result);
            });
        });
    }

    /** Показать назначенный пароль, чтобы его было удобно продиктовать. */
    private void showPassword(String password) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.client_password_done, password))
                .setMessage(R.string.client_password_done_hint)
                .setPositiveButton(R.string.ok, null)
                .setNeutralButton(R.string.client_password_copy, (d, w) -> {
                    android.content.ClipboardManager cm =
                            getSystemService(android.content.ClipboardManager.class);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText(
                                getString(R.string.client_password_field), password));
                        Ui.snack(b.getRoot(), getString(R.string.client_password_copied));
                    }
                })
                .show();
    }

    private void mail() {
        if (client == null || client.email.isEmpty()) return;
        try {
            startActivity(new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + client.email)));
        } catch (ActivityNotFoundException e) {
            Ui.snack(b.getRoot(), getString(R.string.operation_failed));
        }
    }
}
