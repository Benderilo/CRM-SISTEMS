package com.crmsistems.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.crmsistems.app.data.AppDatabase;
import com.crmsistems.app.databinding.ActivityMainBinding;
import com.crmsistems.app.net.SyncManager;
import com.crmsistems.app.ui.analytics.AnalyticsFragment;
import com.crmsistems.app.ui.bot.BotSettingsActivity;
import com.crmsistems.app.ui.clients.ClientsFragment;
import com.crmsistems.app.ui.deploy.DeployActivity;
import com.crmsistems.app.ui.expenses.ExpensesFragment;
import com.crmsistems.app.ui.orders.OrderNotifier;
import com.crmsistems.app.ui.products.ProductsFragment;
import com.crmsistems.app.ui.sales.SalesFragment;
import com.crmsistems.app.ui.settings.SettingsActivity;
import com.crmsistems.app.ui.shops.ShopsActivity;
import com.crmsistems.app.ui.site.SiteContentActivity;
import com.crmsistems.app.ui.sync.ServerSettingsActivity;
import com.crmsistems.app.ui.week.WeekPickActivity;
import com.crmsistems.app.util.NetworkMonitor;
import com.crmsistems.app.util.Prefs;
import com.crmsistems.app.util.ShopBranding;
import com.crmsistems.app.util.Shops;
import com.crmsistems.app.util.Ui;

import java.lang.ref.WeakReference;

/**
 * Главный экран: бургер-меню слева + пять вкладок снизу.
 * Товары / Заказы / Клиенты / Аналитика / Расходы живут во фрагментах
 * и переиспользуются, второстепенные разделы открываются отдельными экранами.
 */
public class MainActivity extends AppCompatActivity {

    /** Открыть сразу вкладку «Заказы» — приходит из уведомления о новом заказе. */
    public static final String EXTRA_OPEN_ORDERS = "open_orders";

    private static final String STATE_TAG = "current_tag";

    private static final String TAG_PRODUCTS = "products";
    private static final String TAG_SALES = "sales";
    private static final String TAG_CLIENTS = "clients";
    private static final String TAG_ANALYTICS = "analytics";
    private static final String TAG_EXPENSES = "expenses";

    private ActivityMainBinding binding;
    private String currentTag = TAG_PRODUCTS;
    private boolean syncing;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** Автосинхронизация с сайтом запускается один раз за запуск приложения. */
    private static boolean autoSynced;

    /** Сеть хоть раз пропадала — тогда возвращение сети действительно запускает обмен. */
    private boolean networkWasLost;

    /**
     * Разрешение на уведомления спрашиваем один раз за запуск: без него
     * сообщения о заказах с сайта просто не показываются.
     */
    private final ActivityResultLauncher<String> notificationPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Открыли с ярлыка другого магазина — сначала переключаемся на него.
        if (openShopFromShortcut(getIntent())) return;
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        applyWindowInsets();

        // Следим за появлением интернета: когда сеть вернулась, отправляем
        // накопленные офлайн-правки и подтягиваем свежие заказы с сайта.
        NetworkMonitor.get(this).register();
        NetworkMonitor.get(this).setListener(new ReconnectListener(this));

        binding.toolbar.setNavigationOnClickListener(v -> binding.drawerLayout.openDrawer(GravityCompat.START));
        binding.toolbar.setOnMenuItemClickListener(this::onToolbarItem);
        binding.navView.setNavigationItemSelectedListener(this::onDrawerItem);
        binding.bottomNav.setOnItemSelectedListener(item -> {
            // «Сборка» — отдельный экран, а не вкладка: открываем его и
            // оставляем выделенной прежнюю вкладку, чтобы возврат был туда же.
            if (item.getItemId() == R.id.nav_week) {
                startActivity(new Intent(this, WeekPickActivity.class));
                return false;
            }
            if (!syncing) select(tagOf(item.getItemId()), true);
            return true;
        });

        if (savedInstanceState != null) {
            currentTag = savedInstanceState.getString(STATE_TAG, TAG_PRODUCTS);
        }
        if (getIntent().getBooleanExtra(EXTRA_OPEN_ORDERS, false)) currentTag = TAG_SALES;
        select(currentTag, false);

        // Шапка меню — название магазина; нажатие открывает список магазинов.
        binding.navView.getHeaderView(0).setOnClickListener(v -> {
            binding.drawerLayout.closeDrawer(GravityCompat.START);
            startActivity(new Intent(this, ShopsActivity.class));
        });

        if (getIntent().getBooleanExtra(ShopsActivity.EXTRA_OPEN_DEPLOY, false)) {
            getIntent().removeExtra(ShopsActivity.EXTRA_OPEN_DEPLOY);
            startActivity(new Intent(this, DeployActivity.class));
        } else if (savedInstanceState == null) {
            offerSetup();
        }

        askNotificationPermission();
    }

    /**
     * Магазин ещё не связан с сервером — один раз предлагаем: поставить сервер
     * на свой VPS или вписать адрес уже работающего.
     */
    private void offerSetup() {
        if (Prefs.serverReady(this) || Prefs.setupOffered(this)) return;
        Prefs.setSetupOffered(this);
        new AlertDialog.Builder(this)
                .setTitle(R.string.setup_title)
                .setMessage(R.string.setup_message)
                .setPositiveButton(R.string.setup_deploy, (d, w) ->
                        startActivity(new Intent(this, DeployActivity.class)))
                .setNeutralButton(R.string.setup_existing, (d, w) ->
                        startActivity(new Intent(this, ServerSettingsActivity.class)))
                .setNegativeButton(R.string.setup_later, null)
                .show();
    }

    /**
     * Ярлык магазина на рабочем столе несёт номер магазина. Если открыт
     * другой — переключаемся (с перезапуском, как в «Моих магазинах»).
     */
    private boolean openShopFromShortcut(Intent intent) {
        int want = intent.getIntExtra(ShopBranding.EXTRA_SHOP_ID, 0);
        if (want == 0 || want == Shops.activeId(this) || Shops.find(this, want) == null) return false;
        AppDatabase.reset();
        Shops.setActive(this, want);
        ShopsActivity.restart(this, false);
        return true;
    }

    /** Нажали на уведомление, когда приложение уже открыто, — переходим к заказам. */
    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        if (openShopFromShortcut(intent)) return;
        setIntent(intent);
        if (intent.getBooleanExtra(EXTRA_OPEN_ORDERS, false)) select(TAG_SALES, true);
    }

    private void askNotificationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // В шапке меню — открытый магазин и подсказка, что их можно переключать.
        View header = binding.navView.getHeaderView(0);
        TextView title = header.findViewById(R.id.headerTitle);
        TextView subtitle = header.findViewById(R.id.headerSubtitle);
        title.setText(Shops.active(this).name);
        showLogo(header.findViewById(R.id.headerIcon));
        String company = Prefs.company(this);
        subtitle.setText(company.isEmpty() ? getString(R.string.shops_header_hint) : company);

        autoSync();
    }

    /** Логотип магазина в шапке меню, если его выбрали; иначе — значок по умолчанию. */
    private void showLogo(android.widget.ImageView icon) {
        android.graphics.Bitmap logo = ShopBranding.logo(this, Shops.activeId(this));
        icon.setClipToOutline(true);
        if (logo != null) {
            icon.setImageBitmap(logo);
            icon.setImageTintList(null);
            icon.setPadding(0, 0, 0, 0);
            icon.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        } else {
            icon.setImageResource(R.drawable.ic_analytics);
            int pad = Ui.dp(this, 12);
            icon.setPadding(pad, pad, pad, pad);
        }
    }

    /** Один раз за запуск — тихо подтянуть изменения с сайта и отдать свои. */
    private void autoSync() {
        if (autoSynced) return;
        autoSynced = true;
        if (!Prefs.serverReady(this)) return;
        new SyncManager(this).syncAll(result -> {
            if (!result.ok()) {
                Ui.snack(binding.getRoot(), result.summary());
            } else if (!result.newOrders.isEmpty()) {
                // Уведомление телефон уже показал, но если продавец смотрит
                // в приложение, подсказка на экране заметнее.
                Ui.snack(binding.getRoot(), OrderNotifier.summary(this, result.newOrders));
            }
        });
    }

    /** Сеть вернулась — сбрасываем флаг автосинхронизации и запускаем обмен заново. */
    private void onReconnect() {
        if (isDestroyed()) return;
        if (!networkWasLost) return;       // при первом срабатывании на старте сеть и так есть
        networkWasLost = false;
        autoSynced = false;
        autoSync();
    }

    private void onNetworkLost() {
        networkWasLost = true;
    }

    /**
     * Слушатель сети, не удерживающий активность (через WeakReference) — иначе
     * синглтон NetworkMonitor утёк бы вместе с MainActivity.
     */
    private static class ReconnectListener implements NetworkMonitor.Listener {
        private final WeakReference<MainActivity> ref;

        ReconnectListener(MainActivity activity) {
            ref = new WeakReference<>(activity);
        }

        @Override
        public void onAvailable() {
            MainActivity a = ref.get();
            if (a == null) return;
            a.mainHandler.post(a::onReconnect);
        }

        @Override
        public void onLost() {
            MainActivity a = ref.get();
            if (a == null) return;
            a.mainHandler.post(a::onNetworkLost);
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_TAG, currentTag);
    }

    /** Приложение рисуется под системными панелями — добавляем отступы вручную. */
    private void applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.contentRoot, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            binding.appBar.setPadding(bars.left, bars.top, bars.right, 0);
            binding.bottomNav.setPadding(bars.left, 0, bars.right, bars.bottom);
            return windowInsets;
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START);
                } else if (!TAG_PRODUCTS.equals(currentTag)) {
                    select(TAG_PRODUCTS, true);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    // ------------------------------------------------------------------ меню

    private boolean onToolbarItem(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_export) {
            startActivity(new Intent(this, SettingsActivity.class)
                    .putExtra(SettingsActivity.EXTRA_SCROLL_TO_DATA, true));
            return true;
        }
        if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return false;
    }

    private boolean onDrawerItem(@NonNull MenuItem item) {
        int id = item.getItemId();
        binding.drawerLayout.closeDrawer(GravityCompat.START);

        if (id == R.id.nav_products || id == R.id.nav_sales || id == R.id.nav_clients
                || id == R.id.nav_analytics || id == R.id.nav_expenses) {
            select(tagOf(id), true);
            return true;
        }
        if (id == R.id.nav_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        if (id == R.id.nav_shops) {
            startActivity(new Intent(this, ShopsActivity.class));
            return true;
        }
        if (id == R.id.nav_deploy) {
            startActivity(new Intent(this, DeployActivity.class));
            return true;
        }
        if (id == R.id.nav_server) {
            startActivity(new Intent(this, ServerSettingsActivity.class));
            return true;
        }
        if (id == R.id.nav_contacts) {
            startActivity(new Intent(this, SiteContentActivity.class));
            return true;
        }
        if (id == R.id.nav_bot) {
            startActivity(new Intent(this, BotSettingsActivity.class));
            return true;
        }
        if (id == R.id.nav_week) {
            startActivity(new Intent(this, WeekPickActivity.class));
            return true;
        }
        if (id == R.id.nav_about) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.app_name)
                    .setMessage(R.string.about_text)
                    .setPositiveButton(R.string.ok, null)
                    .show();
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------- навигация

    private String tagOf(int menuId) {
        if (menuId == R.id.nav_sales) return TAG_SALES;
        if (menuId == R.id.nav_clients) return TAG_CLIENTS;
        if (menuId == R.id.nav_analytics) return TAG_ANALYTICS;
        if (menuId == R.id.nav_expenses) return TAG_EXPENSES;
        return TAG_PRODUCTS;
    }

    private int menuIdOf(String tag) {
        switch (tag) {
            case TAG_SALES:
                return R.id.nav_sales;
            case TAG_CLIENTS:
                return R.id.nav_clients;
            case TAG_ANALYTICS:
                return R.id.nav_analytics;
            case TAG_EXPENSES:
                return R.id.nav_expenses;
            default:
                return R.id.nav_products;
        }
    }

    private int titleOf(String tag) {
        switch (tag) {
            case TAG_SALES:
                return R.string.nav_sales;
            case TAG_CLIENTS:
                return R.string.nav_clients;
            case TAG_ANALYTICS:
                return R.string.nav_analytics;
            case TAG_EXPENSES:
                return R.string.nav_expenses;
            default:
                return R.string.nav_products;
        }
    }

    private Fragment createFragment(String tag) {
        switch (tag) {
            case TAG_SALES:
                return new SalesFragment();
            case TAG_CLIENTS:
                return new ClientsFragment();
            case TAG_ANALYTICS:
                return new AnalyticsFragment();
            case TAG_EXPENSES:
                return new ExpensesFragment();
            default:
                return new ProductsFragment();
        }
    }

    private void select(String tag, boolean animate) {
        currentTag = tag;
        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction tr = fm.beginTransaction();
        tr.setReorderingAllowed(true);
        if (animate) {
            tr.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE);
        }
        for (Fragment fragment : fm.getFragments()) {
            tr.hide(fragment);
        }
        Fragment target = fm.findFragmentByTag(tag);
        if (target == null) {
            tr.add(R.id.container, createFragment(tag), tag);
        } else {
            tr.show(target);
        }
        tr.commit();

        binding.toolbar.setTitle(titleOf(tag));

        syncing = true;
        int menuId = menuIdOf(tag);
        binding.navView.setCheckedItem(menuId);
        if (binding.bottomNav.getSelectedItemId() != menuId) {
            binding.bottomNav.setSelectedItemId(menuId);
        }
        syncing = false;
    }

    /** Позволяет фрагментам поднимать содержимое над нижним меню. */
    public View bottomBar() {
        return binding.bottomNav;
    }
}
