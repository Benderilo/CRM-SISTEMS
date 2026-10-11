package com.crmsistems.app.util;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;

/** Форматирование денег и чисел. Валюта настраивается в настройках. */
public final class Money {

    private static String currency = "₽";

    private static final DecimalFormatSymbols SYMBOLS = createSymbols();
    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.##", SYMBOLS);
    private static final DecimalFormat SHORT = new DecimalFormat("#,##0.#", SYMBOLS);
    private static final DecimalFormat QTY = new DecimalFormat("#,##0.###", SYMBOLS);
    private static final DecimalFormat PLAIN = new DecimalFormat("0.##", new DecimalFormatSymbols(java.util.Locale.US));

    private Money() {
    }

    private static DecimalFormatSymbols createSymbols() {
        DecimalFormatSymbols s = new DecimalFormatSymbols(Dates.RU);
        s.setGroupingSeparator(' ');
        s.setDecimalSeparator(',');
        return s;
    }

    public static void setCurrency(String value) {
        if (value != null && !value.trim().isEmpty()) currency = value.trim();
    }

    public static String currency() {
        return currency;
    }

    /** 12500.5 → «12 500,5 ₽» */
    public static String format(double value) {
        return MONEY.format(value) + " " + currency;
    }

    /** Без символа валюты. */
    public static String number(double value) {
        return MONEY.format(value);
    }

    public static String qty(double value) {
        return QTY.format(value);
    }

    /** Для подписей на графиках: 1 250 000 → «1,3 млн». */
    public static String compact(double value) {
        double abs = Math.abs(value);
        if (abs >= 1_000_000) return SHORT.format(value / 1_000_000) + " млн";
        if (abs >= 10_000) return SHORT.format(value / 1_000) + " тыс";
        return SHORT.format(value);
    }

    public static String percent(double value) {
        return SHORT.format(value) + " %";
    }

    /** Формат для CSV/Excel — точка как разделитель, без разрядов. */
    public static String plain(double value) {
        return PLAIN.format(value);
    }

    /** Формат для CSV: запятая как разделитель — так число сразу понимает русский Excel. */
    public static String csv(double value) {
        return PLAIN.format(value).replace('.', ',');
    }

    /** Разбор числа из CSV: понимает и «1 234,56», и «1234.56». */
    public static double parse(String text) {
        if (text == null) return 0;
        String s = text.trim()
                .replace(" ", "")
                .replace(" ", "")
                .replace(currency, "")
                .replace("₽", "")
                .replace(",", ".");
        if (s.isEmpty()) return 0;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
