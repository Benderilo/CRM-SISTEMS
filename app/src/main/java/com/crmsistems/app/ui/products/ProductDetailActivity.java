package com.crmsistems.app.ui.products;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.crmsistems.app.R;
import com.crmsistems.app.data.Repository;
import com.crmsistems.app.data.entity.OrderEntity;
import com.crmsistems.app.data.entity.Product;
import com.crmsistems.app.data.entity.Sale;
import com.crmsistems.app.databinding.ActivityProductDetailBinding;
import com.crmsistems.app.ui.orders.OrderDetailActivity;
import com.crmsistems.app.ui.sales.SaleAdapter;
import com.crmsistems.app.ui.sales.OrderEditorActivity;
import com.crmsistems.app.ui.sales.SaleEditorActivity;
import com.crmsistems.app.ui.sales.SaleRow;
import com.crmsistems.app.util.Money;
import com.crmsistems.app.util.Ui;

import java.util.List;

/**
 * Карточка товара: показатели продаж и история сделок — вместе с позициями
 * заказов, в которых этот товар купили на сайте.
 */
public class ProductDetailActivity extends AppCompatActivity {

    public static final String EXTRA_ID = "product_id";

    private ActivityProductDetailBinding b;
    private Repository repo;
    private long productId;
    private Product product;

    private SaleAdapter adapter;
    private List<Sale> sales;
    private List<OrderEntity> orders;

    public static Intent intent(Context context, long id) {
        return new Intent(context, ProductDetailActivity.class).putExtra(EXTRA_ID, id);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityProductDetailBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        Ui.applyInsets(b.root, b.appbarInc.getRoot(), b.bottomBar);

        repo = Repository.get(this);
        productId = getIntent().getLongExtra(EXTRA_ID, 0);

        b.appbarInc.toolbar.setTitle(R.string.product_card);
        b.appbarInc.toolbar.setNavigationOnClickListener(v -> finish());
        b.appbarInc.toolbar.inflateMenu(R.menu.menu_detail);
        b.appbarInc.toolbar.setOnMenuItemClickListener(this::onMenu);

        b.statSold.label.setText("Продано");
        b.statRevenue.label.setText(R.string.kpi_revenue);
        b.statProfit.label.setText(R.string.kpi_profit);
        b.statStock.label.setText(R.string.product_stock);

        adapter = new SaleAdapter(SaleAdapter.TITLE_CLIENT, this::openRow);
        b.salesList.setLayoutManager(new LinearLayoutManager(this));
        b.salesList.setAdapter(adapter);

        b.sell.setOnClickListener(v ->
                startActivity(OrderEditorActivity.intent(this, 0, productId)));

        repo.product(productId).observe(this, loaded -> {
            if (loaded == null) {
                finish();
                return;
            }
            product = loaded;
            bind(loaded);
            // Позиции заказов ищем по id товара на сайте — он известен после синхронизации.
            showSales();
        });

        repo.salesOfProduct(productId).observe(this, list -> {
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
        startActivity(new Intent(this, OrderDetailActivity.class)
                .putExtra(OrderDetailActivity.EXTRA_ORDER, row.order));
    }

    private void showSales() {
        List<SaleRow> rows = SaleRow.forProduct(sales, orders,
                product == null ? 0 : product.serverId,
                product == null ? "" : product.name);
        adapter.submitList(rows);
        b.salesEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        bindStats(rows);
    }

    private boolean onMenu(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_edit) {
            startActivity(ProductEditorActivity.intent(this, productId));
            return true;
        }
        if (id == R.id.action_delete && product != null) {
            new AlertDialog.Builder(this)
                    .setTitle(product.title())
                    .setMessage(R.string.confirm_delete)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.delete, (d, w) ->
                            repo.deleteProduct(product, this::finish))
                    .show();
            return true;
        }
        return false;
    }

    private void bind(Product p) {
        b.name.setText(p.title());
        StringBuilder sub = new StringBuilder();
        if (!p.sku.isEmpty()) sub.append("Артикул ").append(p.sku);
        if (!p.category.isEmpty()) {
            if (sub.length() > 0) sub.append(" · ");
            sub.append(p.category);
        }
        if (p.archived) {
            if (sub.length() > 0) sub.append(" · ");
            sub.append("в архиве");
        }
        b.subtitle.setText(sub.toString());
        b.price.setText(Money.format(p.price));
        b.priceDetails.setText("Себестоимость " + Money.format(p.cost)
                + " · наценка " + Money.percent(p.marginPercent())
                + (p.barcode.isEmpty() ? "" : " · штрихкод " + p.barcode));
        b.description.setText(p.description.isEmpty() ? "Описание не заполнено" : p.description);
        b.statStock.value.setText(Money.qty(p.stock) + " " + p.unit);

        com.crmsistems.app.util.Photos.thumb(b.photo, p.photoUri, p.serverImage,
                com.crmsistems.app.util.Prefs.serverUrl(this));
    }

    private void bindStats(List<SaleRow> rows) {
        double qty = 0, revenue = 0, profit = 0;
        for (SaleRow row : rows) {
            if (!row.counted()) continue;
            qty += row.qty();
            revenue += row.total();
            profit += row.profit();
        }
        b.statSold.value.setText(Money.qty(qty));
        b.statRevenue.value.setText(Money.format(revenue));
        b.statProfit.value.setText(Money.format(profit));
    }
}
