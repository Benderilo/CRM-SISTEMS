package com.crmsistems.app.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import androidx.annotation.NonNull;

/**
 * Следит за появлением и пропаданием интернета через системный ConnectivityManager.
 * Когда сеть возвращается, будит слушателей — они отправляют накопленные
 * офлайн-правки и подтягивают новые заказы с сайта.
 */
public final class NetworkMonitor {

    /** Слушатель доступности сети. */
    public interface Listener {
        /** Сеть появилась (или только что стала пригодной для интернета). */
        default void onAvailable() {}

        /** Сеть пропала. */
        default void onLost() {}
    }

    private static NetworkMonitor instance;

    private final ConnectivityManager cm;
    private final ConnectivityManager.NetworkCallback callback;
    private boolean registered;
    private Listener listener;

    private NetworkMonitor(Context context) {
        cm = (ConnectivityManager) context.getApplicationContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                if (listener != null) listener.onAvailable();
            }

            @Override
            public void onLost(@NonNull Network network) {
                // onLost приходит и при смене сети; проверяем реальное состояние,
                // чтобы не ложно сообщать о потере связи при переходе Wi-Fi ↔ мобильная сеть.
                if (!isOnlineNow() && listener != null) listener.onLost();
            }
        };
    }

    /** Единственный экземпляр на всё приложение. */
    public static synchronized NetworkMonitor get(Context context) {
        if (instance == null) {
            instance = new NetworkMonitor(context);
        }
        return instance;
    }

    /** Начать следить за сетью. Безопасно вызывать многократно — повторно не регистрируется. */
    public void register() {
        if (registered) return;
        try {
            cm.registerNetworkCallback(
                    new NetworkRequest.Builder()
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            .build(),
                    callback);
            registered = true;
        } catch (Exception ignored) {
            // На устройствах без ConnectivityManager просто молча работаем «как раньше».
        }
    }

    public void unregister() {
        try {
            cm.unregisterNetworkCallback(callback);
            registered = false;
        } catch (Exception ignored) {
        }
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    /** Есть ли прямо сейчас соединение с интернетом (синхронная проверка). */
    public boolean isOnline() {
        return isOnlineNow();
    }

    private boolean isOnlineNow() {
        try {
            android.net.Network active = cm.getActiveNetwork();
            if (active == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(active);
            return caps != null
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Exception e) {
            return false;
        }
    }
}