package com.alex.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Print lines to stdout and (optionally) a file. Stdout is mandatory.
 * File output is best-effort: open-failure or mid-stream write-failure
 * is logged once on stderr and the file branch is disabled for the
 * remainder of the run. Stdout output continues either way.
 */
public final class OutputSink implements AutoCloseable {

    private final PrintWriter file;
    private boolean fileBroken;

    public static OutputSink open(Path path) {
        if (path == null) return new OutputSink(null);
        try {
            PrintWriter pw = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8));
            return new OutputSink(pw);
        } catch (IOException e) {
            System.err.println("warning: could not open " + path + " (" + e.getMessage() + "); stdout only");
            return new OutputSink(null);
        }
    }

    private OutputSink(PrintWriter file) { this.file = file; }

    public void writeLine(String line) {
        System.out.println(line);
        if (file == null || fileBroken) return;
        file.println(line);
        if (file.checkError()) {
            System.err.println("warning: file write failed; further file output suppressed");
            fileBroken = true;
        }
    }

    public boolean wroteToFile() { return file != null && !fileBroken; }

    @Override
    public void close() {
        if (file != null) file.close();
    }
}
