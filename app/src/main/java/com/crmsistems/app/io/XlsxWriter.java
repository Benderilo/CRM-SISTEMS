package com.crmsistems.app.io;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Минимальный генератор файлов .xlsx (Excel) без внешних библиотек.
 * Книга собирается как zip-архив с XML внутри — этого достаточно, чтобы файл
 * открывался в Excel, LibreOffice и Google Таблицах.
 */
public final class XlsxWriter {

    /** Один лист книги. */
    public static class Sheet {
        public final String name;
        public final String[] headers;
        public final List<Object[]> rows;

        public Sheet(String name, String[] headers, List<Object[]> rows) {
            this.name = name;
            this.headers = headers;
            this.rows = rows;
        }
    }

    private XlsxWriter() {
    }

    public static void write(OutputStream target, List<Sheet> sheets) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(target));
        try {
            put(zip, "[Content_Types].xml", contentTypes(sheets.size()));
            put(zip, "_rels/.rels",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                            + "</Relationships>");
            put(zip, "xl/workbook.xml", workbook(sheets));
            put(zip, "xl/_rels/workbook.xml.rels", workbookRels(sheets.size()));
            for (int i = 0; i < sheets.size(); i++) {
                put(zip, "xl/worksheets/sheet" + (i + 1) + ".xml", sheet(sheets.get(i)));
            }
        } finally {
            zip.finish();
            zip.flush();
        }
    }

    private static void put(ZipOutputStream zip, String path, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String contentTypes(int sheetCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                .append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
                .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
                .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
                .append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>");
        for (int i = 1; i <= sheetCount; i++) {
            sb.append("<Override PartName=\"/xl/worksheets/sheet").append(i)
                    .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        }
        return sb.append("</Types>").toString();
    }

    private static String workbook(List<Sheet> sheets) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                .append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" ")
                .append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
        for (int i = 0; i < sheets.size(); i++) {
            sb.append("<sheet name=\"").append(escape(sheetName(sheets.get(i).name)))
                    .append("\" sheetId=\"").append(i + 1)
                    .append("\" r:id=\"rId").append(i + 1).append("\"/>");
        }
        return sb.append("</sheets></workbook>").toString();
    }

    private static String workbookRels(int sheetCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                .append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 1; i <= sheetCount; i++) {
            sb.append("<Relationship Id=\"rId").append(i)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                    .append(i).append(".xml\"/>");
        }
        return sb.append("</Relationships>").toString();
    }

    private static String sheet(Sheet sheet) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");

        int rowIndex = 1;
        sb.append("<row r=\"").append(rowIndex).append("\">");
        for (int c = 0; c < sheet.headers.length; c++) {
            appendCell(sb, c, rowIndex, sheet.headers[c]);
        }
        sb.append("</row>");

        for (Object[] row : sheet.rows) {
            rowIndex++;
            sb.append("<row r=\"").append(rowIndex).append("\">");
            for (int c = 0; c < row.length; c++) {
                appendCell(sb, c, rowIndex, row[c]);
            }
            sb.append("</row>");
        }
        return sb.append("</sheetData></worksheet>").toString();
    }

    private static void appendCell(StringBuilder sb, int column, int row, Object value) {
        if (value == null) return;
        String ref = columnName(column) + row;
        if (value instanceof Number) {
            sb.append("<c r=\"").append(ref).append("\"><v>")
                    .append(numeric((Number) value)).append("</v></c>");
        } else {
            String text = String.valueOf(value);
            if (text.isEmpty()) return;
            sb.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                    .append(escape(text)).append("</t></is></c>");
        }
    }

    private static String numeric(Number number) {
        double value = number.doubleValue();
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }

    /** A, B, ... Z, AA, AB ... */
    private static String columnName(int index) {
        StringBuilder sb = new StringBuilder();
        int value = index;
        while (value >= 0) {
            sb.insert(0, (char) ('A' + value % 26));
            value = value / 26 - 1;
        }
        return sb.toString();
    }

    private static String sheetName(String name) {
        String result = name.replaceAll("[\\\\/*?:\\[\\]]", " ");
        return result.length() > 31 ? result.substring(0, 31) : result;
    }

    private static String escape(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                case '\'':
                    sb.append("&apos;");
                    break;
                default:
                    // управляющие символы Excel не принимает
                    if (c < 0x20 && c != '\n' && c != '\t') sb.append(' ');
                    else sb.append(c);
            }
        }
        return sb.toString();
    }
}
