# Decisions

This document is the curated "why" behind the load-bearing choices in
this crawler.

## 1. Java 21, pure JDK, Gradle wrapper

Java 21 is pinned in the toolchain. Virtual threads, `HttpClient`,
records, pattern switches, and `try-with-resources` on `ExecutorService`
(via Java 19+ `close()`) are all load-bearing, this is not "Java 21
because it's the latest LTS."

The brief bans on third-party libraries. No Jsoup, Apache
HttpClient, OkHttp, Guava, JCommander etc. Gradle is build
orchestration only, zero runtime dependencies. JUnit 5 is the only
exception, scoped to `testImplementation` and used solely for the
link-extractor regex. The fetcher, crawler, and CLI ship without tests
per the brief.

## 2. Concurrency model

`Executors.newVirtualThreadPerTaskExecutor()` for the worker pool.
Network I/O is exactly the workload virtual threads were designed for, 
classic platform-thread pools either over-serialize (small pool) or
waste OS resources (large pool).

`HttpClient.send(...)` runs synchronously **on** a virtual thread.
`sendAsync(...)` would be redundant: the virtual thread is the
asynchrony. Sync `send` keeps stack traces readable and exception
handling local.

The visited set is `ConcurrentHashMap.newKeySet()`. `add()` is
lock-free, `O(1)`, and returns `true` only when the URL was actually
inserted, that boolean *is* the dedup gate. Adding **before**
scheduling the fetch is the invariant that closes the race:

```java
if (visited.add(normalized)) {
    inFlight.incrementAndGet();
    executor.submit(() -> visit(normalized, label, ...));
}
```
A naive `if (!contains) { add; submit; }` is wrong `contains` and
`add` are individually thread-safe, but the *pair* isn't.

Completion uses `AtomicInteger inFlight` + `CountDownLatch done`. Each
parent pre-increments `inFlight` for the child it submits and
decrements in its own `finally` block; when `inFlight` reaches zero
the latch fires. `StructuredTaskScope` was rejected, still preview
through JDK 26, finalizing in JDK 27, and the API churned across
previews.

## 3. Per-origin concurrency cap

The crawler caps in-flight fetches with `ConcurrentHashMap<String, Semaphore>`
keyed on `scheme://authority` (RFC 6454 origin), lazily populated.
Each origin gets its own `Semaphore(perOriginCap)`.

A single global `Semaphore` would be correct for a single-origin crawl
but is wrong on multi-subdomain sites. When `orf.at` fans out to 15+
subdomains and one of them (`kids.orf.at`) is rate-limited to ~1 req/s
by a backend middleware, fast subdomains burn the global budget while
slow ones queue serially behind them, wall time is dominated by the
slowest origin's tail.

`--exclude-subdomains` is enforced upstream by `DomainPolicy.isInScope()`,
so the per-origin map ends up with exactly one entry on subdomain-excluded
runs and behavior is identical to a single global semaphore. No flag
plumbing in `Crawler`.

Empirical: `orf.at --max-pages=10000` dropped from 4 m 54 s to 1 m 11 s
(~4×) on this change alone.

## 4. HTTP fetcher

`HttpClient` is configured with **explicit HTTP/2 preference**
(`.version(HttpClient.Version.HTTP_2)`). The JDK transparently falls
back to HTTP/1.1 when ALPN doesn't advertise h2; the negotiated version
is exposed on `FetchResult.httpVersion` for diagnostics.

### Size cap — `CappedBodySubscriber`

`HttpResponse.BodySubscribers.limiting(...)` does not exist in JDK 21.
The size cap is enforced by `CappedBodySubscriber`, a `BodySubscriber<byte[]>`
that accumulates bytes into a `ByteArrayOutputStream` and calls
`subscription.cancel()` plus `result.completeExceptionally(...)` on
overflow. `BodySubscribers.ofString()` / `ofByteArray()` would buffer
the entire response before any cap check could run.

### Body skip for non-HTML — `DiscardingBodySubscriber`

The crawler only recurses on `2xx + text/html`. A 100 MB PDF was being
read off the wire and immediately discarded, pure waste. The
`BodyHandler` now inspects `Content-Type` from response headers and
routes non-HTML responses to a `DiscardingBodySubscriber` that calls
`subscription.cancel()` in `onSubscribe`.

`BodySubscribers.replacing(value)` and `BodySubscribers.discarding()`
do **not** cancel, both use the JDK's `NullSubscriber`, which calls
`request(Long.MAX_VALUE)` and drains the body before completing with
the fixed value (verified in OpenJDK 21 source). A custom subscriber
is required for genuine cancellation.

Empirical: media URLs (videos, PDFs) drop from full body download
(seconds, often tens of seconds) to TTFB + h2 RST_STREAM ack
(hundreds of ms).

### Cloudflare fallback ethics

On `403`/`503` the fetcher retries **once** with a realistic Chrome
`User-Agent`, `Accept-Language: en-US,en;q=0.9`, and `Sec-Fetch-*`
headers. No cookies, no JavaScript, no second retry. The URL is
recorded with whichever status finally comes back; `fallbackUsed` on
`FetchResult` exposes whether the second attempt fired.

This is transparent best-effort, not a real WAF bypass.

## 5. URL normalization and trap defenses

`UrlNormalizer` does the standard hygiene: scheme allowlist
(`http`/`https` only), `IDN.toASCII()` on the host (handles
internationalized domains), lowercase host, strip userinfo and
fragment, strip leading `www.`. Resolved against a base URI for
relative links.

Two trap defenses live in the normalizer because they're properties
of the URL itself, not the crawl topology:

- **`MAX_URL_LENGTH = 2000`** — universal backstop against
  length-amplification traps. Any URL whose normalized form exceeds
  2000 chars is dropped.
- **`%(?:25){3,}` regex** — surgical defense against recursive
  percent-encoding traps. ecosio.com's glossary pagination re-encoded
  its own query parameter on every link (`%23` → `%2523` →
  `%252523` → `%25252523` → ...), growing each URL by 2 chars per
  recursion. The length cap alone fires too late (depth ~616, ~10
  minutes); the regex fires at depth 3 (~3 seconds).

The pattern is `%(?:25){3,}`, not `(?:%25){4,}`: the trap shape is a
*single* `%` followed by many `25`s, not many `%25` literals in a row.

## 6. Same-domain policy

`DomainPolicy.isInScope(URI)`:

```java
return host.equals(seedHost) || host.endsWith("." + seedHost);
```

The leading `.` is critical. Plain `host.endsWith(seedHost)` would
treat `evil-example.com` as a subdomain of `example.com` a textbook
auth bypass. Subdomains include the seed by default. `--exclude-subdomains`
tightens the predicate to `host.equals(seedHost)` only.

## 7. Link extraction

`LinkExtractor` uses a **two-stage parse**, not a single regex:

1. A regex matches `<a ... href="...">` openers (atomic groups +
   possessive quantifiers + bounded character classes, ReDoS-safe).
2. A hand-written Java loop scans forward from each opener for
   `</a>` with `MAX_LABEL_SCAN = 4096` cap.

The two-stage form was forced by ReDoS testing: a single regex
spanning opener + label + closer hit the engine's per-iteration
constant factor on 10 K unclosed openers (>2 s). Splitting the parse
moved the closer scan into raw Java, dropping the same input to
~80 ms.

Before opener matching, the body is run through a `STRIP_BLOCK`
pattern that removes `<script>...</script>`, `<style>...</style>`,
and `<!-- ... -->` blocks. This serves two purposes: it prevents
fake-link extraction from JS string literals and embedded SVG icon
styles, and it keeps CSS/script content out of labels. The strip
runs once per page, not once per anchor.

Labels are sanitized: HTML entities decoded (`&amp;`, `&lt;`, `&gt;`,
`&quot;`, numeric entities), whitespace collapsed, then `\t\r\n`
collapsed to single space at format time.

## 8. Output contract

The output line format extends the spec without breaking it:

```
[<status>]<TAB><label><TAB><url>[<TAB><error>]
```

- Every URL discovered is recorded, regardless of HTTP status. 404s,
  500s, PDFs, images → all leaves. Recursion only happens on
  `2xx + text/html`. The `[<status>]` column makes "collect every
  URL" verifiable at a glance.
- Status is zero-padded `%03d` so `grep '^\[5'` works.
- Transport failures (status=0, exception during fetch) render as
  `[ERR]` and emit an optional 4th tab column with the sanitized
  exception message. Columns 1-3 stay stable for the success case.
- Sort is case-insensitive label, URL as tiebreaker.

Output goes to `stdout` and to `crawl-<ddMMyyyy-HHmmss>.txt` (or the
`--output` override). File output is best-effort: open-failure logs
a stderr warning and falls back to stdout-only; mid-stream write
failure flips a `fileBroken` flag (via `PrintWriter.checkError()`)
and suppresses further file writes for the remainder of the run.
Stdout always succeeds.

`stderr` is reserved for progress logs (start, finish, write
confirmation). Pipeable.

## 9. Tradeoffs not taken

- **No rate limiting beyond the per-origin semaphore.** No token
  bucket, no adaptive backoff. The semaphore is the throttle.
- **No on-disk fetch cache.** Re-running against the same seed
  re-fetches everything. Trivial to add (HTTP caching headers exist
  for a reason);
- **No JavaScript rendering.** Pure HTML link extraction. SPAs that
  render their navigation client-side won't be crawled deeply.
  Requires a headless browser, which is the opposite of "pure JDK".
- **Tests scoped to `LinkExtractor` only.** The brief says tests are
  out of scope; the link extractor's regex is the one place where a
  silent correctness regression is a real risk (ReDoS test failures
  drove the two-stage parse).
- 
## 10. Architecture summary

```
com.alex
├── Main                          — entry point
├── cli
│   ├── Args                      — record + parser + usage
│   ├── OutputFormatter           — line formatting + sanitization
│   └── OutputSink                — best-effort dual sink
├── url
│   ├── UrlNormalizer             — scheme/host/path normalization + trap defenses
│   └── DomainPolicy              — same-domain (label-boundary) predicate
├── html
│   ├── Link                      — record (URI url, String label)
│   └── LinkExtractor             — two-stage regex + Java parse
├── http
│   ├── HttpFetcher               — sync send on virtual threads + Cloudflare fallback
│   ├── FetchResult               — record (finalUrl, status, contentType, body, ...)
│   ├── CappedBodySubscriber      — size cap via cancel-on-overflow
│   └── DiscardingBodySubscriber  — non-HTML cancel-in-onSubscribe
└── crawl
    └── Crawler                   — virtual-thread orchestration + per-origin semaphore
```

Each package has one job and minimal cross-package coupling. `crawl`
depends on `http`, `html`, `url`. `cli` depends on everything but is
depended on by nothing.
