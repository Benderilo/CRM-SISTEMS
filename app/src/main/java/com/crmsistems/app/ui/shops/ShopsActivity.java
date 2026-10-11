package com.crmsistems.app.ui.shops;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.crmsistems.app.MainActivity;
import com.crmsistems.app.R;
import com.crmsistems.app.data.AppDatabase;
import com.crmsistems.app.databinding.ActivityShopsBinding;
import com.crmsistems.app.databinding.ViewSettingRowBinding;
import com.crmsistems.app.ui.deploy.DeployActivity;
import com.crmsistems.app.util.Hints;
import com.crmsistems.app.util.Shops;
import com.crmsistems.app.util.Ui;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Список магазинов на телефоне: переключиться, добавить, переименовать, удалить.
 * <p>
 * У каждого магазина своя база и свои настройки, поэтому при переключении
 * приложение перезапускается — так все экраны гарантированно берут данные
 * нового магазина, а не держат в памяти старые.
 */
public class ShopsActivity extends AppCompatActivity {

    /** Открыть MainActivity сразу с экраном установки сервера. */
    public static final String EXTRA_OPEN_DEPLOY = "open_deploy";

    private ActivityShopsBinding b;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityShopsBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        Ui.applyInsets(b.root, b.appbarInc.getRoot(), b.scroll);
        b.appbarInc.toolbar.setTitle(R.string.shops_title);
        b.appbarInc.toolbar.setNavigationOnClickListener(v -> finish());

        Hints.label(b.intro, R.string.help_shops);
        b.add.setOnClickListener(v -> askName(null));
        b.deploy.setOnClickListener(v -> startActivity(new Intent(this, DeployActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        b.list.removeAllViews();
        int active = Shops.activeId(this);
        for (Shops.Shop shop : Shops.list(this)) {
            ViewSettingRowBinding row = ViewSettingRowBinding.inflate(getLayoutInflater(), b.list, true);
            row.rowTitle.setText(shop.name);
            String url = serverUrlOf(this, shop.id);
            String where = url.isEmpty() ? getString(R.string.shops_no_server) : url;
            row.rowSubtitle.setText(shop.id == active ? getString(R.string.shops_active, where) : where);
            row.rowIcon.setImageResource(shop.id == active ? R.drawable.ic_check : R.drawable.ic_products);
            row.rowRoot.setOnClickListener(v -> {
                if (shop.id != active) confirmSwitch(shop);
            });
            row.rowRoot.setOnLongClickListener(v -> {
                actions(shop);
                return true;
            });
        }
    }

    /** Адрес сервера магазина — из его собственного файла настроек. */
    static String serverUrlOf(Context c, int id) {
        return c.getSharedPreferences(Shops.prefsFile(id), Context.MODE_PRIVATE)
                .getString("server_url", "");
    }

    private void confirmSwitch(Shops.Shop shop) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.shops_switch_title, shop.name))
                .setMessage(R.string.shops_switch_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.shops_switch, (d, w) -> switchTo(shop.id, false))
                .show();
    }

    private void actions(Shops.Shop shop) {
        String[] items = {getString(R.string.shops_rename), getString(R.string.shops_delete)};
        new MaterialAlertDialogBuilder(this)
                .setTitle(shop.name)
                .setItems(items, (d, which) -> {
                    if (which == 0) askName(shop);
                    else confirmDelete(shop);
                })
                .show();
    }

    /** Ввод названия: новый магазин (shop == null) или переименование. */
    private void askName(@Nullable Shops.Shop shop) {
        EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setHint(R.string.shops_name_hint);
        if (shop != null) field.setText(shop.name);
        FrameLayout box = new FrameLayout(this);
        int pad = Ui.dp(this, 20);
        box.setPadding(pad, Ui.dp(this, 8), pad, 0);
        box.addView(field);

        new MaterialAlertDialogBuilder(this)
                .setTitle(shop == null ? R.string.shops_add : R.string.shops_rename)
                .setView(box)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String name = Ui.text(field);
                    if (shop != null) {
                        Shops.rename(this, shop.id, name);
                        render();
                        return;
                    }
                    int id = Shops.add(this, name);
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.shops_added)
                            .setMessage(R.string.shops_added_message)
                            .setNegativeButton(R.string.shops_later, (d2, w2) -> render())
                            .setPositiveButton(R.string.shops_open_and_deploy, (d2, w2) -> switchTo(id, true))
                            .show();
                })
                .show();
    }

    private void confirmDelete(Shops.Shop shop) {
        if (Shops.list(this).size() < 2) {
            Ui.snack(b.getRoot(), getString(R.string.shops_last));
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.shops_delete_title, shop.name))
                .setMessage(R.string.shops_delete_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    boolean wasActive = shop.id == Shops.activeId(this);
                    if (wasActive) AppDatabase.reset();
                    Shops.remove(this, shop.id);
                    if (wasActive) restart(this, false);
                    else render();
                })
                .show();
    }

    private void switchTo(int id, boolean openDeploy) {
        AppDatabase.reset();
        Shops.setActive(this, id);
        restart(this, openDeploy);
    }

    /**
     * Перезапуск процесса: базы, настройки и кэши в памяти принадлежат
     * прежнему магазину, проще начать с чистого листа, чем чистить их по одному.
     */
    static void restart(Context c, boolean openDeploy) {
        Intent main = new Intent(c, MainActivity.class);
        Intent intent = Intent.makeRestartActivityTask(main.getComponent());
        intent.putExtra(EXTRA_OPEN_DEPLOY, openDeploy);
        c.startActivity(intent);
        Runtime.getRuntime().exit(0);
    }
}
