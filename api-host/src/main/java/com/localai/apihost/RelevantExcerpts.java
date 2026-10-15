package com.localai.apihost;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Picks the parts of a long document that best match a question, so small models see what matters. */
final class RelevantExcerpts {
    static final String GAP = "\n[...]\n";
    private static final int CHUNK_CHARS = 1_200;
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]{3,}");
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "that", "this", "with", "from", "are", "was", "what", "when", "where", "which",
            "who", "how", "why", "can", "could", "would", "should", "please", "about", "into", "your", "you",
            "have", "has", "does", "did", "tell", "give", "show", "file", "document", "read", "summarize", "summary");

    private RelevantExcerpts() {
    }

    static String select(String text, String query, int budget) {
        if (text.length() <= budget) {
            return text;
        }
        List<String> chunks = chunk(text);
        List<String> terms = terms(query);
        if (terms.isEmpty()) {
            return text.substring(0, budget);
        }
        Map<String, Integer> documentFrequency = new HashMap<>();
        List<Map<String, Integer>> counts = new ArrayList<>();
        for (String chunk : chunks) {
            Map<String, Integer> inChunk = new HashMap<>();
            String lower = chunk.toLowerCase(Locale.ROOT);
            for (String term : terms) {
                int count = 0;
                for (int at = lower.indexOf(term); at >= 0; at = lower.indexOf(term, at + term.length())) {
                    count++;
                }
                if (count > 0) {
                    inChunk.put(term, count);
                    documentFrequency.merge(term, 1, Integer::sum);
                }
            }
            counts.add(inChunk);
        }
        double[] scores = new double[chunks.size()];
        for (int i = 0; i < chunks.size(); i++) {
            for (Map.Entry<String, Integer> entry : counts.get(i).entrySet()) {
                double idf = Math.log(1 + (double) chunks.size() / documentFrequency.get(entry.getKey()));
                scores[i] += idf * Math.min(entry.getValue(), 3);
            }
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingDouble((Integer i) -> scores[i]).reversed().thenComparingInt(i -> i));
        java.util.TreeSet<Integer> chosen = new java.util.TreeSet<>();
        int used = 0;
        boolean anyMatch = scores[order.get(0)] > 0;
        List<Integer> candidates = new ArrayList<>(order);
        if (!anyMatch) {
            return text.substring(0, budget);
        }
        // The opening usually holds the title and context, so keep it when there is room.
        if (chunks.get(0).length() <= budget / 4) {
            chosen.add(0);
            used += chunks.get(0).length();
        }
        for (int index : candidates) {
            if (scores[index] <= 0) {
                break;
            }
            int cost = chunks.get(index).length() + GAP.length();
            if (!chosen.contains(index) && used + cost <= budget) {
                chosen.add(index);
                used += cost;
            }
        }
        StringBuilder result = new StringBuilder();
        int previous = -1;
        for (int index : chosen) {
            if (previous >= 0 && index != previous + 1) {
                result.append(GAP);
            }
            result.append(chunks.get(index));
            previous = index;
        }
        return result.toString();
    }

    private static List<String> terms(String query) {
        List<String> terms = new ArrayList<>();
        if (query == null) {
            return terms;
        }
        var matcher = WORD.matcher(query.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String word = matcher.group();
            if (!STOP_WORDS.contains(word) && !terms.contains(word)) {
                terms.add(word);
            }
        }
        return terms;
    }

    private static List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            while (line.length() > CHUNK_CHARS) {
                if (!current.isEmpty()) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }
                chunks.add(line.substring(0, CHUNK_CHARS));
                line = line.substring(CHUNK_CHARS);
            }
            if (current.length() + line.length() + 1 > CHUNK_CHARS && !current.isEmpty()) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append('\n');
            }
            current.append(line);
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString());
        }
        return chunks;
    }
}