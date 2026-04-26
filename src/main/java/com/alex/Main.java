package com.alex;

import com.alex.cli.Args;
import com.alex.cli.OutputFormatter;
import com.alex.cli.OutputSink;
import com.alex.crawl.Crawler;
import com.alex.crawl.Entry;
import com.alex.http.HttpFetcher;
import com.alex.url.DomainPolicy;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

public class Main {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("ddMMyyyy-HHmmss");

    public static void main(String[] args) {
        Args arguments = Args.parse(args);
        if (arguments.help()) {
            System.out.println(Args.usage());
            return;
        }

        DomainPolicy policy = new DomainPolicy(arguments.seed(), arguments.excludeSubdomains());
        HttpFetcher fetcher = new HttpFetcher(arguments.maxBytes(), arguments.timeout());
        Crawler crawler = new Crawler(fetcher, policy, arguments.maxPages(), arguments.maxConcurrency());

        long t0 = System.nanoTime();
        List<Entry> entries = crawler.crawl(arguments.seed());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.err.printf("crawl finished: %d entries in %d ms%n", entries.size(), ms);

        List<Entry> sorted = entries.stream()
                .sorted(Comparator
                        .comparing(OutputFormatter::labelOf, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(e -> e.url().toString()))
                .toList();

        Path file = arguments.noFile() ? null
                : (arguments.outputFile() != null
                ? arguments.outputFile()
                : Path.of("crawl-" + LocalDateTime.now().format(TS) + ".txt"));

        try (OutputSink sink = OutputSink.open(file)) {
            for (Entry e : sorted) sink.writeLine(OutputFormatter.format(e));
            if (sink.wroteToFile()) {
                System.err.println("wrote " + sorted.size() + " entries to " + file);
            }
        }
    }
}