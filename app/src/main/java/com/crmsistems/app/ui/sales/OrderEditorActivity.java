package com.crmsistems.app.ui.sales;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.crmsistems.app.R;
import com.crmsistems.app.data.Repository;
import com.crmsistems.app.data.entity.Client;
import com.crmsistems.app.data.entity.Product;
import com.crmsistems.app.data.entity.Sale;
import com.crmsistems.app.databinding.ActivityOrderEditorBinding;
import com.crmsistems.app.net.SyncManager;
import com.crmsistems.app.ui.common.PickDialog;
import com.crmsistems.app.util.Dates;
import com.crmsistems.app.util.Prefs;
import com.crmsistems.app.util.Ui;

import com.google.android.material.datepicker.MaterialDatePicker;

import java.util.ArrayList;
import java.util.List;

/**
 * Оформление заказа вручную: покупатель, сколько угодно позиций, дата
 * и комментарий. Всё уходит на сайт одним заказом.
 * <p>
 * Цены и себестоимость берутся из карточек товара. Если по конкретной сделке
 * нужна своя цена или скидка, продажу правят в её карточке — пока она не
 * ушла на сайт.
 */
public class OrderEditorActivity extends AppCompatActivity {

    /** Товар, который надо положить в заказ сразу (из карточки товара). */
    public static final String EXTRA_PRODUCT_ID = "product_id";

    /** Покупатель, выбранный заранее. */
    public static final String EXTRA_CLIENT_ID = "client_id";

    private ActivityOrderEditorBinding b;
    private Repository repo;
    private SaleDraft draft;

    private final List<Client> clients = new ArrayList<>();
    private Client client;
    private long date = System.currentTimeMillis();

    public static Intent intent(Context context, long clientId, long productId) {
        return new Intent(context, OrderEditorActivity.class)
                .putExtra(EXTRA_CLIENT_ID, clientId)
                .putExtra(EXTRA_PRODUCT_ID, productId);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityOrderEditorBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        Ui.applyInsets(b.root, b.appbarInc.getRoot(), b.bottomBar);

        repo = Repository.get(this);
        draft = new SaleDraft(this, this::showItems);

        b.appbarInc.toolbar.setTitle(R.string.add_sale);
        b.appbarInc.toolbar.setNavigationOnClickListener(v -> finish());

        b.client.setOnClickListener(v -> askClient());
        b.addItem.setOnClickListener(v -> draft.askProduct());
        b.date.setOnClickListener(v -> pickDate());
        b.save.setOnClickListener(v -> save());

        b.date.setText(Dates.formatDate(date));
        showItems();

        load(getIntent().getLongExtra(EXTRA_CLIENT_ID, 0),
                getIntent().getLongExtra(EXTRA_PRODUCT_ID, 0));
    }

    /** Что нужно экрану при открытии: покупатели и товар, с которого пришли. */
    private static class Preset {
        List<Client> clients;
        Product product;
    }

    private void load(long presetClient, long presetProduct) {
        repo.query(() -> {
            Preset data = new Preset();
            data.clients = repo.db().clientDao().getActive();
            data.product = presetProduct == 0 ? null : repo.db().productDao().getById(presetProduct);
            return data;
        }, data -> {
            if (data == null) return;
            clients.addAll(data.clients);
            if (presetClient != 0) {
                for (Client c : clients) {
                    if (c.id == presetClient) {
                        setClient(c);
                        break;
                    }
                }
            }
            if (data.product != null) draft.add(data.product, 1);
        });
    }

    private void askClient() {
        if (clients.isEmpty()) {
            Ui.snack(b.getRoot(), getString(R.string.order_no_clients));
            return;
        }
        List<PickDialog.Option> options = new ArrayList<>();
        for (Client c : clients) {
            options.add(new PickDialog.Option(c.title(),
                    c.phone.isEmpty() ? c.company : c.phone, null));
        }
        PickDialog.show(this, R.string.order_pick_client, options,
                which -> setClient(clients.get(which)));
    }

    private void setClient(Client picked) {
        client = picked;
        b.client.setText(picked.title());
    }

    private void pickDate() {
        MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText(R.string.sale_date)
                .setSelection(date)
                .build();
        picker.addOnPositiveButtonClickListener(selection -> {
            date = selection == null ? System.currentTimeMillis() : selection;
            b.date.setText(Dates.formatDate(date));
        });
        picker.show(getSupportFragmentManager(), "order_date");
    }

    private void showItems() {
        b.itemsEmpty.setVisibility(draft.isEmpty() ? View.VISIBLE : View.GONE);
        draft.render(b.itemsBox);
    }

    /**
     * Сохранить заказ: позиции ложатся одной продажей и уходят на сайт
     * одним заказом. Склад уменьшается на проданное — как и при продаже
     * из карточки клиента.
     */
    private void save() {
        if (draft.isEmpty()) {
            Ui.snack(b.getRoot(), getString(R.string.order_items_empty));
            return;
        }
        b.save.setEnabled(false);

        List<Sale> items = draft.toSales(
                client == null ? null : client.id,
                client == null ? "" : client.title(),
                date, Ui.text(b.note));

        repo.saveSales(items, true, () -> {
            Ui.snack(b.getRoot(), getString(R.string.sale_saved));
            if (!Prefs.serverReady(this)) {
                finish();
                return;
            }
            // Заказ появляется в админке сразу, а не при следующем обмене.
            new SyncManager(this).pushSalesNow(result -> {
                if (!result.ok()) Ui.snack(b.getRoot(), result.error);
                finish();
            });
        });
    }
}
