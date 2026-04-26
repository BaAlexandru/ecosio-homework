package com.alex.cli;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

public record Args(URI seed,
                   int maxPages,
                   int maxConcurrency,
                   Duration timeout,
                   long maxBytes,
                   boolean excludeSubdomains,
                   Path outputFile,
                   boolean noFile,
                   boolean help) {

    public static Args parse(String[] argv) {
        URI seed = null;
        int maxPages = 1000, maxConc = 32, timeoutSec = 15;
        long maxBytes = 5L * 1024 * 1024;
        boolean exclude = false, noFile = false, help = false;
        Path output = null;

        for (String s : argv) {
            switch (s) {
                case "--help", "-h" -> help = true;
                case "--exclude-subdomains" -> exclude = true;
                case "--no-file" -> noFile = true;
                default -> {
                    if (s.startsWith("--max-pages=")) maxPages = parseInt(s);
                    else if (s.startsWith("--max-concurrency=")) maxConc = parseInt(s);
                    else if (s.startsWith("--timeout=")) timeoutSec = parseInt(s);
                    else if (s.startsWith("--max-bytes=")) maxBytes = parseLong(s);
                    else if (s.startsWith("--output=")) output = Path.of(after(s));
                    else if (s.startsWith("--")) die("unknown option: " + s);
                    else if (seed == null) seed = URI.create(s);
                    else die("unexpected positional arg: " + s);
                }
            }
        }
        if (!help && seed == null) die("missing <seed-url>");
        return new Args(seed, maxPages, maxConc, Duration.ofSeconds(timeoutSec),
                maxBytes, exclude, output, noFile, help);
    }

    public static String usage() {
        return """
                Usage: ecosio-crawler <seed-url> [options]
                  --max-pages=N          cap pages visited (default 1000)
                  --max-concurrency=N    cap in-flight requests per origin (default 64)
                  --timeout=N            per-request timeout seconds (default 15)
                  --max-bytes=N          response body cap in bytes (default 5242880)
                  --exclude-subdomains   treat sub.example.com as out-of-scope when seed=example.com
                  --output=<path>        write to <path> instead of crawl-<timestamp>.txt
                  --no-file              skip file output (stdout only)
                  --help, -h             print this and exit
                """;
    }

    private static String after(String s) {
        return s.substring(s.indexOf('=') + 1);
    }

    private static int parseInt(String s) {
        return Integer.parseInt(after(s));
    }

    private static long parseLong(String s) {
        return Long.parseLong(after(s));
    }

    private static void die(String msg) {
        System.err.println(msg);
        System.exit(2);
    }
}
