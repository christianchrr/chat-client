package com.localai.apihost;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Converts between Word documents and a small Markdown dialect (headings, bold, italic, bullet and
 * numbered lists, tables, rules and centered lines) so a model can restyle a document.
 */
final class DocxMarkdown {
    static final String FORMAT_GUIDE = "Format: this Word document is shown as Markdown. Use # for the main title or name, "
            + "## for section headings, ### for sub-headings, **bold**, *italic*, '- ' for bullets, '1. ' for numbered "
            + "lists, '| a | b |' rows for tables, '---' for a divider line, and <center>text</center> for centered "
            + "lines (a heading can be centered too, e.g. <center># Name</center>).";

    private static final int MAX_XML_BYTES = 20_000_000;
    private static final Pattern HEADING_STYLE = Pattern.compile("(?i)heading\\s*(\\d)");
    private static final Pattern HEADING_LINE = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern BULLET_LINE = Pattern.compile("^(\\s*)[-*\u2022]\\s+(.*)$");
    private static final Pattern NUMBER_LINE = Pattern.compile("^(\\s*)\\d+[.)]\\s+(.*)$");
    private static final Pattern RULE_LINE = Pattern.compile("^\\s*([-*_])(\\s*\\1){2,}\\s*$");
    private static final Pattern CENTER_LINE = Pattern.compile("(?i)^<center>(.*)</center>$");
    private static final String W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    private DocxMarkdown() {
    }

    static String toMarkdown(byte[] docx) {
        byte[] xml = readDocumentXml(docx);
        Document document = Jsoup.parse(new String(xml, StandardCharsets.UTF_8), "", Parser.xmlParser());
        Element body = document.getElementsByTag("w:body").first();
        if (body == null) {
            throw new DropboxService.DropboxException("dropbox_file_unreadable");
        }
        StringBuilder out = new StringBuilder();
        for (Element child : body.children()) {
            if (child.tagName().equals("w:p")) {
                String line = paragraph(child);
                if (!line.isBlank()) {
                    out.append(line).append('\n');
                }
            } else if (child.tagName().equals("w:tbl")) {
                table(child, out);
            }
        }
        return out.toString();
    }

    private static byte[] readDocumentXml(byte[] docx) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if ("word/document.xml".equals(entry.getName())) {
                    byte[] xml = zip.readNBytes(MAX_XML_BYTES + 1);
                    if (xml.length > MAX_XML_BYTES) {
                        throw new DropboxService.DropboxException("dropbox_file_too_large");
                    }
                    return xml;
                }
            }
        } catch (IOException e) {
            throw new DropboxService.DropboxException("dropbox_file_unreadable", e);
        }
        throw new DropboxService.DropboxException("dropbox_file_unreadable");
    }

    private static Element child(Element parent, String tag) {
        for (Element candidate : parent.children()) {
            if (candidate.tagName().equals(tag)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean on(Element flag) {
        if (flag == null) {
            return false;
        }
        String value = flag.attr("w:val");
        return !(value.equals("0") || value.equalsIgnoreCase("false"));
    }

    private static String paragraph(Element p) {
        String style = "";
        boolean list = false;
        boolean center = false;
        Element properties = child(p, "w:pPr");
        if (properties != null) {
            Element styleElement = child(properties, "w:pStyle");
            style = styleElement == null ? "" : styleElement.attr("w:val");
            list = child(properties, "w:numPr") != null;
            Element alignment = child(properties, "w:jc");
            center = alignment != null && "center".equals(alignment.attr("w:val"));
        }
        String lowerStyle = style.toLowerCase(java.util.Locale.ROOT);
        Matcher heading = HEADING_STYLE.matcher(style);
        String prefix = "";
        boolean isHeading = false;
        if (lowerStyle.equals("title")) {
            prefix = "# ";
            isHeading = true;
        } else if (heading.find()) {
            prefix = "#".repeat(Math.min(3, Integer.parseInt(heading.group(1)))) + " ";
            isHeading = true;
        } else if (list || lowerStyle.contains("list")) {
            prefix = "- ";
        }
        String text = runs(p, isHeading).strip();
        if (text.isBlank()) {
            return "";
        }
        String line = prefix + text;
        return center ? "<center>" + line + "</center>" : line;
    }

    private record Segment(StringBuilder text, boolean bold, boolean italic) {
    }

    private static String runs(Element paragraph, boolean ignoreBold) {
        List<Segment> segments = new ArrayList<>();
        for (Element run : paragraph.getElementsByTag("w:r")) {
            Element properties = child(run, "w:rPr");
            boolean bold = !ignoreBold && properties != null && on(child(properties, "w:b"));
            boolean italic = properties != null && on(child(properties, "w:i"));
            StringBuilder text = new StringBuilder();
            for (Element part : run.children()) {
                switch (part.tagName()) {
                    case "w:t" -> text.append(part.wholeText().replace("*", "\\*"));
                    case "w:tab" -> text.append('\t');
                    case "w:br" -> text.append(' ');
                    default -> {
                    }
                }
            }
            if (text.isEmpty()) {
                continue;
            }
            Segment last = segments.isEmpty() ? null : segments.get(segments.size() - 1);
            if (last != null && last.bold == bold && last.italic == italic) {
                last.text.append(text);
            } else {
                segments.add(new Segment(text, bold, italic));
            }
        }
        StringBuilder out = new StringBuilder();
        for (Segment segment : segments) {
            String raw = segment.text.toString();
            String core = raw.strip();
            if (core.isEmpty() || !segment.bold && !segment.italic) {
                out.append(raw);
                continue;
            }
            String marker = (segment.bold ? "**" : "") + (segment.italic ? "*" : "");
            String reversed = new StringBuilder(marker).reverse().toString();
            out.append(raw, 0, raw.indexOf(core)).append(marker).append(core).append(reversed)
                    .append(raw.substring(raw.indexOf(core) + core.length()));
        }
        return out.toString();
    }

    private static void table(Element table, StringBuilder out) {
        boolean first = true;
        for (Element row : table.children()) {
            if (!row.tagName().equals("w:tr")) {
                continue;
            }
            List<String> cells = new ArrayList<>();
            for (Element cell : row.children()) {
                if (!cell.tagName().equals("w:tc")) {
                    continue;
                }
                List<String> parts = new ArrayList<>();
                for (Element p : cell.getElementsByTag("w:p")) {
                    String text = runs(p, true).strip();
                    if (!text.isBlank()) {
                        parts.add(text);
                    }
                }
                cells.add(String.join(" ", parts).replace("|", "/"));
            }
            if (cells.isEmpty()) {
                continue;
            }
            out.append("| ").append(String.join(" | ", cells)).append(" |\n");
            if (first) {
                out.append("|").append(" --- |".repeat(cells.size())).append('\n');
                first = false;
            }
        }
    }

    static byte[] fromMarkdown(String markdown) {
        StringBuilder body = new StringBuilder();
        List<Integer> orderedLists = new ArrayList<>();
        String[] lines = markdown.replace("\r\n", "\n").split("\n", -1);
        boolean inOrdered = false;
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index].stripTrailing();
            if (line.isBlank()) {
                inOrdered = false;
                continue;
            }
            if (line.stripLeading().startsWith("|")) {
                List<String> rows = new ArrayList<>();
                while (index < lines.length && lines[index].stripLeading().startsWith("|")) {
                    rows.add(lines[index].strip());
                    index++;
                }
                index--;
                body.append(table(rows));
                inOrdered = false;
                continue;
            }
            boolean center = false;
            Matcher centered = CENTER_LINE.matcher(line.strip());
            if (centered.matches()) {
                center = true;
                line = centered.group(1).strip();
            }
            Matcher heading = HEADING_LINE.matcher(line.strip());
            Matcher number = NUMBER_LINE.matcher(line);
            Matcher bullet = BULLET_LINE.matcher(line);
            if (RULE_LINE.matcher(line).matches()) {
                body.append("<w:p><w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\" "
                        + "w:color=\"A6A6A6\"/></w:pBdr></w:pPr></w:p>");
                inOrdered = false;
            } else if (heading.matches()) {
                int level = Math.min(3, heading.group(1).length());
                body.append(paragraph("Heading" + level, 0, 0, center, inline(heading.group(2), false)));
                inOrdered = false;
            } else if (number.matches()) {
                if (!inOrdered) {
                    orderedLists.add(orderedLists.size() + 2);
                    inOrdered = true;
                }
                body.append(paragraph("ListParagraph", orderedLists.get(orderedLists.size() - 1),
                        indentLevel(number.group(1)), center, inline(number.group(2), false)));
            } else if (bullet.matches()) {
                body.append(paragraph("ListParagraph", 1, indentLevel(bullet.group(1)), center,
                        inline(bullet.group(2), false)));
                inOrdered = false;
            } else {
                body.append(paragraph(null, 0, 0, center, inline(line.strip(), false)));
                inOrdered = false;
            }
        }
        if (body.isEmpty()) {
            body.append("<w:p/>");
        }
        String document = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"" + W_NS + "\"><w:body>" + body
                + "<w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/><w:pgMar w:top=\"1080\" w:right=\"1080\" "
                + "w:bottom=\"1080\" w:left=\"1080\" w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/>"
                + "</w:sectPr></w:body></w:document>";
        return zip(document, numbering(orderedLists.size()));
    }

    private static int indentLevel(String leading) {
        return leading.replace("\t", "  ").length() >= 2 ? 1 : 0;
    }

    private static String paragraph(String style, int numId, int level, boolean center, String content) {
        StringBuilder properties = new StringBuilder();
        if (style != null) {
            properties.append("<w:pStyle w:val=\"").append(style).append("\"/>");
        }
        if (numId > 0) {
            properties.append("<w:numPr><w:ilvl w:val=\"").append(level).append("\"/><w:numId w:val=\"")
                    .append(numId).append("\"/></w:numPr>");
        }
        if (center) {
            properties.append("<w:jc w:val=\"center\"/>");
        }
        return "<w:p>" + (properties.isEmpty() ? "" : "<w:pPr>" + properties + "</w:pPr>") + content + "</w:p>";
    }

    private static String table(List<String> rows) {
        List<List<String>> parsed = new ArrayList<>();
        for (String row : rows) {
            String trimmed = row.strip();
            if (trimmed.startsWith("|")) {
                trimmed = trimmed.substring(1);
            }
            if (trimmed.endsWith("|")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            List<String> cells = new ArrayList<>();
            for (String cell : trimmed.split("\\|", -1)) {
                cells.add(cell.strip());
            }
            if (cells.stream().allMatch(cell -> cell.matches(":?-{2,}:?"))) {
                continue;
            }
            parsed.add(cells);
        }
        int columns = parsed.stream().mapToInt(List::size).max().orElse(1);
        StringBuilder table = new StringBuilder("<w:tbl><w:tblPr><w:tblW w:w=\"5000\" w:type=\"pct\"/><w:tblBorders>");
        for (String edge : new String[] {"top", "left", "bottom", "right", "insideH", "insideV"}) {
            table.append("<w:").append(edge).append(" w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"BFBFBF\"/>");
        }
        table.append("</w:tblBorders><w:tblCellMar><w:top w:w=\"40\" w:type=\"dxa\"/><w:left w:w=\"100\" w:type=\"dxa\"/>"
                + "<w:bottom w:w=\"40\" w:type=\"dxa\"/><w:right w:w=\"100\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr>"
                + "<w:tblGrid>");
        for (int column = 0; column < columns; column++) {
            table.append("<w:gridCol w:w=\"").append(10080 / columns).append("\"/>");
        }
        table.append("</w:tblGrid>");
        for (int rowIndex = 0; rowIndex < parsed.size(); rowIndex++) {
            table.append("<w:tr>");
            for (int column = 0; column < columns; column++) {
                String cell = column < parsed.get(rowIndex).size() ? parsed.get(rowIndex).get(column) : "";
                table.append("<w:tc><w:tcPr><w:tcW w:w=\"").append(10080 / columns).append("\" w:type=\"dxa\"/>");
                if (rowIndex == 0) {
                    table.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"E7ECF5\"/>");
                }
                table.append("</w:tcPr><w:p>").append(inline(cell, rowIndex == 0)).append("</w:p></w:tc>");
            }
            table.append("</w:tr>");
        }
        return table.append("</w:tbl><w:p/>").toString();
    }

    private static String inline(String text, boolean forceBold) {
        StringBuilder out = new StringBuilder();
        StringBuilder buffer = new StringBuilder();
        boolean bold = false;
        boolean italic = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                buffer.append('*');
                i++;
            } else if (c == '*') {
                flush(out, buffer, bold || forceBold, italic);
                if (i + 1 < text.length() && text.charAt(i + 1) == '*') {
                    bold = !bold;
                    i++;
                } else {
                    italic = !italic;
                }
            } else {
                buffer.append(c);
            }
        }
        flush(out, buffer, bold || forceBold, italic);
        return out.toString();
    }

    private static void flush(StringBuilder out, StringBuilder buffer, boolean bold, boolean italic) {
        if (buffer.isEmpty()) {
            return;
        }
        out.append("<w:r>");
        if (bold || italic) {
            out.append("<w:rPr>").append(bold ? "<w:b/>" : "").append(italic ? "<w:i/>" : "").append("</w:rPr>");
        }
        String[] parts = buffer.toString().split("\t", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                out.append("<w:tab/>");
            }
            out.append("<w:t xml:space=\"preserve\">").append(escape(parts[i])).append("</w:t>");
        }
        out.append("</w:r>");
        buffer.setLength(0);
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                default -> {
                    if (c >= 0x20) {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static String numbering(int orderedLists) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:numbering xmlns:w=\"" + W_NS + "\">"
                + abstractNum(0, "bullet", "\u2022", "\u2013")
                + abstractNum(1, "decimal", "%1.", "%2.")
                + "<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num>");
        for (int list = 0; list < orderedLists; list++) {
            xml.append("<w:num w:numId=\"").append(list + 2).append("\"><w:abstractNumId w:val=\"1\"/>"
                    + "<w:lvlOverride w:ilvl=\"0\"><w:startOverride w:val=\"1\"/></w:lvlOverride></w:num>");
        }
        return xml.append("</w:numbering>").toString();
    }

    private static String abstractNum(int id, String format, String firstText, String secondText) {
        String secondFormat = format.equals("decimal") ? "lowerLetter" : "bullet";
        return "<w:abstractNum w:abstractNumId=\"" + id + "\"><w:multiLevelType w:val=\"hybridMultilevel\"/>"
                + "<w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/><w:numFmt w:val=\"" + format + "\"/><w:lvlText w:val=\""
                + firstText + "\"/><w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"540\" w:hanging=\"270\"/></w:pPr></w:lvl>"
                + "<w:lvl w:ilvl=\"1\"><w:start w:val=\"1\"/><w:numFmt w:val=\"" + secondFormat + "\"/><w:lvlText w:val=\""
                + secondText + "\"/><w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"1080\" w:hanging=\"270\"/></w:pPr></w:lvl>"
                + "</w:abstractNum>";
    }

    private static String styles() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:styles xmlns:w=\"" + W_NS + "\">"
                + "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" "
                + "w:eastAsia=\"Calibri\" w:cs=\"Calibri\"/><w:sz w:val=\"21\"/><w:szCs w:val=\"21\"/></w:rPr></w:rPrDefault>"
                + "<w:pPrDefault><w:pPr><w:spacing w:after=\"80\" w:line=\"259\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault>"
                + "</w:docDefaults>"
                + "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>"
                + "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/><w:basedOn w:val=\"Normal\"/>"
                + "<w:next w:val=\"Normal\"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before=\"0\" w:after=\"60\"/></w:pPr>"
                + "<w:rPr><w:b/><w:color w:val=\"1F3864\"/><w:sz w:val=\"40\"/><w:szCs w:val=\"40\"/></w:rPr></w:style>"
                + "<w:style w:type=\"paragraph\" w:styleId=\"Heading2\"><w:name w:val=\"heading 2\"/><w:basedOn w:val=\"Normal\"/>"
                + "<w:next w:val=\"Normal\"/><w:qFormat/><w:pPr><w:keepNext/><w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" "
                + "w:space=\"1\" w:color=\"2F5496\"/></w:pBdr><w:spacing w:before=\"200\" w:after=\"80\"/></w:pPr>"
                + "<w:rPr><w:b/><w:color w:val=\"2F5496\"/><w:sz w:val=\"26\"/><w:szCs w:val=\"26\"/></w:rPr></w:style>"
                + "<w:style w:type=\"paragraph\" w:styleId=\"Heading3\"><w:name w:val=\"heading 3\"/><w:basedOn w:val=\"Normal\"/>"
                + "<w:next w:val=\"Normal\"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before=\"120\" w:after=\"40\"/></w:pPr>"
                + "<w:rPr><w:b/><w:color w:val=\"262626\"/><w:sz w:val=\"23\"/><w:szCs w:val=\"23\"/></w:rPr></w:style>"
                + "<w:style w:type=\"paragraph\" w:styleId=\"ListParagraph\"><w:name w:val=\"List Paragraph\"/>"
                + "<w:basedOn w:val=\"Normal\"/><w:qFormat/><w:pPr><w:spacing w:after=\"40\"/></w:pPr></w:style>"
                + "</w:styles>";
    }

    private static byte[] zip(String document, String numbering) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                ZipOutputStream zip = new ZipOutputStream(bytes)) {
            put(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument."
                    + "wordprocessingml.document.main+xml\"/>"
                    + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument."
                    + "wordprocessingml.styles+xml\"/>"
                    + "<Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument."
                    + "wordprocessingml.numbering+xml\"/></Types>");
            put(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/"
                    + "officeDocument\" Target=\"word/document.xml\"/></Relationships>");
            put(zip, "word/_rels/document.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/"
                    + "styles\" Target=\"styles.xml\"/>"
                    + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/"
                    + "numbering\" Target=\"numbering.xml\"/></Relationships>");
            put(zip, "word/document.xml", document);
            put(zip, "word/styles.xml", styles());
            put(zip, "word/numbering.xml", numbering);
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new DropboxService.DropboxException("dropbox_unavailable", e);
        }
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
