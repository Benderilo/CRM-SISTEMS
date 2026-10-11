package com.crmsistems.app.ui.site;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.crmsistems.app.R;
import com.crmsistems.app.databinding.ActivityShopBrowserBinding;
import com.crmsistems.app.util.Ui;

/**
 * Встроенный браузер для сайта и админ-панели магазина. Настроен как обычный
 * браузер: JavaScript, DOM-хранилище, куки (включая сторонние), масштаб
 * щипком, выбор файлов, диалоги страниц, переходы по «Назад» историей.
 * <p>
 * Если передали логин и пароль, сам входит в админку: на странице входа
 * заполняет форму и отправляет её. Несколько попыток подряд не делает —
 * с неверным паролем вход не должен зациклиться.
 */
public class ShopBrowserActivity extends AppCompatActivity {

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_LOGIN = "login";
    public static final String EXTRA_PASS = "pass";

    /** Сколько раз пробуем автовход: страница входа + одна повторная попытка. */
    private static final int MAX_LOGIN_ATTEMPTS = 2;

    private ActivityShopBrowserBinding b;
    private String url;
    private String login = "";
    private String pass = "";
    private int loginAttempts;

    /** Ожидает результата системного выбора файла (загрузка фото в админке). */
    private ValueCallback<Uri[]> fileCallback;

    private final ActivityResultLauncher<Intent> fileChooser =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                Uri[] uris = null;
                Intent data = result.getData();
                if (result.getResultCode() == Activity.RESULT_OK && data != null) {
                    if (data.getClipData() != null) {
                        int n = data.getClipData().getItemCount();
                        uris = new Uri[n];
                        for (int i = 0; i < n; i++) uris[i] = data.getClipData().getItemAt(i).getUri();
                    } else if (data.getData() != null) {
                        uris = new Uri[]{data.getData()};
                    }
                }
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(uris);
                    fileCallback = null;
                }
            });

    public static void start(Context context, String url, @Nullable String login, @Nullable String pass) {
        Intent i = new Intent(context, ShopBrowserActivity.class);
        i.putExtra(EXTRA_URL, url);
        i.putExtra(EXTRA_LOGIN, login == null ? "" : login);
        i.putExtra(EXTRA_PASS, pass == null ? "" : pass);
        context.startActivity(i);
    }

    /** Доводит адрес до вида https://host/path — без схемы добавляем https. */
    public static String normalizeUrl(String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty()) return "";
        if (!v.startsWith("http://") && !v.startsWith("https://")) v = "https://" + v;
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityShopBrowserBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        url = normalizeUrl(getIntent().getStringExtra(EXTRA_URL));
        String l = getIntent().getStringExtra(EXTRA_LOGIN);
        String p = getIntent().getStringExtra(EXTRA_PASS);
        if (l != null) login = l;
        if (p != null) pass = p;
        if (url.isEmpty()) {
            finish();
            return;
        }

        Ui.applyInsets(b.root, b.appbarInc.getRoot(), b.web);

        b.appbarInc.toolbar.setTitle(host(url));
        b.appbarInc.toolbar.setNavigationOnClickListener(v -> finish());
        b.appbarInc.toolbar.inflateMenu(R.menu.menu_browser);
        b.appbarInc.toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_refresh) {
                b.web.reload();
                return true;
            }
            if (id == R.id.action_external) {
                openOutside(url);
                return true;
            }
            return false;
        });

        b.retry.setOnClickListener(v -> {
            b.errorBox.setVisibility(View.GONE);
            b.web.reload();
        });

        setupWebView();
        b.web.loadUrl(url);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (b.web.canGoBack()) {
                    b.web.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    // ------------------------------------------------------------- браузер

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = b.web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        // «; wv» в стандартном User-Agent выдаёт WebView — убираем,
        // чтобы сайты считали нас обычным Chrome.
        s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setGeolocationEnabled(true);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(b.web, true);

        b.web.setWebViewClient(new ShopWebViewClient());
        b.web.setWebChromeClient(new ShopChromeClient());
        b.web.setDownloadListener((downloadUrl, userAgent, contentDisposition,
                                   mimetype, contentLength) -> openOutside(downloadUrl));
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Куки сессии админки сохраняем на диск сразу — переживут перезапуск.
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        if (b != null) b.web.destroy();
        super.onDestroy();
    }

    /** tel:, mailto:, скачивание файлов — отдаём системным приложениям. */
    private void openOutside(String link) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            Ui.snack(b.getRoot(), getString(R.string.web_no_app));
        }
    }

    private static String host(String pageUrl) {
        Uri uri = Uri.parse(pageUrl);
        String h = uri.getHost();
        return h == null ? pageUrl : h;
    }

    // ------------------------------------------------------------ автовход

    /**
     * Открылась страница входа в админку — заполняем форму сохранёнными
     * логином и паролем и отправляем её.
     */
    private void tryAutoLogin(String pageUrl) {
        if (login.isEmpty() || pass.isEmpty()) return;
        if (loginAttempts >= MAX_LOGIN_ATTEMPTS) return;
        if (pageUrl == null || !pageUrl.contains("/admin/login")) return;
        loginAttempts++;
        String js = "(function(){var l=document.getElementById('login');" +
                "var p=document.getElementById('password');" +
                "if(l&&p){l.value='" + js(login) + "';p.value='" + js(pass) + "';" +
                "var f=l.closest('form');if(f){f.submit();}}})()";
        b.web.evaluateJavascript(js, null);
    }

    /** Экранируем значение для вставки в JS-строку в одинарных кавычках. */
    private static String js(String v) {
        return v.replace("\\", "\\\\").replace("'", "\\'")
                .replace("\n", "").replace("\r", "");
    }

    // --------------------------------------------------------------- клиенты

    private class ShopWebViewClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme();
            if ("http".equals(scheme) || "https".equals(scheme)) return false; // грузим сами
            openOutside(uri.toString());
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String pageUrl, Bitmap favicon) {
            super.onPageStarted(view, pageUrl, favicon);
            b.appbarInc.toolbar.setTitle(host(pageUrl));
            b.errorBox.setVisibility(View.GONE);
        }

        @Override
        public void onPageFinished(WebView view, String pageUrl) {
            super.onPageFinished(view, pageUrl);
            tryAutoLogin(pageUrl);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            super.onReceivedError(view, request, error);
            if (request.isForMainFrame()) b.errorBox.setVisibility(View.VISIBLE);
        }

        @Override
        public void onReceivedSslError(WebView view, final SslErrorHandler handler, SslError error) {
            new AlertDialog.Builder(ShopBrowserActivity.this)
                    .setTitle(R.string.web_ssl_title)
                    .setMessage(getString(R.string.web_ssl_message, host(url)))
                    .setPositiveButton(R.string.web_continue, (d, w) -> handler.proceed())
                    .setNegativeButton(R.string.cancel, (d, w) -> handler.cancel())
                    .setOnCancelListener(d -> handler.cancel())
                    .show();
        }
    }

    private class ShopChromeClient extends WebChromeClient {

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            b.progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
        }

        @Override
        public boolean onJsAlert(WebView view, String pageUrl, String message, final JsResult result) {
            new AlertDialog.Builder(ShopBrowserActivity.this)
                    .setMessage(message)
                    .setPositiveButton(R.string.ok, (d, w) -> result.confirm())
                    .setOnCancelListener(d -> result.cancel())
                    .show();
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView view, String pageUrl, String message, final JsResult result) {
            new AlertDialog.Builder(ShopBrowserActivity.this)
                    .setMessage(message)
                    .setPositiveButton(R.string.ok, (d, w) -> result.confirm())
                    .setNegativeButton(R.string.cancel, (d, w) -> result.cancel())
                    .setOnCancelListener(d -> result.cancel())
                    .show();
            return true;
        }

        @Override
        public boolean onJsPrompt(WebView view, String pageUrl, String message, String defaultValue,
                                  final JsPromptResult result) {
            EditText input = new EditText(ShopBrowserActivity.this);
            input.setText(defaultValue);
            new AlertDialog.Builder(ShopBrowserActivity.this)
                    .setMessage(message)
                    .setView(input)
                    .setPositiveButton(R.string.ok, (d, w) -> result.confirm(input.getText().toString()))
                    .setNegativeButton(R.string.cancel, (d, w) -> result.cancel())
                    .setOnCancelListener(d -> result.cancel())
                    .show();
            return true;
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin,
                                                       GeolocationPermissions.Callback callback) {
            // Разрешение на местоположение у приложения не запрошено — отказываем.
            callback.invoke(origin, false, false);
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = callback;
            try {
                fileChooser.launch(params.createIntent());
                return true;
            } catch (ActivityNotFoundException e) {
                fileCallback = null;
                return false;
            }
        }
    }
}
