package com.crmsistems.app.ui.shops;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.crmsistems.app.MainActivity;
import com.crmsistems.app.R;
import com.crmsistems.app.data.AppDatabase;
import com.crmsistems.app.databinding.ActivityShopsBinding;
import com.crmsistems.app.databinding.ViewSettingRowBinding;
import com.crmsistems.app.ui.deploy.DeployActivity;
import com.crmsistems.app.util.AppIcons;
import com.crmsistems.app.util.Hints;
import com.crmsistems.app.util.ShopBranding;
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

    /** Магазин, для которого выбирают логотип в галерее. */
    private int logoFor;

    private final ActivityResultLauncher<PickVisualMediaRequest> pickLogo =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), this::onLogoPicked);

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
        b.appIcon.setOnClickListener(v -> chooseAppIcon());
        Hints.label(b.appIconHint, R.string.help_app_icon);
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
            Bitmap logo = ShopBranding.logo(this, shop.id);
            if (logo != null) {
                row.rowIcon.setImageBitmap(logo);
                row.rowIcon.setImageTintList(null);
                row.rowIcon.setPadding(0, 0, 0, 0);
                row.rowIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);
                row.rowIcon.setClipToOutline(true);
            } else {
                row.rowIcon.setImageResource(shop.id == active ? R.drawable.ic_check : R.drawable.ic_products);
            }
            // Нажатие на открытый магазин — его настройки, на другой — переход.
            row.rowRoot.setOnClickListener(v -> {
                if (shop.id != active) confirmSwitch(shop);
                else actions(shop);
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
        boolean hasLogo = ShopBranding.logo(this, shop.id) != null;
        String[] items = {
                getString(R.string.shops_rename),
                getString(hasLogo ? R.string.shops_logo_change : R.string.shops_logo_set),
                getString(R.string.shops_pin),
                getString(hasLogo ? R.string.shops_logo_remove : R.string.shops_delete),
                getString(R.string.shops_delete)};
        // «Убрать логотип» показываем, только когда он есть.
        String[] shown = hasLogo ? items : new String[]{items[0], items[1], items[2], items[4]};
        new MaterialAlertDialogBuilder(this)
                .setTitle(shop.name)
                .setItems(shown, (d, which) -> {
                    String item = shown[which];
                    if (item.equals(items[0])) askName(shop);
                    else if (item.equals(items[1])) pickLogoFor(shop);
                    else if (item.equals(items[2])) pin(shop);
                    else if (hasLogo && item.equals(items[3])) {
                        ShopBranding.removeLogo(this, shop.id);
                        render();
                    } else confirmDelete(shop);
                })
                .show();
    }

    // ------------------------------------------------- логотип и ярлык

    private void pickLogoFor(Shops.Shop shop) {
        logoFor = shop.id;
        pickLogo.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build());
    }

    private void onLogoPicked(@Nullable Uri uri) {
        if (uri == null || logoFor == 0) return;
        try {
            ShopBranding.saveLogo(this, logoFor, uri);
            render();
            Ui.snack(b.getRoot(), getString(R.string.shops_logo_saved));
        } catch (Exception e) {
            Ui.snack(b.getRoot(), getString(R.string.shops_logo_failed));
        }
    }

    private void pin(Shops.Shop shop) {
        if (!ShopBranding.canPin(this) || !ShopBranding.pinShortcut(this, shop)) {
            Ui.snack(b.getRoot(), getString(R.string.shops_pin_unsupported));
        }
        // Дальше лаунчер сам покажет окно «Добавить на главный экран?».
    }

    // ------------------------------------------------- иконка приложения

    /** Сетка иконок на выбор; выбранная отмечена рамкой. */
    private void chooseAppIcon() {
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        int pad = Ui.dp(this, 12);
        grid.setPadding(pad, pad, pad, 0);
        AppIcons.Option current = AppIcons.current(this);
        AlertDialog[] dialog = new AlertDialog[1];
        for (AppIcons.Option o : AppIcons.ALL) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(pad / 2, pad / 2, pad / 2, pad);
            cell.setBackgroundResource(android.R.drawable.list_selector_background);

            ImageView img = new ImageView(this);
            int size = Ui.dp(this, 56);
            img.setLayoutParams(new LinearLayout.LayoutParams(size, size));
            img.setBackgroundResource(R.drawable.shape_circle);
            img.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, o.color)));
            img.setImageResource(o.foreground);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            if (o == current) {
                img.setForeground(ContextCompat.getDrawable(this, R.drawable.ring_selected));
            }
            cell.addView(img);

            TextView label = new TextView(this);
            label.setText(o.label);
            label.setGravity(Gravity.CENTER);
            label.setTextSize(12);
            label.setPadding(0, Ui.dp(this, 4), 0, 0);
            cell.addView(label);

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width = 0;
            cell.setLayoutParams(lp);
            cell.setOnClickListener(v -> {
                AppIcons.apply(this, o);
                if (dialog[0] != null) dialog[0].dismiss();
                Ui.snack(b.getRoot(), getString(R.string.app_icon_changed, o.label));
            });
            grid.addView(cell);
        }
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(grid);
        dialog[0] = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.app_icon_title)
                .setView(scroll)
                .setNegativeButton(R.string.cancel, null)
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
                        ShopBranding.refreshShortcut(this, shop.id);
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
                    if (Shops.remove(this, shop.id)) ShopBranding.disableShortcut(this, shop.id);
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
    public static void restart(Context c, boolean openDeploy) {
        Intent main = new Intent(c, MainActivity.class);
        Intent intent = Intent.makeRestartActivityTask(main.getComponent());
        intent.putExtra(EXTRA_OPEN_DEPLOY, openDeploy);
        c.startActivity(intent);
        Runtime.getRuntime().exit(0);
    }
}
