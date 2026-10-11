package com.crmsistems.app.data.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.crmsistems.app.data.entity.PendingChange;

import java.util.List;

@Dao
public interface PendingChangeDao {

    @Insert
    long insert(PendingChange change);

    @Query("SELECT * FROM pending_changes ORDER BY createdAt ASC")
    List<PendingChange> getAll();

    @Query("DELETE FROM pending_changes WHERE id = :id")
    void delete(long id);

    /** Чтобы не плодить дубли — перед новой записью убираем старую того же вида и цели. */
    @Query("DELETE FROM pending_changes WHERE kind = :kind AND refId = :refId")
    void deleteKindRef(int kind, long refId);

    /**
     * Убрать правки, которые ссылаются на локальные записи (товары, клиенты,
     * продажи). Нужно после очистки базы и восстановления из копии: старые id
     * уже ничего не значат, а случайное совпадение сработало бы не на той записи.
     * Смены статусов заказов (kind = 0) не трогаем — они живут по id сайта.
     */
    @Query("DELETE FROM pending_changes WHERE kind IN (1, 2, 3)")
    void clearLocalRefs();

    /** Памятка о конкретной записи — например, списывать ли склад по продаже. */
    @Query("SELECT * FROM pending_changes WHERE kind = :kind AND refId = :refId LIMIT 1")
    PendingChange findKindRef(int kind, long refId);

    @Query("SELECT COUNT(*) FROM pending_changes")
    int count();

    @Query("SELECT COUNT(*) FROM pending_changes")
    LiveData<Integer> observeCount();
}