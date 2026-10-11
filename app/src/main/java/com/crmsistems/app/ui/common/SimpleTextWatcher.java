package com.crmsistems.app.ui.common;

import android.text.Editable;
import android.text.TextWatcher;

/** Упрощает подписку на изменение текста — нужен только один метод. */
public abstract class SimpleTextWatcher implements TextWatcher {

    public abstract void onChanged(String text);

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }

    @Override
    public void afterTextChanged(Editable s) {
        onChanged(s == null ? "" : s.toString());
    }
}
