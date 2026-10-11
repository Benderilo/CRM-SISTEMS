package com.crmsistems.app.io;

import java.util.ArrayList;
import java.util.List;

/**
 * Чтение и запись CSV. Пишем с разделителем «;» и BOM — так файл сразу
 * открывается в русском Excel двойным кликом. При чтении разделитель определяется сам.
 */
public final class Csv {

    public static final char DELIMITER = ';';
    public static final String BOM = String.valueOf((char) 0xFEFF);

    private Csv() {
    }

    public static String write(List<String[]> rows) {
        StringBuilder sb = new StringBuilder(BOM);
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                if (i > 0) sb.append(DELIMITER);
                sb.append(escape(row[i]));
            }
            sb.append("\r\n");
        }
        return sb.toString();
    }

    private static String escape(String value) {
        String text = value == null ? "" : value;
        boolean needQuotes = text.indexOf(DELIMITER) >= 0 || text.indexOf('"') >= 0
                || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
        if (!needQuotes) return text;
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    /** Разбирает CSV в список строк-массивов. Пустые строки пропускаются. */
    public static List<String[]> parse(String source) {
        List<String[]> rows = new ArrayList<>();
        if (source == null || source.isEmpty()) return rows;

        String text = source.startsWith(BOM) ? source.substring(1) : source;
        char delimiter = detectDelimiter(text);

        List<String> current = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cell.append(c);
                }
                continue;
            }
            if (c == '"') {
                inQuotes = true;
            } else if (c == delimiter) {
                current.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                current.add(cell.toString());
                cell.setLength(0);
                addRow(rows, current);
                current = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        current.add(cell.toString());
        addRow(rows, current);
        return rows;
    }

    private static void addRow(List<String[]> rows, List<String> cells) {
        boolean empty = true;
        for (String cell : cells) {
            if (cell != null && !cell.trim().isEmpty()) {
                empty = false;
                break;
            }
        }
        if (!empty) rows.add(cells.toArray(new String[0]));
    }

    private static char detectDelimiter(String text) {
        int end = text.indexOf('\n');
        String header = end > 0 ? text.substring(0, end) : text;
        int semicolons = count(header, ';');
        int commas = count(header, ',');
        int tabs = count(header, '\t');
        if (tabs > semicolons && tabs > commas) return '\t';
        return commas > semicolons ? ',' : ';';
    }

    private static int count(String text, char c) {
        int result = 0;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == c) result++;
        return result;
    }
}
