package com.crmsistems.app.io;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Чтение книги .xlsx без внешних библиотек — пара к {@link XlsxWriter}.
 * <p>
 * Книга Excel это zip с XML внутри: список листов лежит в xl/workbook.xml,
 * сами таблицы — в xl/worksheets/*.xml, а текст ячеек Excel обычно выносит
 * в общий словарь xl/sharedStrings.xml. Читаем всё три части.
 * <p>
 * Понимаем и то, что пишем сами, и то, что сохранил Excel, LibreOffice
 * или Google Таблицы после правки файла человеком.
 */
public final class XlsxReader {

    /** Лист книги: имя и строки ячеек (первая строка — заголовки). */
    public static class Sheet {
        public final String name;
        public final List<String[]> rows;

        Sheet(String name, List<String[]> rows) {
            this.name = name;
            this.rows = rows;
        }
    }

    /** Больше этого в память не берём — защита от подсунутого огромного файла. */
    private static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;

    private XlsxReader() {
    }

    public static List<Sheet> read(InputStream source) throws Exception {
        Map<String, byte[]> parts = unzip(source);
        if (!parts.containsKey("xl/workbook.xml")) {
            throw new IllegalArgumentException("Это не книга Excel (.xlsx)");
        }

        List<String> shared = parts.containsKey("xl/sharedStrings.xml")
                ? sharedStrings(parts.get("xl/sharedStrings.xml"))
                : new ArrayList<>();
        Map<String, String> rels = parts.containsKey("xl/_rels/workbook.xml.rels")
                ? relations(parts.get("xl/_rels/workbook.xml.rels"))
                : new HashMap<>();

        List<Sheet> sheets = new ArrayList<>();
        int fallbackIndex = 0;
        for (Map.Entry<String, String> entry : sheetList(parts.get("xl/workbook.xml")).entrySet()) {
            fallbackIndex++;
            String path = rels.get(entry.getValue());
            // Некоторые редакторы не заполняют r:id — тогда берём листы по порядку.
            if (path == null) path = "worksheets/sheet" + fallbackIndex + ".xml";
            byte[] data = parts.get("xl/" + path);
            if (data == null) data = parts.get(path);
            if (data == null) continue;
            sheets.add(new Sheet(entry.getKey(), rows(data, shared)));
        }
        if (sheets.isEmpty()) throw new IllegalArgumentException("В книге нет листов");
        return sheets;
    }

    // ------------------------------------------------------------------ разбор

    private static Map<String, byte[]> unzip(InputStream source) throws IOException {
        Map<String, byte[]> parts = new HashMap<>();
        long total = 0;
        ZipInputStream zip = new ZipInputStream(source);
        try {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while ((read = zip.read(chunk)) > 0) {
                    total += read;
                    if (total > MAX_TOTAL_BYTES) {
                        throw new IOException("Файл слишком большой");
                    }
                    buffer.write(chunk, 0, read);
                }
                parts.put(entry.getName().replace('\\', '/'), buffer.toByteArray());
            }
        } finally {
            zip.close();
        }
        return parts;
    }

    /** Имена листов в порядке книги и их r:id. */
    private static Map<String, String> sheetList(byte[] workbook) throws Exception {
        Map<String, String> sheets = new LinkedHashMap<>();
        XmlPullParser p = parser(workbook);
        int event;
        int unnamed = 0;
        while ((event = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && "sheet".equals(p.getName())) {
                String name = attr(p, "name");
                if (name == null || name.isEmpty()) name = "Лист" + (++unnamed);
                String id = attr(p, "id"); // r:id
                // Одинаковые имена листов Excel не допускает, но подстрахуемся.
                while (sheets.containsKey(name)) name = name + " ";
                sheets.put(name, id == null ? "" : id);
            }
        }
        return sheets;
    }

    /** rId → путь к файлу листа. */
    private static Map<String, String> relations(byte[] xml) throws Exception {
        Map<String, String> rels = new HashMap<>();
        XmlPullParser p = parser(xml);
        int event;
        while ((event = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && "Relationship".equals(p.getName())) {
                String id = attr(p, "Id");
                String target = attr(p, "Target");
                if (id != null && target != null) {
                    if (target.startsWith("/xl/")) target = target.substring(4);
                    if (target.startsWith("/")) target = target.substring(1);
                    rels.put(id, target);
                }
            }
        }
        return rels;
    }

    /** Общий словарь текстов: ячейка с t="s" ссылается сюда номером. */
    private static List<String> sharedStrings(byte[] xml) throws Exception {
        List<String> out = new ArrayList<>();
        XmlPullParser p = parser(xml);
        StringBuilder current = null;
        int event;
        while ((event = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                if ("si".equals(p.getName())) current = new StringBuilder();
            } else if (event == XmlPullParser.TEXT && current != null) {
                current.append(p.getText());
            } else if (event == XmlPullParser.END_TAG && "si".equals(p.getName())) {
                out.add(current == null ? "" : current.toString());
                current = null;
            }
        }
        return out;
    }

    /** Строки листа. Пропуски столбцов заполняем пустыми ячейками по адресу (A1, C1…). */
    private static List<String[]> rows(byte[] xml, List<String> shared) throws Exception {
        List<String[]> rows = new ArrayList<>();
        XmlPullParser p = parser(xml);

        List<String> row = null;
        int column = -1;
        String type = null;
        StringBuilder text = null;
        boolean insideValue = false;

        int event;
        while ((event = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String tag = p.getName();
                if ("row".equals(tag)) {
                    row = new ArrayList<>();
                } else if ("c".equals(tag) && row != null) {
                    type = attr(p, "t");
                    column = columnOf(attr(p, "r"), row.size());
                    text = new StringBuilder();
                } else if (("v".equals(tag) || "t".equals(tag)) && text != null) {
                    insideValue = true;
                }
            } else if (event == XmlPullParser.TEXT && insideValue && text != null) {
                text.append(p.getText());
            } else if (event == XmlPullParser.END_TAG) {
                String tag = p.getName();
                if ("v".equals(tag) || "t".equals(tag)) {
                    insideValue = false;
                } else if ("c".equals(tag) && row != null && text != null) {
                    while (row.size() < column) row.add("");
                    row.add(cellValue(text.toString(), type, shared));
                    text = null;
                    type = null;
                } else if ("row".equals(tag) && row != null) {
                    rows.add(row.toArray(new String[0]));
                    row = null;
                }
            }
        }
        return rows;
    }

    private static String cellValue(String raw, String type, List<String> shared) {
        String value = raw.trim();
        if ("s".equals(type)) { // ссылка в общий словарь
            try {
                int index = Integer.parseInt(value);
                return index >= 0 && index < shared.size() ? shared.get(index) : "";
            } catch (NumberFormatException e) {
                return "";
            }
        }
        if ("b".equals(type)) return value.equals("1") ? "да" : "нет";
        return value;
    }

    /** «C7» → 2. Если адреса нет, ячейка идёт следующей по порядку. */
    private static int columnOf(String ref, int fallback) {
        if (ref == null || ref.isEmpty()) return fallback;
        int index = 0;
        for (int i = 0; i < ref.length(); i++) {
            char c = Character.toUpperCase(ref.charAt(i));
            if (c < 'A' || c > 'Z') break;
            index = index * 26 + (c - 'A' + 1);
        }
        return index > 0 ? index - 1 : fallback;
    }

    private static XmlPullParser parser(byte[] xml) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(new ByteArrayInputStream(xml), StandardCharsets.UTF_8.name());
        return p;
    }

    /** Атрибут по имени без учёта пространства имён: r:id читается как id. */
    private static String attr(XmlPullParser p, String name) {
        for (int i = 0; i < p.getAttributeCount(); i++) {
            String attr = p.getAttributeName(i);
            int colon = attr.indexOf(':');
            if (colon >= 0) attr = attr.substring(colon + 1);
            if (attr.equals(name)) return p.getAttributeValue(i);
        }
        return null;
    }
}
