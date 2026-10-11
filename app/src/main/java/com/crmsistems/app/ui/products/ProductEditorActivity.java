package com.crmsistems.app.ui.products;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.crmsistems.app.R;
import com.crmsistems.app.data.Repository;
import com.crmsistems.app.data.entity.Product;
import com.crmsistems.app.databinding.ActivityProductEditorBinding;
import com.crmsistems.app.ui.common.SimpleTextWatcher;
import com.crmsistems.app.util.Money;
import com.crmsistems.app.util.Ui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Полная карточка товара: создание и редактирование. */
public class ProductEditorActivity extends AppCompatActivity {

    public static final String EXTRA_ID = "product_id";

    private static final String[] UNITS = {"шт", "кг", "г", "л", "м", "м²", "упак", "усл", "час"};

    private ActivityProductEditorBinding b;
    private Repository repo;
    private Product product = new Product();

    private final ActivityResultLauncher<String[]> pickPhoto =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                try {
                    getContentResolver().takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException ignored) {
                }
                product.photoUri = uri.toString();
                product.photoUploaded = false; // новое фото — нужно залить на сайт
                showPhoto();
            });

    public static Intent intent(Context context, long id) {
        return new Intent(context, ProductEditorActivity.class).putExtra(EXTRA_ID, id);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityProductEditorBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        Ui.applyInsets(b.root, b.appbarInc.getRoot(), b.bottomBar);

        repo = Repository.get(this);
        long id = getIntent().getLongExtra(EXTRA_ID, 0);

        b.appbarInc.toolbar.setNavigationOnClickListener(v -> finish());
        b.appbarInc.toolbar.setTitle(id == 0 ? R.string.add_product : R.string.product_card);
        if (id != 0) {
            b.appbarInc.toolbar.inflateMenu(R.menu.menu_editor);
            b.appbarInc.toolbar.setOnMenuItemClickListener(this::onMenu);
        }

        b.unit.setSimpleItems(UNITS);
        loadCategories();

        b.pickPhoto.setOnClickListener(v -> pickPhoto.launch(new String[]{"image/*"}));
        b.clearPhoto.setOnClickListener(v -> {
            product.photoUri = "";
            product.serverImage = ""; // фото убрано — на сайте тоже убираем
            product.photoUploaded = false;
            showPhoto();
        });
        b.save.setOnClickListener(v -> save());

        SimpleTextWatcher margin = new SimpleTextWatcher() {
            @Override
            public void onChanged(String text) {
                updateMarginHint();
            }
        };
        b.price.addTextChangedListener(margin);
        b.cost.addTextChangedListener(margin);

        if (id == 0) {
            b.unit.setText(UNITS[0], false);
            updateMarginHint();
        } else {
            repo.query(() -> repo.db().productDao().getById(id), loaded -> {
                if (loaded == null) {
                    finish();
                    return;
                }
                product = loaded;
                fill();
            });
        }
    }

    private boolean onMenu(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_delete) {
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

    private void loadCategories() {
        repo.query(() -> repo.db().productDao().getAll(), products -> {
            Set<String> categories = new LinkedHashSet<>();
            categories.add("Основная");
            if (products != null) {
                for (Product p : products) {
                    if (p.category != null && !p.category.trim().isEmpty()) categories.add(p.category);
                }
            }
            List<String> list = new ArrayList<>(categories);
            b.category.setSimpleItems(list.toArray(new String[0]));
        });
    }

    private void fill() {
        b.name.setText(product.name);
        b.sku.setText(product.sku);
        b.category.setText(product.category, false);
        b.unit.setText(product.unit.isEmpty() ? UNITS[0] : product.unit, false);
        b.price.setText(Money.plain(product.price));
        b.cost.setText(Money.plain(product.cost));
        b.stock.setText(Money.plain(product.stock));
        b.minStock.setText(Money.plain(product.minStock));
        b.barcode.setText(product.barcode);
        b.description.setText(product.description);
        b.archived.setChecked(product.archived);
        showPhoto();
        updateMarginHint();
    }

    private void showPhoto() {
        if (product.photoUri == null || product.photoUri.isEmpty()) {
            if (product.serverImage != null && !product.serverImage.isEmpty()) {
                // Локальной копии нет, но на сайте фото есть — покажем его.
                b.photo.setPadding(0, 0, 0, 0);
                b.photo.setImageTintList(null);
                b.photo.setImageResource(R.drawable.ic_products);
                com.crmsistems.app.util.ImageLoader.load(b.photo,
                        com.crmsistems.app.util.Prefs.serverUrl(this) + product.serverImage);
            } else {
                int pad = Ui.dp(this, 24);
                b.photo.setPadding(pad, pad, pad, pad);
                b.photo.setImageResource(R.drawable.ic_products);
            }
        } else {
            b.photo.setPadding(0, 0, 0, 0);
            b.photo.setImageTintList(null);
            try {
                b.photo.setImageURI(Uri.parse(product.photoUri));
            } catch (Exception e) {
                b.photo.setImageResource(R.drawable.ic_products);
            }
        }
    }

    private void updateMarginHint() {
        double price = Ui.number(b.price);
        double cost = Ui.number(b.cost);
        if (price <= 0) {
            b.marginHint.setText("");
            return;
        }
        double profit = price - cost;
        b.marginHint.setText("Прибыль с единицы: " + Money.format(profit)
                + " · наценка " + Money.percent(profit / price * 100));
    }

    private void save() {
        String name = Ui.text(b.name);
        if (name.isEmpty()) {
            b.name.setError(getString(R.string.required_field));
            b.name.requestFocus();
            return;
        }
        product.name = name;
        product.sku = Ui.text(b.sku);
        product.category = Ui.text(b.category);
        product.unit = Ui.text(b.unit).isEmpty() ? UNITS[0] : Ui.text(b.unit);
        product.price = Ui.number(b.price);
        product.cost = Ui.number(b.cost);
        product.stock = Ui.number(b.stock);
        product.minStock = Ui.number(b.minStock);
        product.barcode = Ui.text(b.barcode);
        product.description = Ui.text(b.description);
        product.archived = b.archived.isChecked();

        repo.saveProduct(product, id -> {
            // Сразу уходим на сайт: карточка и фото применяются к витрине без кнопки.
            if (com.crmsistems.app.util.Prefs.serverReady(this)) {
                new com.crmsistems.app.net.SyncManager(this).pushProduct(product, r -> {
                    // Если связи нет — правка сохранена в очередь офлайн. Сообщаем
                    // тостом, потому что экран карточки сразу закрывается.
                    if (!r.ok()) {
                        android.widget.Toast.makeText(this, r.summary(),
                                android.widget.Toast.LENGTH_LONG).show();
                    }
                    finish();
                });
            } else {
                finish();
            }
        });
    }
}
