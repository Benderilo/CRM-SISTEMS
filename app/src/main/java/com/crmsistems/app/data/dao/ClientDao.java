package com.crmsistems.app.data.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.crmsistems.app.data.entity.Client;
import com.crmsistems.app.data.model.NameValue;

import java.util.List;

@Dao
public interface ClientDao {

    @Query("SELECT * FROM clients ORDER BY name COLLATE NOCASE ASC")
    LiveData<List<Client>> observeAll();

    @Query("SELECT * FROM clients ORDER BY name COLLATE NOCASE ASC")
    List<Client> getAll();

    @Query("SELECT * FROM clients WHERE archived = 0 ORDER BY name COLLATE NOCASE ASC")
    List<Client> getActive();

    @Query("SELECT * FROM clients WHERE id = :id")
    LiveData<Client> observeById(long id);

    @Query("SELECT * FROM clients WHERE id = :id")
    Client getById(long id);

    @Query("SELECT * FROM clients WHERE phone = :phone AND phone <> '' LIMIT 1")
    Client findByPhone(String phone);

    @Query("SELECT COUNT(*) FROM clients WHERE archived = 0")
    int countActive();

    @Query("SELECT COUNT(*) FROM clients WHERE createdAt BETWEEN :from AND :to")
    int countNew(long from, long to);

    @Query("SELECT segment AS name, COUNT(*) AS value FROM clients WHERE archived = 0 "
            + "GROUP BY segment ORDER BY value DESC")
    List<NameValue> countBySegment();

    @Insert
    long insert(Client client);

    @Insert
    List<Long> insertAll(List<Client> clients);

    @Update
    void update(Client client);

    @Delete
    void delete(Client client);

    @Query("DELETE FROM clients WHERE id = :id")
    void deleteById(long id);

    /** Убрать локальную копию клиента, которого на сайте уже нет. */
    @Query("DELETE FROM clients WHERE serverId = :serverId AND serverId <> 0")
    void deleteByServerId(long serverId);

    @Query("DELETE FROM clients")
    void clear();
}
