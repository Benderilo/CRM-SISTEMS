package com.crmsistems.app.util;

/**
 * Телефон как ключ сопоставления. «+7 999 123-45-67», «8 (999) 1234567»
 * и «9991234567» — один и тот же номер, поэтому сравниваем только цифры
 * без ведущей восьмёрки или семёрки.
 */
public final class Phones {

    private Phones() {
    }

    /** Только значащие цифры номера; пустая строка, если цифр нет. */
    public static String key(String phone) {
        if (phone == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < phone.length(); i++) {
            char ch = phone.charAt(i);
            if (ch >= '0' && ch <= '9') sb.append(ch);
        }
        String d = sb.toString();
        if (d.length() == 11 && (d.charAt(0) == '8' || d.charAt(0) == '7')) d = d.substring(1);
        return d;
    }

    /**
     * Номер для показа: «+7 (999) 123-45-67». Иностранный или неполный номер
     * оставляем как есть — лучше показать как ввели, чем переврать.
     */
    public static String pretty(String phone) {
        String d = key(phone);
        if (d.length() != 10) return phone == null ? "" : phone.trim();
        return "+7 (" + d.substring(0, 3) + ") " + d.substring(3, 6)
                + "-" + d.substring(6, 8) + "-" + d.substring(8, 10);
    }

    /** Совпадают ли номера. Пустой номер не совпадает ни с чем. */
    public static boolean same(String a, String b) {
        String ka = key(a);
        return !ka.isEmpty() && ka.equals(key(b));
    }
}
