package com.localai.apihost;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DropboxServiceTest {
    @TempDir
    Path directory;

    @Test
    void editedCopiesKeepOriginalAndIncrementSuffix() throws Exception {
        DropboxService dropbox = new DropboxService(directory);
        dropbox.add("notes.txt", "one".getBytes(StandardCharsets.UTF_8));

        assertEquals("notes-edited.txt", dropbox.saveEditedCopy("notes.txt", "two"));
        assertEquals("notes-edited-2.txt", dropbox.saveEditedCopy("notes.txt", "three"));
        assertEquals("notes-edited-3.txt", dropbox.saveEditedCopy("notes-edited.txt", "four"));
        assertEquals("one", Files.readString(directory.resolve("notes.txt")));
        assertEquals("two", Files.readString(directory.resolve("notes-edited.txt")));
        assertEquals(4, dropbox.list().size());
    }

    @Test
    void rejectsPathTraversalAndUnsupportedTypes() {
        DropboxService dropbox = new DropboxService(directory);
        byte[] data = new byte[] {1};

        assertThrows(DropboxService.DropboxException.class, () -> dropbox.add("..\\evil.txt", data));
        assertThrows(DropboxService.DropboxException.class, () -> dropbox.add("../evil.txt", data));
        assertThrows(DropboxService.DropboxException.class, () -> dropbox.add("a..b.txt", data));
        assertThrows(DropboxService.DropboxException.class, () -> dropbox.add("tool.exe", data));
        assertThrows(DropboxService.DropboxException.class, () -> dropbox.readText("..\\secret.txt"));
    }

    @Test
    void duplicateUploadDoesNotOverwrite() throws Exception {
        DropboxService dropbox = new DropboxService(directory);
        dropbox.add("a.csv", "x,y".getBytes(StandardCharsets.UTF_8));
        DropboxService.DropboxFile second = dropbox.add("a.csv", "p,q".getBytes(StandardCharsets.UTF_8));

        assertEquals("a-copy.csv", second.name());
        assertEquals("x,y", Files.readString(directory.resolve("a.csv")));
    }

    @Test
    void pdfIsReadableButNotEditable() {
        DropboxService dropbox = new DropboxService(directory);
        assertTrue(DropboxService.isSupported("x.pdf"));
        assertTrue(!DropboxService.isEditable("x.pdf"));
        dropbox.add("x.pdf", new byte[] {1});
        assertThrows(DropboxService.DropboxException.class, () -> dropbox.saveEditedCopy("x.pdf", "text"));
    }

    @Test
    void xlsxIsReadableAsTabSeparatedSheets() throws Exception {
        DropboxService dropbox = new DropboxService(directory);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            put(zip, "xl/workbook.xml", "<workbook xmlns:r=\"r\"><sheets>"
                    + "<sheet name=\"Budget\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            put(zip, "xl/_rels/workbook.xml.rels", "<Relationships>"
                    + "<Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\"/></Relationships>");
            put(zip, "xl/sharedStrings.xml", "<sst><si><t>Item</t></si><si><t>Cost</t></si>"
                    + "<si><r><t>Pen</t></r></si></sst>");
            put(zip, "xl/worksheets/sheet1.xml", "<worksheet><sheetData>"
                    + "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c></row>"
                    + "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>2</v></c><c r=\"C2\"><v>4.5</v></c></row>"
                    + "</sheetData></worksheet>");
        }
        dropbox.add("budget.xlsx", bytes.toByteArray());

        String text = dropbox.readText("budget.xlsx");

        assertTrue(text.contains("## Sheet: Budget"));
        assertTrue(text.contains("Item\tCost"));
        assertTrue(text.contains("Pen\t\t4.5"));
        assertTrue(!DropboxService.isEditable("budget.xlsx"));
    }

    private static void put(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    @Test
    void docxRoundTripsEditedText() throws Exception {
        DropboxService dropbox = new DropboxService(directory);
        String xml = "<?xml version=\"1.0\"?><w:document xmlns:w=\"w\"><w:body>"
                + "<w:p><w:r><w:t>Old text</w:t></w:r></w:p><w:sectPr><w:pgSz/></w:sectPr></w:body></w:document>";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(xml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        dropbox.add("report.docx", bytes.toByteArray());

        String name = dropbox.saveEditedCopy("report.docx", "Hello & <world>\nSecond line");

        assertEquals("report-edited.docx", name);
        assertEquals("Hello & <world>\nSecond line", dropbox.readText(name).strip());
        assertTrue(dropbox.readText("report.docx").contains("Old text"));
    }

    @Test
    void docxKeepsStructureAndStyling() throws Exception {
        DropboxService dropbox = new DropboxService(directory);
        String xml = "<?xml version=\"1.0\"?><w:document xmlns:w=\"w\"><w:body>"
                + "<w:p><w:pPr><w:pStyle w:val=\"Title\"/><w:jc w:val=\"center\"/></w:pPr><w:r><w:t>Jane Doe</w:t></w:r></w:p>"
                + "<w:p><w:pPr><w:pStyle w:val=\"Heading2\"/></w:pPr><w:r><w:t>Experience</w:t></w:r></w:p>"
                + "<w:p><w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr></w:pPr>"
                + "<w:r><w:rPr><w:b/></w:rPr><w:t>Led</w:t></w:r><w:r><w:t xml:space=\"preserve\"> a team</w:t></w:r></w:p>"
                + "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>Skill</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Level</w:t></w:r></w:p></w:tc></w:tr>"
                + "<w:tr><w:tc><w:p><w:r><w:t>Java</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Expert</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"
                + "</w:body></w:document>";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            put(zip, "word/document.xml", xml);
        }
        dropbox.add("resume.docx", bytes.toByteArray());

        String markdown = dropbox.readText("resume.docx");
        assertTrue(markdown.contains("<center># Jane Doe</center>"));
        assertTrue(markdown.contains("## Experience"));
        assertTrue(markdown.contains("- **Led** a team"));
        assertTrue(markdown.contains("| Skill | Level |"));
        assertTrue(markdown.contains("| Java | Expert |"));

        String restyled = "<center># Jane Doe</center>\n---\n## Summary\nSeasoned *engineer* with **10 years** of work.\n"
                + "- Built \\*things\\*\n  - sub point\n1. First\n2. Second\n\n| A | B |\n|---|---|\n| 1 | 2 |\n";
        String name = dropbox.saveEditedCopy("resume.docx", restyled);

        byte[] output = Files.readAllBytes(directory.resolve(name));
        String documentXml = null;
        String stylesXml = null;
        try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(output))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);
                factory.newDocumentBuilder().parse(new org.xml.sax.InputSource(new java.io.StringReader(content)));
                if (entry.getName().equals("word/document.xml")) {
                    documentXml = content;
                }
                if (entry.getName().equals("word/styles.xml")) {
                    stylesXml = content;
                }
            }
        }
        assertTrue(documentXml.contains("w:val=\"Heading1\"") && documentXml.contains("w:jc w:val=\"center\""));
        assertTrue(documentXml.contains("<w:b/>") && documentXml.contains("<w:i/>"));
        assertTrue(documentXml.contains("<w:tbl>") && documentXml.contains("<w:numId w:val=\"2\"/>"));
        assertTrue(stylesXml.contains("Heading2"));
        String roundTrip = dropbox.readText(name);
        assertTrue(roundTrip.contains("<center># Jane Doe</center>"));
        assertTrue(roundTrip.contains("Seasoned *engineer* with **10 years** of work."));
        assertTrue(roundTrip.contains("| A | B |"));
    }
}