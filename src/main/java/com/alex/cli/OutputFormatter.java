package com.alex.cli;

import com.alex.crawl.Entry;

public final class OutputFormatter {

    private OutputFormatter() {}

    public static String format(Entry e) {
        String status = e.error() != null ? "ERR" : String.format("%03d", e.status());
        String line = "[" + status + "]\t" + sanitize(labelOf(e)) + "\t" + e.url();
        if (e.error() != null) line += "\t" + sanitize(e.error());
        return line;
    }

    public static String labelOf(Entry e) {
        return e.label() == null ? "" : e.label();
    }

    private static String sanitize(String s) {
        return s.replaceAll("[\\t\\r\\n]+", " ").trim();
    }
}
