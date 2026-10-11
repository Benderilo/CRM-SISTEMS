package com.crmsistems.app.util;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Работа с датами и периодами отчётов. */
public final class Dates {

    public static final Locale RU = new Locale("ru", "RU");

    private static final SimpleDateFormat DATE = new SimpleDateFormat("dd.MM.yyyy", RU);
    private static final SimpleDateFormat DATE_TIME = new SimpleDateFormat("dd.MM.yyyy HH:mm", RU);
    private static final SimpleDateFormat ISO = new SimpleDateFormat("yyyy-MM-dd", RU);
    // Сервер живёт по Москве: расписания, которые он считает сам, показываем
    // в его часах, иначе выбранные 04:00 на телефоне выглядят как 05:00.
    private static final SimpleDateFormat DATE_TIME_MSK = new SimpleDateFormat("dd.MM.yyyy HH:mm", RU);

    private static final String[] MONTHS_SHORT = {
            "янв", "фев", "мар", "апр", "май", "июн", "июл", "авг", "сен", "окт", "ноя", "дек"
    };

    static {
        DATE_TIME_MSK.setTimeZone(TimeZone.getTimeZone("Europe/Moscow"));
    }

    private Dates() {
    }

    public static String formatDate(long millis) {
        if (millis <= 0) return "—";
        return DATE.format(new Date(millis));
    }

    public static String formatDateTime(long millis) {
        if (millis <= 0) return "—";
        return DATE_TIME.format(new Date(millis));
    }

    /** То же время, но московское: им сервер считает свои расписания. */
    public static String formatDateTimeMsk(long millis) {
        if (millis <= 0) return "—";
        return DATE_TIME_MSK.format(new Date(millis));
    }

    public static String formatIso(long millis) {
        if (millis <= 0) return "";
        return ISO.format(new Date(millis));
    }

    /** Разбор даты из CSV: поддерживаем dd.MM.yyyy, yyyy-MM-dd и «сырые» миллисекунды. */
    public static long parse(String text) {
        if (text == null) return 0;
        String s = text.trim();
        if (s.isEmpty()) return 0;
        try {
            if (s.matches("\\d{10,}")) return Long.parseLong(s);
        } catch (NumberFormatException ignored) {
        }
        synchronized (Dates.class) {
            try {
                return DATE.parse(s).getTime();
            } catch (Exception ignored) {
            }
            try {
                return ISO.parse(s).getTime();
            } catch (Exception ignored) {
            }
            try {
                return DATE_TIME.parse(s).getTime();
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    /** «2026-03» → «мар 26» — подпись для оси графика. */
    public static String monthLabel(String yyyyMm) {
        if (yyyyMm == null || yyyyMm.length() < 7) return yyyyMm == null ? "" : yyyyMm;
        try {
            int m = Integer.parseInt(yyyyMm.substring(5, 7));
            String year = yyyyMm.substring(2, 4);
            return MONTHS_SHORT[Math.max(0, Math.min(11, m - 1))] + " " + year;
        } catch (Exception e) {
            return yyyyMm;
        }
    }

    public static long startOfDay(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    public static long endOfDay(long millis) {
        return startOfDay(millis) + 24L * 60 * 60 * 1000 - 1;
    }

    public static long now() {
        return System.currentTimeMillis();
    }

    public static long daysAgo(int days) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, -days);
        return startOfDay(c.getTimeInMillis());
    }

    public static long monthsAgo(int months) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.MONTH, -months);
        return startOfDay(c.getTimeInMillis());
    }

    /** Начало текущего месяца. */
    public static long startOfMonth() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.DAY_OF_MONTH, 1);
        return startOfDay(c.getTimeInMillis());
    }
}
