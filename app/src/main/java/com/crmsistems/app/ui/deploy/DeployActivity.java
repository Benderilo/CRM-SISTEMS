package com.crmsistems.app.ui.deploy;

import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.crmsistems.app.R;
import com.crmsistems.app.data.Repository;
import com.crmsistems.app.databinding.ActivityDeployBinding;
import com.crmsistems.app.net.ServerApi;
import com.crmsistems.app.net.ShopInstaller;
import com.crmsistems.app.net.SshClient;
import com.crmsistems.app.ui.common.SimpleTextWatcher;
import com.crmsistems.app.ui.site.ShopBrowserActivity;
import com.crmsistems.app.util.Hints;
import com.crmsistems.app.util.Prefs;
import com.crmsistems.app.util.Shops;
import com.crmsistems.app.util.Ui;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.security.SecureRandom;

/**
 * Установка магазина на свой VPS прямо с телефона.
 * <p>
 * Продавец вписывает IP и пароль сервера, название магазина и (по желанию)
 * домен — приложение подключается по SSH, заливает сервер из APK и запускает
 * server/install.sh: nginx, служба, HTTPS-сертификат, файрвол, ночные копии.
 * По окончании адрес сайта и ключ доступа записываются в настройки этого
 * магазина сами — ничего переписывать руками не нужно.
 * <p>
 * Пароль сервера не сохраняется: он нужен только на время установки.
 */
public class DeployActivity extends AppCompatActivity {

    private ActivityDeployBinding b;
    private ShopInstaller installer;
    private boolean busy;
    /** Пользователь сам правил короткое имя — больше не подставляем его из названия. */
    private boolean slugTouched;
    private boolean fillingSlug;
    private final StringBuilder log = new StringBuilder();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityDeployBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        Ui.applyInsets(b.root, b.appbarInc.getRoot(), b.scroll);
        b.appbarInc.toolbar.setTitle(R.string.deploy_title);
        b.appbarInc.toolbar.setNavigationOnClickListener(v -> finish());

        installer = new ShopInstaller(this);

        b.host.setText(Prefs.sshHost(this));
        b.port.setText(String.valueOf(Prefs.sshPort(this)));
        b.user.setText(Prefs.sshUser(this));
        b.shopName.setText(Shops.active(this).name);
        b.slug.setText(Prefs.slug(this));
        b.domain.setText(Prefs.domain(this));
        b.email.setText(Prefs.email(this));
        String login = Prefs.webAdminLogin(this);
        b.adminLogin.setText(login.isEmpty() ? "admin" : login);
        slugTouched = !Prefs.sshHost(this).isEmpty();

        hints();

        b.shopName.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void onChanged(String text) {
                if (slugTouched) return;
                String slug = slugOf(text);
                if (!slug.isEmpty()) {
                    fillingSlug = true;
                    b.slug.setText(slug);
                    fillingSlug = false;
                }
            }
        });
        b.slug.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void onChanged(String text) {
                if (!fillingSlug) slugTouched = true;
            }
        });

        String bundled = installer.bundledVersion();
        b.bundled.setText(bundled.isEmpty()
                ? getString(R.string.deploy_no_bundle)
                : getString(R.string.deploy_bundled, bundled));

        b.check.setOnClickListener(v -> check());
        b.install.setOnClickListener(v -> confirmInstall());
        b.update.setOnClickListener(v -> run(true));
        b.forgetKey.setOnClickListener(v -> {
            Prefs.setSshHostKey(this, "");
            b.forgetKey.setVisibility(View.GONE);
            Ui.snack(b.getRoot(), getString(R.string.deploy_key_forgotten));
        });
        b.openSite.setOnClickListener(v ->
                ShopBrowserActivity.start(this, Prefs.serverUrl(this), null, null));
    }

    /** Значки «?» у каждого поля: что вписывать и где это взять. */
    private void hints() {
        Hints.field(b.hostLayout, R.string.help_deploy_host);
        Hints.field(b.userLayout, R.string.help_deploy_user);
        Hints.field(b.portLayout, R.string.help_deploy_port);
        Hints.field(b.passwordLayout, R.string.help_deploy_password);
        Hints.field(b.shopNameLayout, R.string.help_deploy_shop_name);
        Hints.field(b.slugLayout, R.string.help_deploy_slug);
        Hints.field(b.domainLayout, R.string.help_deploy_domain);
        Hints.field(b.emailLayout, R.string.help_deploy_email);
        Hints.field(b.adminLoginLayout, R.string.help_deploy_admin_login);
        Hints.field(b.adminPassLayout, R.string.help_deploy_admin_pass);
        Hints.label(b.sectionServer, R.string.help_deploy_section_server);
        Hints.label(b.sectionShop, R.string.help_deploy_section_shop);
    }

    // ------------------------------------------------------------- ввод

    @Nullable
    private ShopInstaller.Params params(boolean needShop) {
        ShopInstaller.Params p = new ShopInstaller.Params();
        p.host = Ui.text(b.host).replaceFirst("^(ssh|https?)://", "").replaceAll("/.*$", "");
        p.user = Ui.text(b.user).isEmpty() ? "root" : Ui.text(b.user);
        p.password = b.password.getText() == null ? "" : b.password.getText().toString();
        try {
            p.port = Integer.parseInt(Ui.text(b.port));
        } catch (NumberFormatException e) {
            p.port = 22;
        }
        p.knownFingerprint = Prefs.sshHost(this).equals(p.host) ? Prefs.sshHostKey(this) : "";
        p.slug = Ui.text(b.slug).isEmpty() ? "shop" : Ui.text(b.slug);
        p.domain = Ui.text(b.domain).replaceFirst("^https?://", "").replaceAll("/.*$", "").toLowerCase();
        p.email = Ui.text(b.email);
        p.adminLogin = Ui.text(b.adminLogin).isEmpty() ? "admin" : Ui.text(b.adminLogin);
        p.adminPass = b.adminPass.getText() == null ? "" : b.adminPass.getText().toString();

        b.hostLayout.setError(null);
        b.passwordLayout.setError(null);
        b.slugLayout.setError(null);
        b.adminPassLayout.setError(null);
        if (p.host.isEmpty()) {
            b.hostLayout.setError(getString(R.string.deploy_err_host));
            return null;
        }
        if (p.password.isEmpty()) {
            b.passwordLayout.setError(getString(R.string.deploy_err_password));
            return null;
        }
        if (needShop) {
            if (!p.slug.matches("[a-z0-9][a-z0-9-]{0,30}")) {
                b.slugLayout.setError(getString(R.string.deploy_err_slug));
                return null;
            }
            if (!p.adminPass.isEmpty() && p.adminPass.length() < 8) {
                b.adminPassLayout.setError(getString(R.string.deploy_err_admin_pass));
                return null;
            }
        }
        return p;
    }

    // ------------------------------------------------------------ проверка

    private void check() {
        ShopInstaller.Params p = params(false);
        if (p == null || busy) return;
        setBusy(true);
        b.serverInfo.setVisibility(View.VISIBLE);
        b.serverInfo.setText(R.string.deploy_connecting);
        Repository.get(this).io().execute(() -> {
            try {
                ShopInstaller.ServerInfo info = installer.check(p);
                String text = getString(R.string.deploy_server_info, info.os, info.arch,
                        info.memory, info.disk, info.fingerprint)
                        + (info.installed.isEmpty()
                        ? getString(R.string.deploy_not_installed, p.slug)
                        : getString(R.string.deploy_installed, p.slug, info.installed));
                runOnUiThread(() -> {
                    setBusy(false);
                    b.serverInfo.setText(text);
                    rememberServer(p, info.fingerprint);
                });
            } catch (SshClient.SshException e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    b.serverInfo.setText(e.getMessage());
                    b.forgetKey.setVisibility(Prefs.sshHostKey(this).isEmpty() ? View.GONE : View.VISIBLE);
                });
            }
        });
    }

    private void rememberServer(ShopInstaller.Params p, String fingerprint) {
        Prefs.setSsh(this, p.host, p.port, p.user);
        if (!fingerprint.isEmpty()) Prefs.setSshHostKey(this, fingerprint);
    }

    // ------------------------------------------------------------ установка

    private void confirmInstall() {
        ShopInstaller.Params p = params(true);
        if (p == null) return;
        String where = p.domain.isEmpty()
                ? getString(R.string.deploy_confirm_no_domain, p.host)
                : getString(R.string.deploy_confirm_domain, p.domain);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.deploy_install)
                .setMessage(getString(R.string.deploy_confirm, p.host, p.slug, where))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.deploy_install_go, (d, w) -> run(false))
                .show();
    }

    private void run(boolean update) {
        ShopInstaller.Params p = params(!update);
        if (p == null || busy) return;
        if (!update && p.adminPass.isEmpty()) {
            // Пароль панели не задан — придумываем сами и показываем в конце.
            p.adminPass = randomPassword();
        }
        String shopName = Ui.text(b.shopName);

        setBusy(true);
        log.setLength(0);
        b.log.setText("");
        b.openSite.setVisibility(View.GONE);
        b.progressCard.setVisibility(View.VISIBLE);
        b.progress.setProgressCompat(0, false);
        b.stepText.setText(R.string.deploy_connecting);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        ShopInstaller.Listener listener = new ShopInstaller.Listener() {
            @Override
            public void onStep(String text) {
                runOnUiThread(() -> b.stepText.setText(text));
                append("▸ " + text);
            }

            @Override
            public void onLog(String line) {
                append(line);
            }

            @Override
            public void onWarn(String text) {
                append("⚠ " + text);
            }

            @Override
            public void onProgress(int percent) {
                runOnUiThread(() -> b.progress.setProgressCompat(percent, true));
            }
        };

        Repository.get(this).io().execute(() -> {
            try {
                ShopInstaller.Result r = update ? installer.update(p, listener) : installer.install(p, listener);
                rememberServer(p, r.fingerprint);
                if (!update) {
                    Prefs.setInstall(this, p.slug, p.domain, p.email);
                    Prefs.setServerUrl(this, r.url());
                    Prefs.setApiKey(this, r.key());
                    Prefs.setWebSiteUrl(this, r.url());
                    Prefs.setWebAdminUrl(this, r.url() + "/admin");
                    Prefs.setWebAdminLogin(this, p.adminLogin);
                    // Пароль панели сервер берёт только при первой установке —
                    // при переустановке в базе остаётся прежний.
                    if (r.fresh()) Prefs.setWebAdminPass(this, p.adminPass);
                    if (!shopName.isEmpty()) {
                        Shops.rename(this, Shops.activeId(this), shopName);
                        pushShopName(shopName);
                    }
                }
                String done = update
                        ? getString(R.string.deploy_updated, r.version())
                        : getString(R.string.deploy_done, r.url(), p.adminLogin,
                        !r.fresh() ? getString(R.string.deploy_pass_kept)
                                : Ui.text(b.adminPass).isEmpty() ? p.adminPass
                                : getString(R.string.deploy_pass_yours));
                runOnUiThread(() -> {
                    setBusy(false);
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    b.stepText.setText(update ? R.string.deploy_update_ok : R.string.deploy_install_ok);
                    b.openSite.setVisibility(View.VISIBLE);
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(update ? R.string.deploy_update_ok : R.string.deploy_install_ok)
                            .setMessage(done)
                            .setPositiveButton(R.string.ok, null)
                            .show();
                });
            } catch (SshClient.SshException e) {
                append("✖ " + e.getMessage());
                runOnUiThread(() -> {
                    setBusy(false);
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    b.stepText.setText(R.string.deploy_failed);
                    b.forgetKey.setVisibility(Prefs.sshHostKey(this).isEmpty() ? View.GONE : View.VISIBLE);
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.deploy_failed)
                            .setMessage(e.getMessage())
                            .setPositiveButton(R.string.ok, null)
                            .show();
                });
            }
        });
    }

    /**
     * Название магазина — на сайт: шапка, вкладка браузера, бот. Остальные
     * тексты не трогаем: сервер сохраняет их все разом, поэтому сначала читаем.
     */
    private void pushShopName(String name) {
        try {
            ServerApi api = new ServerApi(this);
            JSONObject site = api.site().optJSONObject("site");
            if (site == null) site = new JSONObject();
            site.put("title", name);
            api.saveSite(site);
        } catch (Exception e) {
            append("⚠ " + getString(R.string.deploy_title_not_saved, e.getMessage()));
        }
    }

    private void append(String line) {
        synchronized (log) {
            log.append(line).append('\n');
            // Длинный вывод apt никому не нужен целиком — держим хвост.
            if (log.length() > 20_000) log.delete(0, log.length() - 15_000);
        }
        runOnUiThread(() -> {
            synchronized (log) {
                b.log.setText(log);
            }
        });
    }

    private void setBusy(boolean value) {
        busy = value;
        b.check.setEnabled(!value);
        b.install.setEnabled(!value);
        b.update.setEnabled(!value);
    }

    @Override
    public void onBackPressed() {
        if (busy) {
            Ui.snack(b.getRoot(), getString(R.string.deploy_wait));
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------- мелочи

    private static String randomPassword() {
        String abc = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) sb.append(abc.charAt(r.nextInt(abc.length())));
        return sb.toString();
    }

    /** «Цветы у Марины» → «tsvety-u-mariny»: имя папки и службы на сервере. */
    static String slugOf(String name) {
        String[] ru = {"а", "б", "в", "г", "д", "е", "ё", "ж", "з", "и", "й", "к", "л", "м", "н", "о",
                "п", "р", "с", "т", "у", "ф", "х", "ц", "ч", "ш", "щ", "ъ", "ы", "ь", "э", "ю", "я"};
        String[] en = {"a", "b", "v", "g", "d", "e", "e", "zh", "z", "i", "y", "k", "l", "m", "n", "o",
                "p", "r", "s", "t", "u", "f", "h", "ts", "ch", "sh", "sch", "", "y", "", "e", "yu", "ya"};
        StringBuilder sb = new StringBuilder();
        for (char ch : name.toLowerCase().toCharArray()) {
            String c = String.valueOf(ch);
            int i = java.util.Arrays.asList(ru).indexOf(c);
            if (i >= 0) sb.append(en[i]);
            else if (c.matches("[a-z0-9]")) sb.append(c);
            else sb.append('-');
        }
        String s = sb.toString().replaceAll("-+", "-").replaceAll("^-|-$", "");
        return s.length() > 30 ? s.substring(0, 30).replaceAll("-$", "") : s;
    }
}
