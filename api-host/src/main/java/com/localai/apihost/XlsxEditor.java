package com.localai.apihost;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Applies edits made to the tab-separated sheet text back onto the original workbook, so styles, widths,
 * formulas in untouched cells, and other formatting are preserved.
 */
final class XlsxEditor {
    static final String FORMAT_GUIDE = "[The file is Excel, shown as one \"## Sheet: name\" block per sheet. Each line is "
            + "one row (line 1 is row 1) and cells are separated by tabs. Formula cells show their calculated values. "
            + "To change the workbook, reply with the same blocks and only change the cells you need. "
            + "Start a cell with = to write a formula.]";
    private static final int MAX_COLUMNS = 200;

    private XlsxEditor() {
    }

    static byte[] apply(byte[] original, String editedText) {
        Map<String, List<String[]>> before = parse(DropboxService.extractXlsx(original));
        Map<String, List<String[]>> after = parse(editedText);
        if (after.isEmpty()) {
            throw new DropboxService.DropboxException("dropbox_edit_content_required");
        }
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(original))) {
            for (Map.Entry<String, List<String[]>> entry : after.entrySet()) {
                Sheet sheet = workbook.getSheet(entry.getKey());
                List<String[]> oldRows = before.get(entry.getKey());
                if (sheet == null || oldRows == null) {
                    continue;
                }
                applySheet(sheet, oldRows, entry.getValue());
            }
            workbook.setForceFormulaRecalculation(true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException | RuntimeException e) {
            if (e instanceof DropboxService.DropboxException dropboxException) {
                throw dropboxException;
            }
            throw new DropboxService.DropboxException("dropbox_file_unreadable", e);
        }
    }

    private static void applySheet(Sheet sheet, List<String[]> oldRows, List<String[]> newRows) {
        int rowCount = Math.max(oldRows.size(), newRows.size());
        for (int r = 0; r < rowCount; r++) {
            String[] oldRow = r < oldRows.size() ? oldRows.get(r) : new String[0];
            String[] newRow = r < newRows.size() ? newRows.get(r) : new String[0];
            int columns = Math.min(MAX_COLUMNS, Math.max(oldRow.length, newRow.length));
            for (int c = 0; c < columns; c++) {
                String oldValue = c < oldRow.length ? oldRow[c] : "";
                String newValue = c < newRow.length ? newRow[c] : "";
                if (!oldValue.equals(newValue)) {
                    setCell(sheet, r, c, newValue);
                }
            }
        }
    }

    private static void setCell(Sheet sheet, int rowIndex, int columnIndex, String value) {
        Row row = sheet.getRow(rowIndex);
        if (row == null) {
            if (value.isEmpty()) {
                return;
            }
            row = sheet.createRow(rowIndex);
        }
        Cell cell = row.getCell(columnIndex);
        if (cell == null) {
            if (value.isEmpty()) {
                return;
            }
            cell = row.createCell(columnIndex);
            Row above = rowIndex > 0 ? sheet.getRow(rowIndex - 1) : null;
            Cell template = above == null ? null : above.getCell(columnIndex);
            if (template != null) {
                cell.setCellStyle(template.getCellStyle());
            }
        }
        boolean wasText = cell.getCellType() == CellType.STRING;
        if (value.isEmpty()) {
            cell.setBlank();
        } else if (value.startsWith("=") && value.length() > 1) {
            cell.setCellFormula(value.substring(1));
        } else if (!wasText && isNumber(value)) {
            cell.setCellValue(Double.parseDouble(value.strip()));
        } else {
            cell.setCellValue(value);
        }
    }

    private static boolean isNumber(String value) {
        String text = value.strip();
        if (text.isEmpty() || text.length() > 30 || text.toLowerCase(Locale.ROOT).matches(".*[a-df-z].*")) {
            return false;
        }
        try {
            double parsed = Double.parseDouble(text);
            return !Double.isNaN(parsed) && !Double.isInfinite(parsed);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static Map<String, List<String[]>> parse(String text) {
        Map<String, List<String[]>> sheets = new LinkedHashMap<>();
        List<String[]> current = null;
        for (String line : text.split("\\r?\\n", -1)) {
            if (line.startsWith("## Sheet: ")) {
                current = new ArrayList<>();
                sheets.put(line.substring("## Sheet: ".length()).strip(), current);
            } else if (current != null && !line.startsWith("[Sheet truncated")) {
                current.add(line.split("\t", -1));
            }
        }
        for (List<String[]> rows : sheets.values()) {
            while (!rows.isEmpty() && rows.get(rows.size() - 1).length == 1
                    && rows.get(rows.size() - 1)[0].isEmpty()) {
                rows.remove(rows.size() - 1);
            }
        }
        return sheets;
    }
}