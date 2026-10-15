package com.localai.desktop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DropboxEditExtractionTest {
    @Test
    void extractsLastFencedBlock() {
        String reply = "Done.\n```text\nfirst\n```\nnote\n```csv\na,b\n1,2\n```";
        assertEquals("a,b\n1,2", DesktopController.extractFencedContent(reply));
    }

    @Test
    void returnsNullWithoutFence() {
        assertNull(DesktopController.extractFencedContent("no code here"));
    }

    @Test
    void detectsMentionedFilesAndEditIntent() {
        var notes = new LocalApiClient.DropboxFile("notes.txt", 3, true);
        var edited = new LocalApiClient.DropboxFile("notes-edited.txt", 3, true);
        var budget = new LocalApiClient.DropboxFile("Budget.xlsx", 9, false);
        var all = java.util.List.of(notes, edited, budget);

        assertEquals(java.util.List.of(notes), DesktopController.mentionedFiles("please fix notes.txt", all));
        assertEquals(java.util.List.of(edited), DesktopController.mentionedFiles("summarize notes-edited", all));
        assertEquals(java.util.List.of(budget), DesktopController.mentionedFiles("what is in budget?", all));
        assertEquals(java.util.List.of(), DesktopController.mentionedFiles("hello there", all));
        org.junit.jupiter.api.Assertions.assertTrue(DesktopController.looksLikeEditRequest("Fix the typos"));
        org.junit.jupiter.api.Assertions.assertFalse(DesktopController.looksLikeEditRequest("What is in it?"));
    }
}