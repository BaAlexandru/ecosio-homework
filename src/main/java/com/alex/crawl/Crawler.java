package com.alex.crawl;

import com.alex.html.Link;
import com.alex.html.LinkExtractor;
import com.alex.http.FetchResult;
import com.alex.http.HttpFetcher;
import com.alex.url.DomainPolicy;
import com.alex.url.UrlNormalizer;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Crawler {

    private static final Pattern CHARSET_IN_CT =
            Pattern.compile("(?i)charset\\s*=\\s*\"?([A-Za-z0-9_\\-]+)\"?");
    private static final int DEFAULT_CONCURRENCY = 64;

    private final HttpFetcher fetcher;
    private final DomainPolicy policy;
    private final int maxPages;
    private final int perOriginCap;
    private final Map<String, Semaphore> capByOrigin = new ConcurrentHashMap<>();

    public Crawler(HttpFetcher fetcher, DomainPolicy policy, int maxPages) {
        this(fetcher, policy, maxPages, DEFAULT_CONCURRENCY);
    }

    public Crawler(HttpFetcher fetcher, DomainPolicy policy, int maxPages, int perOriginConcurrency) {
        if (perOriginConcurrency < 1) throw new IllegalArgumentException("concurrency must be >= 1");
        this.fetcher = fetcher;
        this.policy = policy;
        this.maxPages = maxPages;
        this.perOriginCap = perOriginConcurrency;
    }

    public List<Entry> crawl(URI seed) {
        URI normalizedSeed = UrlNormalizer.normalize(seed)
                .orElseThrow(() -> new IllegalArgumentException("Seed cannot be crawled: " + seed));

        Set<URI> visited = ConcurrentHashMap.newKeySet();
        ConcurrentLinkedQueue<Entry> entries = new ConcurrentLinkedQueue<>();
        AtomicInteger inFlight = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            //

            if (visited.add(normalizedSeed)) {
                inFlight.incrementAndGet(); // pre-incremented by parent
                executor.submit(() -> visit(normalizedSeed, "<seed>", visited, entries, inFlight, done, executor));
            }

            try {
                done.await();  // block until inFlight hits 0
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // try-with-resources close() runs shutdown() + awaitTermination(forever).
            // Safe because inFlight==0 means no live tasks could submit children.
        }

        return List.copyOf(entries);
    }

    private void visit(URI url, String label,
                       Set<URI> visited, ConcurrentLinkedQueue<Entry> entries,
                       AtomicInteger inFlight, CountDownLatch done,
                       ExecutorService exec) {
        try {
            FetchResult result;
            Semaphore cap = capFor(url);
            try {
                cap.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            try {
                result = fetcher.fetch(url);
            } finally {
                cap.release();
            }

            // Every URL we reach gets recorded — even non-2xx, even non-HTML.
            // That's the "collect every URL regardless of status" contract (D-0.6).
            entries.add(new Entry(
                    result.finalUrl(),
                    result.status(),
                    label,
                    result.fallbackUsed(),
                    result.error()
            ));

            if (!result.shouldRecurse()) {
                return;
            }

            String body = decodeBody(result.body(), result.contentType());

            for (Link link : LinkExtractor.extract(result.finalUrl(), body)) {
                URI childUrl = link.url();

                if (!policy.isInScope(childUrl)) {
                    continue;
                }
                if (visited.size() >= maxPages) {
                    continue;
                }
                if (!visited.add(childUrl)) {
                    continue;
                }

                inFlight.incrementAndGet();

                exec.submit(() -> visit(childUrl, link.label(), visited, entries, inFlight, done, exec));
            }
        } finally {
            // The single decrement is matched 1:1 with the parent's pre-increment.
            // The thread that takes inFlight to 0 is the one that's allowed to
            // signal completion — even if it's the seed's own task (zero links).
            if (inFlight.decrementAndGet() == 0) {
                done.countDown();
            }
        }
    }

    /**
     * Decode response bytes using {@code Content-Type}'s charset, falling back
     * to UTF-8. This is best-effort: malformed Content-Type headers and
     * unrecognized charset names both silently fall back to UTF-8.
     * Meta-charset detection in the body itself is out of scope (D-4.8).
     */
    private static String decodeBody(byte[] bytes, String contentType) {
        Charset charset = StandardCharsets.UTF_8;
        if (contentType != null) {
            Matcher matcher = CHARSET_IN_CT.matcher(contentType);
            if (matcher.find()) {
                try {
                    charset = Charset.forName(matcher.group(1));
                } catch (IllegalCharsetNameException | UnsupportedCharsetException ignored) {
                    // keep UTF-8 fallback
                }
            }
        }
        return new String(bytes, charset);
    }

    private Semaphore capFor(URI url) {
        String origin = url.getScheme() + "://" + url.getAuthority();
        return capByOrigin.computeIfAbsent(origin, k -> new Semaphore(perOriginCap));
    }

}
