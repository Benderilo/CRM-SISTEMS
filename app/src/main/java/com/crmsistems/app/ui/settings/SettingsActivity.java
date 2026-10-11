package com.crmsistems.app.ui.settings;

import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.crmsistems.app.R;
import com.crmsistems.app.data.Repository;
import com.crmsistems.app.databinding.ActivitySettingsBinding;
import com.crmsistems.app.databinding.ViewSettingRowBinding;
import com.crmsistems.app.io.DataExporter;
import com.crmsistems.app.net.ServerApi;
import com.crmsistems.app.net.OrderCheckWorker;
import com.crmsistems.app.ui.orders.OrderNotifier;
import com.crmsistems.app.util.Dates;
import com.crmsistems.app.util.Money;
import com.crmsistems.app.util.Prefs;
import com.crmsistems.app.util.Ui;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** Настройки приложения и обмен данными с компьютером. */
public class SettingsActivity extends AppCompatActivity {

    public static final String EXTRA_SCROLL_TO_DATA = "scroll_to_data";

    private static final String MIME_XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private ActivitySettingsBinding b;
    private Repository repo;

    /** Какую таблицу выгружаем/загружаем в CSV. */
    private int pendingTable = DataExporter.TABLE_PRODUCTS;
    private boolean importingJson;

    private final ActivityResultLauncher<String> createXlsx =
            registerForActivityResult(new ActivityResultContracts.CreateDocument(MIME_XLSX), uri -> {
                if (uri != null) run(() -> DataExporter.exportXlsx(this, uri), R.string.export_done);
            });

    private final ActivityResultLauncher<String> createCsv =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("text/csv"), uri -> {
                if (uri != null) {
                    run(() -> DataExporter.exportCsv(this, uri, pendingTable), R.string.export_done);
                }
            });

    private final ActivityResultLauncher<String> createJson =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"), uri -> {
                if (uri != null) run(() -> DataExporter.exportJson(this, uri), R.string.export_done);
            });

    private final ActivityResultLauncher<String[]> openFile =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                if (importingJson) {
                    restore(uri);
                } else {
                    run(() -> DataExporter.importCsv(this, uri, pendingTable), R.string.import_done);
                }
            });

    private final ActivityResultLauncher<String[]> openXlsx =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) confirmImportXlsx(uri);
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        Ui.applyInsets(b.root, b.appbarInc.getRoot(), b.scroll);

        repo = Repository.get(this);

        b.appbarInc.toolbar.setTitle(R.string.nav_settings);
        b.appbarInc.toolbar.setNavigationOnClickListener(v -> finish());

        setupGeneral();
        setupDataRows();

        if (getIntent().getBooleanExtra(EXTRA_SCROLL_TO_DATA, false)) {
            b.scroll.post(() -> b.scroll.smoothScrollTo(0, b.dataTitle.getTop()));
        }
    }

    // ---------------------------------------------------------------- основное

    private void setupGeneral() {
        b.company.setText(Prefs.company(this));
        b.currency.setText(Prefs.currency(this));
        b.lowStock.setChecked(Prefs.lowStockAlert(this));

        int theme = Prefs.theme(this);
        b.themeGroup.check(theme == 1 ? R.id.themeLight : theme == 2 ? R.id.themeDark : R.id.themeSystem);
        b.themeGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            int mode = checkedId == R.id.themeLight ? 1 : checkedId == R.id.themeDark ? 2 : 0;
            if (mode != Prefs.theme(this)) Prefs.setTheme(this, mode);
        });

        b.lowStock.setOnCheckedChangeListener((buttonView, isChecked) ->
                Prefs.setLowStockAlert(this, isChecked));

        setupOrderAlerts();
    }

    /**
     * Сообщения о заказах с сайта. Второй переключатель работает только при
     * включённом первом: напоминать не о чем, если сообщения выключены совсем.
     */
    private void setupOrderAlerts() {
        b.orderAlerts.setChecked(Prefs.orderAlerts(this));
        b.orderReminder.setChecked(Prefs.orderReminder(this));
        b.orderReminder.setEnabled(Prefs.orderAlerts(this));

        b.orderAlerts.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Prefs.setOrderAlerts(this, isChecked);
            b.orderReminder.setEnabled(isChecked);
            // Сообщения выключили — убираем и то, что уже висит в шторке.
            if (!isChecked) OrderNotifier.cancel(this);
            OrderCheckWorker.apply(this);
        });

        b.orderReminder.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Prefs.setOrderReminder(this, isChecked);
            OrderCheckWorker.apply(this);
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        Prefs.setCompany(this, Ui.text(b.company));
        String currency = Ui.text(b.currency);
        Prefs.setCurrency(this, currency.isEmpty() ? "₽" : currency);
        Money.setCurrency(Prefs.currency(this));
    }

    // ------------------------------------------------------------ обмен данными

    private void setupDataRows() {
        row(b.rowExportXlsx, R.drawable.ic_export, R.string.export_xlsx, R.string.export_xlsx_desc,
                v -> createXlsx.launch(fileName("xlsx")));

        row(b.rowImportXlsx, R.drawable.ic_import, R.string.import_xlsx, R.string.import_xlsx_desc,
                // Часть файловых менеджеров отдаёт .xlsx с типом octet-stream,
                // поэтому в списке разрешённых типов есть и «любой файл».
                v -> openXlsx.launch(new String[]{MIME_XLSX, "application/vnd.ms-excel",
                        "application/octet-stream", "*/*"}));

        row(b.rowMore, R.drawable.ic_sort, R.string.data_more, R.string.data_more_desc,
                v -> toggleMore());

        row(b.rowExportCsv, R.drawable.ic_export, R.string.export_csv, R.string.export_csv_desc,
                v -> chooseTable(table -> {
                    pendingTable = table;
                    createCsv.launch(DataExporter.TABLE_NAMES[table] + "_"
                            + Dates.formatIso(System.currentTimeMillis()) + ".csv");
                }));

        row(b.rowImportCsv, R.drawable.ic_import, R.string.import_csv, R.string.import_csv_desc,
                v -> chooseTable(table -> {
                    pendingTable = table;
                    importingJson = false;
                    openFile.launch(new String[]{"text/csv", "text/comma-separated-values",
                            "text/plain", "application/csv", "*/*"});
                }));

        row(b.rowBackup, R.drawable.ic_backup, R.string.backup_json, R.string.backup_json_desc,
                v -> createJson.launch(fileName("json")));

        row(b.rowRestore, R.drawable.ic_import, R.string.restore_json, R.string.restore_json_desc,
                v -> {
                    importingJson = true;
                    openFile.launch(new String[]{"application/json", "text/plain", "*/*"});
                });

        row(b.rowClearOrders, R.drawable.ic_delete, R.string.clear_orders,
                R.string.clear_orders_desc, v -> chooseOrdersScope());

        row(b.rowClear, R.drawable.ic_delete, R.string.clear_data, R.string.clear_data_desc,
                v -> confirmClear());

        // Опасные действия — подсвечиваем строки красным.
        int red = androidx.core.content.ContextCompat.getColor(this, R.color.expense);
        android.content.res.ColorStateList redTint =
                android.content.res.ColorStateList.valueOf(red);
        b.rowClear.rowTitle.setTextColor(red);
        b.rowClear.rowIcon.setImageTintList(redTint);
        b.rowClearOrders.rowTitle.setTextColor(red);
        b.rowClearOrders.rowIcon.setImageTintList(redTint);
    }

    private void row(ViewSettingRowBinding row, int icon, int title, int subtitle,
                     View.OnClickListener listener) {
        ImageView iconView = row.rowIcon;
        TextView titleView = row.rowTitle;
        TextView subtitleView = row.rowSubtitle;
        iconView.setImageResource(icon);
        titleView.setText(title);
        subtitleView.setText(subtitle);
        row.rowRoot.setOnClickListener(listener);
    }

    /** Показать/спрятать редкие форматы. */
    private void toggleMore() {
        boolean show = b.moreBox.getVisibility() != View.VISIBLE;
        b.moreBox.setVisibility(show ? View.VISIBLE : View.GONE);
        b.rowMore.rowSubtitle.setText(show ? R.string.data_more_hide : R.string.data_more_desc);
    }

    // --------------------------------------------------------- загрузка из Excel

    /**
     * Загрузка книги Excel: сначала спрашиваем, потом сами делаем резервную копию
     * и только затем правим базу. Так неудачный файл не оставит без данных.
     */
    private void confirmImportXlsx(Uri uri) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.import_xlsx)
                .setMessage(getString(R.string.import_xlsx_confirm, displayName(uri)))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (d, w) -> backupThenImportXlsx(uri))
                .show();
    }

    private void backupThenImportXlsx(Uri uri) {
        Ui.snack(b.getRoot(), getString(R.string.import_backup_making));
        repo.run(() -> {
            String error = null;
            try {
                DataExporter.autoBackup(this);
            } catch (Exception e) {
                e.printStackTrace();
                error = e.getMessage();
            }
            String err = error;
            b.getRoot().post(() -> {
                if (err == null) {
                    importXlsx(uri);
                } else {
                    // Копия не вышла — решает человек, а не приложение.
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.import_xlsx)
                            .setMessage(getString(R.string.import_backup_failed, err))
                            .setNegativeButton(R.string.cancel, null)
                            .setPositiveButton(R.string.ok, (d, w) -> importXlsx(uri))
                            .show();
                }
            });
        });
    }

    private void importXlsx(Uri uri) {
        Ui.snack(b.getRoot(), "Выполняется…");
        repo.run(() -> {
            String message;
            try {
                int[] counts = DataExporter.importXlsx(this, uri);
                message = getString(R.string.import_xlsx_result, counts[DataExporter.TABLE_PRODUCTS],
                        counts[DataExporter.TABLE_CLIENTS], counts[DataExporter.TABLE_SALES],
                        counts[DataExporter.TABLE_EXPENSES]);
            } catch (Exception e) {
                e.printStackTrace();
                message = getString(R.string.operation_failed) + ": " + e.getMessage();
            }
            String result = message;
            b.getRoot().post(() -> Ui.snack(b.getRoot(), result));
        });
    }

    /** Имя выбранного файла — показать в вопросе, чтобы было видно, что грузим. */
    private String displayName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (Exception ignored) {
            // Имя — приятная мелочь: не вышло, покажем адрес файла.
        }
        String path = uri.getLastPathSegment();
        return path == null ? uri.toString() : path;
    }

    private String fileName(String extension) {
        String company = Prefs.company(this).replaceAll("[^\\p{L}\\p{N}_ -]", "").trim();
        String prefix = company.isEmpty() ? "CRM" : company;
        return prefix + "_" + Dates.formatIso(System.currentTimeMillis()) + "." + extension;
    }

    private interface TableCallback {
        void onTable(int table);
    }

    private void chooseTable(TableCallback callback) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.choose_table)
                .setItems(DataExporter.TABLE_NAMES, (dialog, which) -> callback.onTable(which))
                .show();
    }

    private interface Job {
        void run() throws Exception;
    }

    /** Выполняем задачу в фоне и показываем результат. */
    private void run(Job job, int successMessage) {
        Ui.snack(b.getRoot(), "Выполняется…");
        repo.run(() -> {
            String message;
            try {
                job.run();
                message = getString(successMessage);
            } catch (Exception e) {
                e.printStackTrace();
                message = getString(R.string.operation_failed) + ": " + e.getMessage();
            }
            String result = message;
            b.getRoot().post(() -> Ui.snack(b.getRoot(), result));
        });
    }

    // --------------------------------------------------------- очистка заказов

    /**
     * Удаление заказов — два вопроса подряд: сначала «какие», потом «точно?».
     * Заказы живут на сайте, поэтому удаляет их сервер; он же перед этим
     * сохраняет копию рядом с базой, а склад не трогает.
     */
    private void chooseOrdersScope() {
        if (!Prefs.serverReady(this)) {
            Ui.snack(b.getRoot(), getString(R.string.server_not_configured));
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.clear_orders)
                .setItems(new CharSequence[]{
                        getString(R.string.clear_orders_done),
                        getString(R.string.clear_orders_all)
                }, (d, which) -> confirmPurge(which == 0))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void confirmPurge(boolean onlyDone) {
        androidx.appcompat.app.AlertDialog d = new MaterialAlertDialogBuilder(this)
                .setTitle(onlyDone ? R.string.clear_orders_done : R.string.clear_orders_all)
                .setMessage(onlyDone ? R.string.clear_orders_warn_done : R.string.clear_orders_warn_all)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.clear_continue, (dlg, w) -> confirmPurgeFinal(onlyDone))
                .show();
        tintDanger(d);
    }

    private void confirmPurgeFinal(boolean onlyDone) {
        androidx.appcompat.app.AlertDialog d = new MaterialAlertDialogBuilder(this)
                .setTitle(onlyDone ? R.string.clear_orders_done : R.string.clear_orders_all)
                .setMessage(R.string.clear_orders_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dlg, w) -> purgeOrders(onlyDone))
                .show();
        tintDanger(d);
    }

    private void purgeOrders(boolean onlyDone) {
        Ui.snack(b.getRoot(), "Выполняется…");
        ServerApi api = new ServerApi(this);
        repo.run(() -> {
            String message;
            try {
                org.json.JSONObject j = api.purgeOrders(onlyDone ? "done" : "all");
                // Кэш заказов в телефоне тоже чистим, иначе удалённые повисли бы
                // в списке до следующего обмена.
                repo.db().orderDao().clear();
                message = getString(R.string.clear_orders_done_result,
                        j.optInt("deleted"), j.optInt("left"));
            } catch (ServerApi.ApiException e) {
                message = e.getMessage();
            }
            String result = message;
            b.getRoot().post(() -> Ui.snack(b.getRoot(), result));
        });
    }

    /**
     * Очистка базы — два подтверждения подряд. Между ними приложение само делает
     * резервную копию в «Загрузки/CRMBackups», чтобы случайное нажатие не лишило данных.
     */
    private void confirmClear() {
        androidx.appcompat.app.AlertDialog d = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.clear_data)
                .setMessage(R.string.clear_warn1)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.clear_continue, (dlg, w) -> makeBackupThenAsk())
                .show();
        tintDanger(d);
    }

    /** Делаем резервную копию в фоне, затем показываем финальное подтверждение. */
    private void makeBackupThenAsk() {
        Ui.snack(b.getRoot(), getString(R.string.clear_backup_making));
        repo.run(() -> {
            String path = null;
            String error = null;
            try {
                path = DataExporter.autoBackup(this);
            } catch (Exception e) {
                e.printStackTrace();
                error = e.getMessage();
            }
            String p = path;
            String err = error;
            b.getRoot().post(() -> askFinalClear(p, err));
        });
    }

    private void askFinalClear(String backupPath, String backupError) {
        androidx.appcompat.app.AlertDialog d;
        if (backupPath != null) {
            d = new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.clear_data)
                    .setMessage(getString(R.string.clear_warn2, backupPath))
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.delete, (dlg, w) -> repo.clearAll(() ->
                            Ui.snack(b.getRoot(), getString(R.string.clear_done_with_backup, backupPath))))
                    .show();
        } else {
            d = new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.clear_data)
                    .setMessage(getString(R.string.clear_backup_failed, backupError))
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.delete, (dlg, w) -> repo.clearAll(() ->
                            Ui.snack(b.getRoot(), getString(R.string.deleted))))
                    .show();
        }
        tintDanger(d);
    }

    /** Кнопку подтверждения удаления — красным, чтобы её было не промахнуться. */
    private void tintDanger(androidx.appcompat.app.AlertDialog d) {
        int red = androidx.core.content.ContextCompat.getColor(this, R.color.expense);
        d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setTextColor(red);
    }

    private void restore(Uri uri) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.restore_json)
                .setMessage(R.string.restore_json_desc)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (d, w) ->
                        run(() -> DataExporter.importJson(this, uri), R.string.import_done))
                .show();
    }
}
