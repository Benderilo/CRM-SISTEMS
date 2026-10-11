package com.crmsistems.app.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.crmsistems.app.data.dao.ClientDao;
import com.crmsistems.app.data.dao.ExpenseDao;
import com.crmsistems.app.data.dao.OrderDao;
import com.crmsistems.app.data.dao.PendingChangeDao;
import com.crmsistems.app.data.dao.ProductDao;
import com.crmsistems.app.data.dao.SaleDao;
import com.crmsistems.app.data.entity.Client;
import com.crmsistems.app.data.entity.Expense;
import com.crmsistems.app.data.entity.OrderEntity;
import com.crmsistems.app.data.entity.PendingChange;
import com.crmsistems.app.data.entity.Product;
import com.crmsistems.app.data.entity.Sale;

/**
 * Локальная база данных приложения (SQLite через Room).
 * Файл базы лежит в приватной папке приложения: /data/data/&lt;пакет&gt;/databases/crm_N.db,
 * где N — номер магазина (см. {@link com.crmsistems.app.util.Shops}).
 */
@Database(
        entities = {Client.class, Product.class, Sale.class, Expense.class,
                OrderEntity.class, PendingChange.class},
        version = 7,
        exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {


    /**
     * Версия 2 — появилась связь с сайтом: у товара и клиента добавился serverId,
     * у товара ещё и published. Пишем миграцию руками, чтобы уже набранная
     * на телефоне база не стёрлась.
     */
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE products ADD COLUMN published INTEGER NOT NULL DEFAULT 1");
            db.execSQL("ALTER TABLE products ADD COLUMN serverId INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE clients ADD COLUMN serverId INTEGER NOT NULL DEFAULT 0");
        }
    };

    /**
     * Версия 3 — фото товара на сайте: путь к картинке на сервере (serverImage)
     * и флаг photoUploaded, чтобы не заливать одно и то же фото повторно.
     */
    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE products ADD COLUMN serverImage TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE products ADD COLUMN photoUploaded INTEGER NOT NULL DEFAULT 0");
        }
    };

    /**
     * Версия 4 — офлайн-режим: локальный кэш заказов (orders) и исходящие
     * офлайн-правки (pending_changes). Заказы живут на сервере, здесь только
     * последняя удачная копия для просмотра без интернета.
     */
    static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            // Схема должна совпадать с той, что Room генерирует из сущностей, бит в бит:
            // Room 2.7 сверяет nullability и default-значения столбцов, и при расхождении
            // сработал бы fallbackToDestructiveMigration — база стёрлась бы вместе с продажами.
            db.execSQL("CREATE TABLE IF NOT EXISTS orders ("
                    + "id INTEGER NOT NULL, "
                    + "number TEXT, "
                    + "clientName TEXT, "
                    + "phone TEXT, "
                    + "email TEXT, "
                    + "address TEXT, "
                    + "comment TEXT, "
                    + "delivery TEXT, "
                    + "payMethod TEXT, "
                    + "status INTEGER NOT NULL, "
                    + "statusName TEXT, "
                    + "total REAL NOT NULL, "
                    + "profit REAL NOT NULL, "
                    + "createdAt INTEGER NOT NULL, "
                    + "itemsJson TEXT, "
                    + "cachedAt INTEGER NOT NULL, "
                    + "PRIMARY KEY(id))");
            db.execSQL("CREATE TABLE IF NOT EXISTS pending_changes ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "kind INTEGER NOT NULL, "
                    + "refId INTEGER NOT NULL, "
                    + "payload TEXT, "
                    + "createdAt INTEGER NOT NULL)");
        }
    };

    /**
     * Версия 5 — блокировка клиентов: заблокированный не может войти на сайт
     * и оформить заказ. Флаг синхронизируется с сайтом в обе стороны.
     */
    static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE clients ADD COLUMN blocked INTEGER NOT NULL DEFAULT 0");
        }
    };

    /**
     * Версия 6 — история заказов: у кэша появилась дата удаления. Заказ,
     * пропавший с сайта, больше не выбрасывается из базы, а помечается ею:
     * в приложении его не видно, в выгрузку в Excel он попадает.
     */
    static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE orders ADD COLUMN deletedAt INTEGER NOT NULL DEFAULT 0");
        }
    };

    /**
     * Версия 7 — продажа из нескольких позиций: строки одной продажи носят
     * общий groupId. DEFAULT 0 обязателен и в ALTER, и в сущности
     * (@ColumnInfo(defaultValue)) — Room сверяет значение по умолчанию, и при
     * расхождении база стёрлась бы вместе с продажами.
     */
    static final Migration MIGRATION_6_7 = new Migration(6, 7) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE sales ADD COLUMN groupId INTEGER NOT NULL DEFAULT 0");
        }
    };

    private static volatile AppDatabase instance;

    public abstract ClientDao clientDao();

    public abstract ProductDao productDao();

    public abstract SaleDao saleDao();

    public abstract ExpenseDao expenseDao();

    public abstract OrderDao orderDao();

    public abstract PendingChangeDao pendingChangeDao();

    public static AppDatabase get(Context context) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    AppDatabase.class,
                                    com.crmsistems.app.util.Shops.dbName(
                                            com.crmsistems.app.util.Shops.activeId(context)))
                            // Внешние ключи включаем явно: при удалении клиента продажи остаются,
                            // но ссылка обнуляется (ON DELETE SET NULL).
                            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                                    MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                            .fallbackToDestructiveMigration(true)
                            .build();
                }
            }
        }
        return instance;
    }

    /** Полностью пересоздать соединение (нужно после восстановления базы из файла). */
    public static synchronized void reset() {
        if (instance != null && instance.isOpen()) {
            instance.close();
        }
        instance = null;
    }
}
