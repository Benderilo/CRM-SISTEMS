package com.crmsistems.app.data.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

/**
 * Карточка клиента.
 */
@Entity(tableName = "clients")
public class Client {

    public static final String[] SEGMENTS = {"Новый", "Постоянный", "VIP", "Спящий", "Проблемный"};
    public static final String[] SOURCES = {"Сарафан", "Реклама", "Соцсети", "Сайт", "Холодный звонок", "Другое"};

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    public String name = "";

    /** Телефон — ключ при импорте (по нему ищем дубли). */
    @NonNull
    public String phone = "";

    @NonNull
    public String email = "";

    @NonNull
    public String company = "";

    @NonNull
    public String address = "";

    /** Сегмент: Новый / Постоянный / VIP / Спящий / Проблемный. */
    @NonNull
    public String segment = "Новый";

    /** Источник обращения. */
    @NonNull
    public String source = "";

    /** Метки через запятую. */
    @NonNull
    public String tags = "";

    @NonNull
    public String note = "";

    @NonNull
    public String photoUri = "";

    /** День рождения (epoch millis), 0 — не указан. */
    public long birthday;

    public boolean archived;

    /** Заблокирован: не может войти на сайт и оформить заказ там. */
    public boolean blocked;

    /** id этого же клиента на сайте, 0 — на сайте его ещё нет. */
    public long serverId;

    public long createdAt;
    public long updatedAt;

    public Client() {
    }

    @Ignore
    public Client(@NonNull String name, @NonNull String phone, @NonNull String segment) {
        this.name = name;
        this.phone = phone;
        this.segment = segment;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
    }

    @NonNull
    public String title() {
        return name.isEmpty() ? "Без имени" : name;
    }

    /** Инициалы для аватарки-заглушки. */
    @NonNull
    public String initials() {
        String src = title().trim();
        String[] parts = src.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length && sb.length() < 2; i++) {
            if (!parts[i].isEmpty()) sb.append(Character.toUpperCase(parts[i].charAt(0)));
        }
        return sb.length() == 0 ? "?" : sb.toString();
    }
}
