package com.crmsistems.app.ui.sales;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.crmsistems.app.R;
import com.crmsistems.app.data.Repository;
import com.crmsistems.app.data.entity.Client;
import com.crmsistems.app.data.entity.Product;
import com.crmsistems.app.data.entity.Sale;
import com.crmsistems.app.databinding.ActivitySaleEditorBinding;
import com.crmsistems.app.net.SyncManager;
import com.crmsistems.app.ui.common.SimpleTextWatcher;
import com.crmsistems.app.util.Dates;
import com.crmsistems.app.util.Money;
import com.crmsistems.app.util.Prefs;
import com.crmsistems.app.util.Ui;

import com.google.android.material.datepicker.MaterialDatePicker;

import java.util.ArrayList;
import java.util.List;

/**
 * Оформление продажи — здесь товар «привязывается» к клиенту.
 * Цена и себестоимость копируются из карточки товара, но их можно изменить для конкретной сделки.
 */
public class SaleEditorActivity extends AppCompatActivity {

    public static final String EXTRA_SALE_ID = "sale_id";
    public static final String EXTRA_CLIENT_ID = "client_id";
    public static final String EXTRA_PRODUCT_ID = "product_id";

    private ActivitySaleEditorBinding b;
    private Repository repo;

    private Sale sale = new Sale();
    private final List<Client> clients = new ArrayList<>();
    private final List<Product> products = new ArrayList<>();
    private Client selectedClient;
    private Product selectedProduct;
    private boolean editing;

    public static Intent intent(Context context, long saleId, long clientId, long productId) {
        return new Intent(context, SaleEditorActivity.class)
                .putExtra(EXTRA_SALE_ID, saleId)
                .putExtra(EXTRA_CLIENT_ID, clientId)
                .putExtra(EXTRA_PRODUCT_ID, productId);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivitySaleEditorBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        Ui.applyInsets(b.root, b.appbarInc.getRoot(), b.bottomBar);

        repo = Repository.get(this);

        long saleId = getIntent().getLongExtra(EXTRA_SALE_ID, 0);
        long clientId = getIntent().getLongExtra(EXTRA_CLIENT_ID, 0);
        long productId = getIntent().getLongExtra(EXTRA_PRODUCT_ID, 0);
        editing = saleId != 0;

        b.appbarInc.toolbar.setNavigationOnClickListener(v -> finish());
        b.appbarInc.toolbar.setTitle(editing ? R.string.sale_card : R.string.add_sale);
        if (editing) {
            b.appbarInc.toolbar.inflateMenu(R.menu.menu_editor);
            b.appbarInc.toolbar.setOnMenuItemClickListener(this::onMenu);
        }

        b.status.setSimpleItems(Sale.STATUS_NAMES);
        b.status.setText(Sale.STATUS_NAMES[0], false);
        b.qty.setText("1");
        sale.date = System.currentTimeMillis();
        b.date.setText(Dates.formatDate(sale.date));
        b.date.setOnClickListener(v -> pickDate());
        // Списывается только новая продажа: при правке остаток уже уменьшен.
        b.writeOffNote.setVisibility(editing ? android.view.View.GONE : android.view.View.VISIBLE);

        SimpleTextWatcher recalc = new SimpleTextWatcher() {
            @Override
            public void onChanged(String text) {
                updateTotal();
            }
        };
        b.qty.addTextChangedListener(recalc);
        b.price.addTextChangedListener(recalc);
        b.cost.addTextChangedListener(recalc);
        b.discount.addTextChangedListener(recalc);

        b.client.setOnItemClickListener((parent, view, position, id) -> {
            selectedClient = position >= 0 && position < clients.size() ? clients.get(position) : null;
        });
        b.product.setOnItemClickListener((parent, view, position, id) -> {
            selectedProduct = position >= 0 && position < products.size() ? products.get(position) : null;
            if (selectedProduct != null) {
                b.price.setText(Money.plain(selectedProduct.price));
                b.cost.setText(Money.plain(selectedProduct.cost));
            }
        });

        b.save.setOnClickListener(v -> save());

        loadDictionaries(saleId, clientId, productId);
        updateTotal();
    }

    private boolean onMenu(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_delete) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.sale_card)
                    .setMessage(R.string.confirm_delete)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.delete, (d, w) -> repo.deleteSale(sale, this::finish))
                    .show();
            return true;
        }
        return false;
    }

    /** Всё, что нужно экрану: справочники и (при редактировании) сама сделка. */
    private static class Dictionaries {
        List<Client> clients;
        List<Product> products;
        Sale sale;
    }

    /** Грузим справочники клиентов и товаров, затем — саму сделку. */
    private void loadDictionaries(long saleId, long presetClient, long presetProduct) {
        repo.query(() -> {
            Dictionaries data = new Dictionaries();
            data.clients = repo.db().clientDao().getActive();
            data.products = repo.db().productDao().getActive();
            data.sale = saleId == 0 ? null : repo.db().saleDao().getById(saleId);
            return data;
        }, data -> {
            if (data == null) return;
            clients.clear();
            products.clear();
            clients.addAll(data.clients);
            products.addAll(data.products);

            String[] clientNames = new String[clients.size()];
            for (int i = 0; i < clients.size(); i++) {
                Client c = clients.get(i);
                clientNames[i] = c.company.isEmpty() ? c.title() : c.title() + " (" + c.company + ")";
            }
            String[] productNames = new String[products.size()];
            for (int i = 0; i < products.size(); i++) {
                Product p = products.get(i);
                productNames[i] = p.title() + " · " + Money.format(p.price);
            }
            b.client.setSimpleItems(clientNames);
            b.product.setSimpleItems(productNames);

            if (data.sale != null) {
                sale = data.sale;
                fill();
            } else {
                if (presetClient != 0) selectClient(presetClient);
                if (presetProduct != 0) selectProduct(presetProduct);
                updateTotal();
            }
        });
    }

    private void selectClient(long id) {
        for (int i = 0; i < clients.size(); i++) {
            if (clients.get(i).id == id) {
                selectedClient = clients.get(i);
                b.client.setText(selectedClient.title(), false);
                return;
            }
        }
    }

    private void selectProduct(long id) {
        for (int i = 0; i < products.size(); i++) {
            if (products.get(i).id == id) {
                selectedProduct = products.get(i);
                b.product.setText(selectedProduct.title(), false);
                b.price.setText(Money.plain(selectedProduct.price));
                b.cost.setText(Money.plain(selectedProduct.cost));
                return;
            }
        }
    }

    private void fill() {
        if (sale.clientId != null) selectClient(sale.clientId);
        if (sale.productId != null) selectProduct(sale.productId);
        if (selectedClient == null && !sale.clientName.isEmpty()) b.client.setText(sale.clientName, false);
        if (selectedProduct == null && !sale.productName.isEmpty()) b.product.setText(sale.productName, false);
        b.qty.setText(Money.plain(sale.qty));
        b.price.setText(Money.plain(sale.unitPrice));
        b.cost.setText(Money.plain(sale.unitCost));
        b.discount.setText(Money.plain(sale.discount));
        b.note.setText(sale.note);
        b.date.setText(Dates.formatDate(sale.date));
        b.status.setText(sale.statusName(), false);
        updateTotal();
    }

    private void pickDate() {
        MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText(R.string.sale_date)
                .setSelection(sale.date > 0 ? sale.date : MaterialDatePicker.todayInUtcMilliseconds())
                .build();
        picker.addOnPositiveButtonClickListener(selection -> {
            sale.date = selection == null ? System.currentTimeMillis() : selection;
            b.date.setText(Dates.formatDate(sale.date));
        });
        picker.show(getSupportFragmentManager(), "sale_date");
    }

    private void updateTotal() {
        double qty = Ui.number(b.qty);
        double price = Ui.number(b.price);
        double cost = Ui.number(b.cost);
        double discount = Ui.number(b.discount);
        double total = qty * price - discount;
        double profit = qty * (price - cost) - discount;
        b.totalPreview.setText("Итого: " + Money.format(total) + "\nПрибыль: " + Money.format(profit));
    }

    private int statusIndex() {
        String value = Ui.text(b.status);
        for (int i = 0; i < Sale.STATUS_NAMES.length; i++) {
            if (Sale.STATUS_NAMES[i].equalsIgnoreCase(value)) return i;
        }
        return Sale.STATUS_PAID;
    }

    private void save() {
        if (selectedProduct == null && sale.productId == null) {
            Ui.snack(b.getRoot(), "Выберите товар");
            return;
        }
        double qty = Ui.number(b.qty);
        if (qty <= 0) {
            b.qty.setError(getString(R.string.required_field));
            return;
        }

        if (selectedClient != null) {
            sale.clientId = selectedClient.id;
            sale.clientName = selectedClient.title();
        }
        if (selectedProduct != null) {
            sale.productId = selectedProduct.id;
            sale.productName = selectedProduct.title();
        }
        sale.qty = qty;
        sale.unitPrice = Ui.number(b.price);
        sale.unitCost = Ui.number(b.cost);
        sale.discount = Ui.number(b.discount);
        sale.note = Ui.text(b.note);
        sale.status = statusIndex();

        // Новая продажа уменьшает остаток — и в телефоне, и на сайте.
        // Отменённая не уменьшает: так же поступает сайт.
        repo.saveSale(sale, !editing, id -> pushToSite());
    }

    /**
     * Отправить продажу на сайт: там она становится заказом и видна в админке.
     * Если сайт не подключён или связи нет — продажа остаётся в телефоне
     * и уйдёт при следующей синхронизации.
     */
    private void pushToSite() {
        if (!Prefs.serverReady(this)) {
            finish();
            return;
        }
        new SyncManager(this).pushSalesNow(result -> {
            if (!result.ok()) {
                Toast.makeText(this, result.error, Toast.LENGTH_LONG).show();
            }
            finish();
        });
    }
}
