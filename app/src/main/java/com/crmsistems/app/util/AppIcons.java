package com.crmsistems.app.util;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;

import com.crmsistems.app.R;

/**
 * Иконка приложения на выбор.
 * <p>
 * Картинку значка Android берёт из APK, поменять её на произвольную нельзя.
 * Поэтому в манифесте заведено несколько входов в приложение (activity-alias)
 * с разными иконками, и включён ровно один из них — лаунчер показывает его.
 * Своя картинка из галереи — это ярлык магазина на рабочем столе, см. {@link ShopBranding}.
 */
public final class AppIcons {

    /** Один вариант иконки. */
    public static final class Option {
        public final String key;
        public final String label;
        public final int launcher; // R.mipmap.*
        @DrawableRes public final int foreground;
        @ColorRes public final int color;

        Option(String key, String label, int launcher, int foreground, int color) {
            this.key = key;
            this.label = label;
            this.launcher = launcher;
            this.foreground = foreground;
            this.color = color;
        }

        /** Имя activity-alias в манифесте: com.crmsistems.app.LauncherFlowers и т. п. */
        String alias(Context c) {
            return c.getPackageName() + ".Launcher"
                    + Character.toUpperCase(key.charAt(0)) + key.substring(1);
        }
    }

    public static final Option[] ALL = {
            new Option("store", "Магазин", R.mipmap.ic_launcher, R.drawable.ic_crm_foreground, R.color.app_icon_store),
            new Option("flowers", "Цветы", R.mipmap.ic_launcher_flowers, R.drawable.ic_app_flowers, R.color.app_icon_flowers),
            new Option("food", "Кафе и еда", R.mipmap.ic_launcher_food, R.drawable.ic_app_food, R.color.app_icon_food),
            new Option("grocery", "Продукты", R.mipmap.ic_launcher_grocery, R.drawable.ic_app_grocery, R.color.app_icon_grocery),
            new Option("clothes", "Одежда", R.mipmap.ic_launcher_clothes, R.drawable.ic_app_clothes, R.color.app_icon_clothes),
            new Option("auto", "Авто", R.mipmap.ic_launcher_auto, R.drawable.ic_app_auto, R.color.app_icon_auto),
            new Option("build", "Стройка, ремонт", R.mipmap.ic_launcher_build, R.drawable.ic_app_build, R.color.app_icon_build),
            new Option("tech", "Техника", R.mipmap.ic_launcher_tech, R.drawable.ic_app_tech, R.color.app_icon_tech),
            new Option("pets", "Зоотовары", R.mipmap.ic_launcher_pets, R.drawable.ic_app_pets, R.color.app_icon_pets),
            new Option("coffee", "Кофе, выпечка", R.mipmap.ic_launcher_coffee, R.drawable.ic_app_coffee, R.color.app_icon_coffee),
    };

    private static final String FILE = "crm_app";
    private static final String KEY = "app_icon";

    private AppIcons() {
    }

    /** Выбранная иконка — общая для всего приложения, не для магазина. */
    public static Option current(Context c) {
        String key = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getString(KEY, "store");
        for (Option o : ALL) if (o.key.equals(key)) return o;
        return ALL[0];
    }

    /**
     * Включить вход с этой иконкой и выключить остальные. Лаунчер обновит
     * значок сам — обычно за несколько секунд.
     */
    public static void apply(Context c, Option chosen) {
        PackageManager pm = c.getPackageManager();
        // Сначала включаем новый, потом гасим старые: иначе на мгновение у
        // приложения не остаётся ни одного входа, и некоторые лаунчеры
        // убирают его значок с рабочего стола.
        set(pm, c, chosen, true);
        for (Option o : ALL) if (o != chosen) set(pm, c, o, false);
        c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit().putString(KEY, chosen.key).apply();
    }

    private static void set(PackageManager pm, Context c, Option o, boolean on) {
        pm.setComponentEnabledSetting(new ComponentName(c, o.alias(c)),
                on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }
}
