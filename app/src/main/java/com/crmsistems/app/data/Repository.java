package com.crmsistems.app.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.LiveData;

import com.crmsistems.app.data.entity.Client;
import com.crmsistems.app.data.entity.Expense;
import com.crmsistems.app.data.entity.OrderEntity;
import com.crmsistems.app.data.entity.Product;
import com.crmsistems.app.data.entity.Sale;
import com.crmsistems.app.net.SyncManager;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Единая точка доступа к данным. Все операции записи и тяжёлые выборки —
 * в фоновом пуле потоков, результат возвращается в главный поток.
 */
public class Repository {

    public interface Callback<T> {
        void onResult(T value);
    }

    private static volatile Repository instance;

    private final AppDatabase db;
    private final Context appContext;
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());

    private Repository(Context context) {
        db = AppDatabase.get(context);
        appContext = context.getApplicationContext();
    }

    public static Repository get(Context context) {
        if (instance == null) {
            synchronized (Repository.class) {
                if (instance == null) instance = new Repository(context);
            }
        }
        return instance;
    }

    /** Сбросить кэш после восстановления базы из резервной копии. */
    public static synchronized void invalidate() {
        instance = null;
        AppDatabase.reset();
    }

    public AppDatabase db() {
        return db;
    }

    public ExecutorService io() {
        return io;
    }

    /** Выполнить задачу в фоне. */
    public void run(Runnable work) {
        io.execute(work);
    }

    /** Выполнить задачу в фоне и вернуть результат в главный поток. */
    public <T> void query(Callable<T> work, Callback<T> callback) {
        io.execute(() -> {
            T result = null;
            try {
                result = work.call();
            } catch (Exception e) {
                e.printStackTrace();
            }
            T value = result;
            if (callback != null) main.post(() -> callback.onResult(value));
        });
    }

    private void done(Runnable callback) {
        if (callback != null) main.post(callback);
    }

    // ------------------------------------------------------------------ товары

    public LiveData<List<Product>> products() {
        return db.productDao().observeAll();
    }

    public LiveData<Product> product(long id) {
        return db.productDao().observeById(id);
    }

    public void saveProduct(Product product, Callback<Long> callback) {
        io.execute(() -> {
            long now = System.currentTimeMillis();
            product.updatedAt = now;
            long id = product.id;
            if (product.id == 0) {
                product.createdAt = now;
                id = db.productDao().insert(product);
                product.id = id;
            } else {
                db.productDao().update(product);
            }
            long result = id;
            if (callback != null) main.post(() -> callback.onResult(result));
        });
    }

    public void deleteProduct(Product product, Runnable callback) {
        io.execute(() -> {
            db.productDao().delete(product);
            done(callback);
        });
    }

    // ----------------------------------------------------------------- клиенты

    public LiveData<List<Client>> clients() {
        return db.clientDao().observeAll();
    }

    public LiveData<Client> client(long id) {
        return db.clientDao().observeById(id);
    }

    public void saveClient(Client client, Callback<Long> callback) {
        io.execute(() -> {
            long now = System.currentTimeMillis();
            client.updatedAt = now;
            long id = client.id;
            if (client.id == 0) {
                client.createdAt = now;
                id = db.clientDao().insert(client);
                client.id = id;
            } else {
                db.clientDao().update(client);
            }
            long result = id;
            if (callback != null) main.post(() -> callback.onResult(result));
        });
    }

    /**
     * Удалить клиента. Если он известен сайту — удаление встанет в очередь
     * и уйдёт при следующей синхронизации, иначе сайт вернул бы его обратно.
     */
    public void deleteClient(Client client, Runnable callback) {
        io.execute(() -> {
            if (client.serverId != 0) {
                SyncManager.enqueueClientDelete(appContext, client.id, client.serverId);
            }
            db.clientDao().delete(client);
            done(callback);
        });
    }

    // ---------------------------------------------------------------- продажи

    public LiveData<List<Sale>> sales() {
        return db.saleDao().observeAll();
    }

    public LiveData<List<Sale>> recentSales(int limit) {
        return db.saleDao().observeRecent(limit);
    }

    public LiveData<List<Sale>> salesOfClient(long clientId) {
        return db.saleDao().observeByClient(clientId);
    }

    public LiveData<List<Sale>> salesOfProduct(long productId) {
        return db.saleDao().observeByProduct(productId);
    }

    /**
     * Сохранить продажу. При {@code writeOffStock = true} остаток товара уменьшается
     * на проданное количество (списание со склада). Отменённая продажа склад
     * не трогает — так же поступает сайт, иначе остатки разъезжаются.
     */
    public void saveSale(Sale sale, boolean writeOffStock, Callback<Long> callback) {
        io.execute(() -> {
            long now = System.currentTimeMillis();
            if (sale.date == 0) sale.date = now;
            long id = sale.id;
            if (sale.id == 0) {
                sale.createdAt = now;
                id = db.saleDao().insert(sale);
                sale.id = id;
                if (writeOffStock && sale.productId != null
                        && sale.status != Sale.STATUS_CANCELED) {
                    db.productDao().changeStock(sale.productId, sale.qty, now);
                }
            } else {
                db.saleDao().update(sale);
            }
            long result = id;
            if (callback != null) main.post(() -> callback.onResult(result));
        });
    }

    /**
     * Сохранить продажу из нескольких позиций. Все строки получают общий
     * groupId — на сайт они уйдут одним заказом, а в карточке клиента
     * покажутся одной покупкой.
     */
    public void saveSales(List<Sale> items, boolean writeOffStock, Runnable callback) {
        io.execute(() -> {
            long now = System.currentTimeMillis();
            // Метка группы — время сохранения: одно на всю продажу и заведомо
            // не совпадает с уже лежащими в базе.
            long group = now;
            for (Sale sale : items) {
                if (sale.date == 0) sale.date = now;
                sale.createdAt = now;
                sale.groupId = group;
                sale.id = db.saleDao().insert(sale);
                if (writeOffStock && sale.productId != null
                        && sale.status != Sale.STATUS_CANCELED) {
                    db.productDao().changeStock(sale.productId, sale.qty, now);
                }
            }
            done(callback);
        });
    }

    public void deleteSale(Sale sale, Runnable callback) {
        io.execute(() -> {
            db.saleDao().delete(sale);
            done(callback);
        });
    }

    // ----------------------------------------------------------------- расходы

    /** Кэш заказов с сайта — аналитика по нему пересчитывается при синхронизации. */
    public LiveData<List<OrderEntity>> orders() {
        return db.orderDao().observeAll();
    }

    public LiveData<List<Expense>> expenses() {
        return db.expenseDao().observeAll();
    }

    public void saveExpense(Expense expense, Callback<Long> callback) {
        io.execute(() -> {
            long now = System.currentTimeMillis();
            if (expense.date == 0) expense.date = now;
            long id = expense.id;
            if (expense.id == 0) {
                expense.createdAt = now;
                id = db.expenseDao().insert(expense);
                expense.id = id;
            } else {
                db.expenseDao().update(expense);
            }
            long result = id;
            if (callback != null) main.post(() -> callback.onResult(result));
        });
    }

    public void deleteExpense(Expense expense, Runnable callback) {
        io.execute(() -> {
            db.expenseDao().delete(expense);
            done(callback);
        });
    }

    // -------------------------------------------------------------- обслуживание

    public void clearAll(Runnable callback) {
        io.execute(() -> {
            db.runInTransaction(() -> {
                db.saleDao().clear();
                db.expenseDao().clear();
                db.productDao().clear();
                db.clientDao().clear();
                // Правки в очереди ссылались на стёртые записи — они уже ни о чём.
                db.pendingChangeDao().clearLocalRefs();
            });
            done(callback);
        });
    }
}
