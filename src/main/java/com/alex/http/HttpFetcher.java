package com.alex.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class HttpFetcher {

    private static final String DEFAULT_UA =
            "EcosioCrawler/1.0 (+https://github.com/BaAlexandru/ecosio-homework)";

    private static final String CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36";

    private static final long DEFAULT_MAX_BYTES = 5L * 1024 * 1024;   // 5 MiB
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient client;
    private final long maxBytes;
    private final Duration timeout;

    public HttpFetcher() {
        this(DEFAULT_MAX_BYTES, DEFAULT_TIMEOUT);
    }

    public HttpFetcher(long maxBytes, Duration timeout) {
        this.maxBytes = maxBytes;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder()
                // Prefer HTTP/2 explicitly. The JDK transparently falls back to
                // HTTP/1.1 if the server doesn't advertise h2 via ALPN. The
                // negotiated version is exposed on FetchResult.httpVersion.
                .version(HttpClient.Version.HTTP_2)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    public FetchResult fetch(URI url) {
        FetchResult first = sendOnce(url, DEFAULT_UA, false);
        if (first.status() == 403 || first.status() == 503) {
            return sendOnce(url, CHROME_UA, true);
        }
        return first;
    }

    private FetchResult sendOnce(URI url, String userAgent, boolean fallback) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(url)
                .timeout(timeout)
                // Per-request HTTP/2 hint pins the contract; client downgrades
                // on ALPN mismatch and reports actual version via response.version().
                .version(HttpClient.Version.HTTP_2)
                .header("User-Agent", userAgent)
                .header("Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");

        if (fallback) {
            builder
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Sec-Fetch-Dest", "document")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Site", "none")
                    .header("Sec-Fetch-User", "?1")
                    .header("Upgrade-Insecure-Requests", "1");
        }

        HttpRequest request = builder.GET().build();
        try {
            HttpResponse<byte[]> response =
                    client.send(request, info -> new CappedBodySubscriber(maxBytes));
            String contentType = response.headers()
                    .firstValue("Content-Type").orElse(null);
            return new FetchResult(
                    response.uri(),
                    response.statusCode(),
                    contentType,
                    response.body(),
                    fallback,
                    response.version(),
                    null);
        } catch (IOException e) {
            return FetchResult.transportFailure(url,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return FetchResult.transportFailure(url, "interrupted");
        }
    }
}
