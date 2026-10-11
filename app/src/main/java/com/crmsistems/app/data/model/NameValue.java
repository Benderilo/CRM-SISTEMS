package com.crmsistems.app.data.model;

import androidx.room.Ignore;

/** Пара «подпись — значение» для графиков и топов. */
public class NameValue {
    public String name;
    public double value;

    public NameValue() {
    }

    @Ignore
    public NameValue(String name, double value) {
        this.name = name;
        this.value = value;
    }

    public String safeName() {
        return name == null || name.trim().isEmpty() ? "Без названия" : name;
    }
}
