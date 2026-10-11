package com.crmsistems.app.ui.sync;

import android.content.Intent;
import android.os.Bundle;
import android.widget.EditText;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.crmsistems.app.R;
import com.crmsistems.app.data.Repository;
import com.crmsistems.app.databinding.ActivityServerSettingsBinding;
import com.crmsistems.app.databinding.DialogWebAdminBinding;
import com.crmsistems.app.databinding.DialogWebSiteBinding;
import com.crmsistems.app.net.ServerApi;
import com.crmsistems.app.net.SyncManager;
import com.crmsistems.app.ui.orders.OrdersActivity;
import com.crmsistems.app.ui.site.ShopBrowserActivity;
import com.crmsistems.app.util.Dates;
import com.crmsistems.app.util.Hints;
import com.crmsistems.app.util.Prefs;
import com.crmsistems.app.util.Ui;

import org.json.JSONObject;

/**
 * Настройка связи с сайтом магазина: адрес, ключ доступа, ручная синхронизация
 * и переход к смене пароля от админки.
 * <p>
 * Сверху — две кнопки встроенного браузера: открыть сайт и открыть админ-панель.
 * Адреса и доступы спрашиваются при первом нажатии и запоминаются; кнопка
 * «Сбросить» забывает их — например, когда магазин переезжает на другой адрес.
 */
public class ServerSettingsActivity extends AppCompatActivity {

    private ActivityServerSettingsBinding b;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityServerSettingsBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        Ui.applyInsets(b.root, b.appbarInc.getRoot(), b.scroll);
        Hints.field(b.serverUrlLayout, R.string.help_server_url);
        Hints.field(b.apiKeyLayout, R.string.help_server_api_key);

        b.appbarInc.toolbar.setTitle(R.string.server_title);
        b.appbarInc.toolbar.setNavigationOnClickListener(v -> finish());

        b.serverUrl.setText(Prefs.serverUrl(this));
        b.apiKey.setText(Prefs.apiKey(this));

        b.check.setOnClickListener(v -> {
            save();
            checkConnection();
        });
        b.sync.setOnClickListener(v -> {
            save();
            runSync();
        });
        b.changePassword.setOnClickListener(v -> {
            save();
            startActivity(new Intent(this, AdminPasswordActivity.class));
        });
        b.openOrders.setOnClickListener(v -> {
            save();
            startActivity(new Intent(this, OrdersActivity.class));
        });

        b.openSite.setOnClickListener(v -> openSite());
        b.openAdmin.setOnClickListener(v -> openAdmin());
        b.resetWeb.setOnClickListener(v -> confirmResetWeb());

        showLastSync();
    }

    // ----------------------------------------------------- встроенный браузер

    /** Открыть сайт: адрес спрашиваем только при первом нажатии. */
    private void openSite() {
        String saved = Prefs.webSiteUrl(this);
        if (saved.isEmpty()) {
            askSiteUrl();
        } else {
            ShopBrowserActivity.start(this, saved, null, null);
        }
    }

    /** Диалог ввода адреса сайта — показываем один раз, дальше запоминаем. */
    private void askSiteUrl() {
        DialogWebSiteBinding d = DialogWebSiteBinding.inflate(getLayoutInflater());
        // Подставляем адрес из настроек подключения — скорее всего, это он и есть.
        d.siteUrl.setText(Prefs.serverUrl(this));

        new AlertDialog.Builder(this)
                .setTitle(R.string.web_open_site)
                .setView(d.getRoot())
                .setPositiveButton(R.string.ok, (dialog, w) -> {
                    String url = ShopBrowserActivity.normalizeUrl(Ui.text((EditText) d.siteUrl));
                    if (url.isEmpty()) {
                        Ui.snack(b.getRoot(), getString(R.string.web_url_empty));
                        return;
                    }
                    Prefs.setWebSiteUrl(this, url);
                    ShopBrowserActivity.start(this, url, null, null);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Открыть админ-панель: при первом запуске спрашиваем адрес и доступы. */
    private void openAdmin() {
        String url = Prefs.webAdminUrl(this);
        String login = Prefs.webAdminLogin(this);
        String pass = Prefs.webAdminPass(this);
        if (url.isEmpty() || login.isEmpty() || pass.isEmpty()) {
            askAdminCredentials(url);
        } else {
            ShopBrowserActivity.start(this, url, login, pass);
        }
    }

    /** Диалог ввода адреса админки, логина и пароля — запоминается до сброса. */
    private void askAdminCredentials(String currentUrl) {
        DialogWebAdminBinding d = DialogWebAdminBinding.inflate(getLayoutInflater());
        if (currentUrl != null && !currentUrl.isEmpty()) d.url.setText(currentUrl);
        else if (!Prefs.serverUrl(this).isEmpty()) d.url.setText(Prefs.serverUrl(this) + "/admin");
        d.login.setText(Prefs.webAdminLogin(this));

        new AlertDialog.Builder(this)
                .setTitle(R.string.web_open_admin)
                .setView(d.getRoot())
                .setPositiveButton(R.string.ok, (dialog, w) -> {
                    String url = ShopBrowserActivity.normalizeUrl(Ui.text((EditText) d.url));
                    String login = Ui.text((EditText) d.login);
                    String pass = Ui.text((EditText) d.pass);
                    if (url.isEmpty() || login.isEmpty() || pass.isEmpty()) {
                        Ui.snack(b.getRoot(), getString(R.string.web_url_empty));
                        return;
                    }
                    Prefs.setWebAdminUrl(this, url);
                    Prefs.setWebAdminLogin(this, login);
                    Prefs.setWebAdminPass(this, pass);
                    ShopBrowserActivity.start(this, url, login, pass);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Забыть адреса и доступы — перед этим спрашиваем подтверждение. */
    private void confirmResetWeb() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.web_reset)
                .setMessage(R.string.web_reset_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, w) -> {
                    Prefs.clearWeb(this);
                    Ui.snack(b.getRoot(), getString(R.string.web_reset_done));
                })
                .show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        save();
    }

    private void save() {
        Prefs.setServerUrl(this, Ui.text(b.serverUrl));
        Prefs.setApiKey(this, Ui.text(b.apiKey));
    }

    private void showLastSync() {
        long last = Prefs.lastSync(this);
        b.lastSync.setText(last == 0
                ? getString(R.string.server_never_synced)
                : getString(R.string.server_last_sync, Dates.formatDateTime(last)));
    }

    /** Проверка адреса и ключа — заодно подтягиваем логин администратора. */
    private void checkConnection() {
        if (!Prefs.serverReady(this)) {
            b.status.setText(R.string.server_not_configured);
            return;
        }
        b.status.setText(R.string.server_syncing);
        b.progress.setVisibility(android.view.View.VISIBLE);

        Repository repo = Repository.get(this);
        ServerApi api = new ServerApi(this);
        repo.io().execute(() -> {
            String status;
            String admin = null;
            try {
                JSONObject j = api.ping();
                status = getString(R.string.server_status_ok,
                        j.optInt("products"), j.optInt("clients"), j.optInt("orders"));
                try {
                    JSONObject a = api.adminInfo().getJSONObject("admin");
                    admin = getString(R.string.server_admin_login, a.optString("login"));
                } catch (Exception ignored) {
                    // Логин админа — приятное дополнение, без него экран работает.
                }
            } catch (ServerApi.ApiException e) {
                status = e.getMessage();
            }
            final String s = status;
            final String a = admin;
            runOnUiThread(() -> {
                b.progress.setVisibility(android.view.View.GONE);
                b.status.setText(s);
                if (a != null) b.adminLogin.setText(a);
            });
        });
    }

    private void runSync() {
        if (!Prefs.serverReady(this)) {
            Ui.snack(b.getRoot(), getString(R.string.server_not_configured));
            return;
        }
        b.progress.setVisibility(android.view.View.VISIBLE);
        b.sync.setEnabled(false);
        b.status.setText(R.string.server_syncing);

        new SyncManager(this).syncAll(result -> {
            b.progress.setVisibility(android.view.View.GONE);
            b.sync.setEnabled(true);
            b.status.setText(result.summary());
            Ui.snack(b.getRoot(), result.summary());
            showLastSync();
        });
    }
}
