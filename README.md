# ecosio-crawler

A small JDK-only web crawler for the ecosio Full Stack technical task.

## The task

> Technical Task: Full Stack Developer with a focus on Java
>
> We are eager to get to know your code style. Please find below the instructions for a quick but interesting example helping us to see how you handle the full potential that the JDK offers. Hence, please stick to the JDK and avoid using any other open source libraries. It is important to us to understand how and why you get to a solution (i.e., your code style).
>
> 1. Connect to an arbitrary website (http/s)
> 2. Search for all links on this site referring to other pages of the same domain (website) and collect them
> 3. For each collected link start over with (1) - we would like to see some multithreading here since network tends to be slow
> 4. When finished, output the full collection of links sorted by the link label
>
> Result: collection of links for an arbitrary website (e.g., `orf.at`, `ecosio.com`)

Two clarifications:

- **Spring Boot**: not desired; if used, the choice has to be justified. I chose plain JDK.
- **Tests**: out of scope.

## How I read the task

- **"Same domain"** means same registrable host. `blog.example.com` is in scope when seeded with `example.com` by default; a `--exclude-subdomains` flag tightens it to exact-host. The same-domain check is label-boundary (`host.equals(seed) || host.endsWith("." + seed)`) - a naive `endsWith` would treat `evil-example.com` as a subdomain of `example.com`.
- **"Start over with (1)"** means recurse, but visit each URL at most once. Deduplication is on the normalized URL.
- **"Multithreading"** uses Java 21 virtual threads via `Executors.newVirtualThreadPerTaskExecutor()`. Network-bound work is exactly what virtual threads were built for; classic platform-thread pools would either over-serialize or waste OS resources.
- **"Output sorted by the link label"** - case-insensitive sort by label, URL as tiebreaker. Output goes to `stdout` and to `crawl-<timestamp>.txt` so a review of the result is possible without re-running.
## Decisions worth flagging upfront

- **Pure JDK.** No Jsoup, no Apache HttpClient, no OkHttp, no Guava. HTML link extraction is a bounded, ReDoS-safe regex over the response body. HTTP uses `java.net.http.HttpClient` with synchronous `send()` on virtual threads - `sendAsync` is unnecessary in 2025 once virtual threads are in play.
- **Java 21.** Pinned in `build.gradle` toolchain. Virtual threads are load-bearing.
- **Deduplication via `ConcurrentHashMap.newKeySet()`.** The visited set must be lock-free and `O(1)` per `add` — `Collections.synchronizedSet(new HashSet<>())` would serialize every check across all virtual threads, and `CopyOnWriteArraySet` is `O(n)` per add (fine for read-heavy workloads, ruinous when every fetched page produces 50+ candidate URLs). The chosen set's `add()` returns `true` only when the URL was actually inserted, and that boolean **is** the dedup gate. Adding **before** scheduling closes the race where two virtual threads could both pass an "if not visited" check and submit duplicate fetch tasks for the same URL. A naive `if (!visited.contains(u)) { visited.add(u); submit(...); }` would be wrong — `contains` and `add` are individually thread-safe, but the *pair* isn't.
```java
if (visited.add(normalized)) {
    executor.submit(() -> fetch(normalized));
}
```

- **Collect every URL, not just `200`s.** A 404, a 500, a PDF - they are still links on the page, and the output records each with its HTTP status. **Recursion** only happens for `2xx + Content-Type: text/html`; everything else is recorded as a leaf node.
- **Response-size cap via a custom `Flow.Subscriber`.** `HttpResponse.BodySubscribers.limiting(...)` the cap is enforced by a subscriber that tracks running byte count and calls `subscription.cancel()` on overflow. `BodySubscribers.ofString()` would buffer the entire response before any cap check can run.
- **Cloudflare fallback.** On `403`/`503`, retry once with a realistic Chrome `User-Agent` + `Accept-Language` + `Sec-Fetch-*` headers. Single retry, no cookies, no JavaScript. The URL is recorded with whichever status finally comes back. `ecosio.com` is currently behind Cloudflare; this is transparent best-effort, not a real bypass.
- **No `StructuredTaskScope`.** Still preview through JDK 26 (finalizing in JDK 27); the API has churned across previews. Completion is tracked with `AtomicInteger inFlight` + `CountDownLatch done`.

## How it's built

The commit history is the story - one commit per phase:

| # | Commit                                                                | What it adds |
|---|-----------------------------------------------------------------------|-------------|
| 0 | `docs-1: task interpretation and approach`                            | README |
| 1 | `feature-1: URL normalization and same-domain policy`                 | `UrlNormalizer`, `DomainPolicy`. |
| 2 | `feature-2: HTML link extractor`                                      | `LinkExtractor`, `Link` record. |
| 3 | `feature-3: HTTP fetcher with timeout, size cap, Cloudflare fallback` | `HttpFetcher`, `FetchResult`. |
| 4 | `feature-4: virtual-thread crawler orchestration`                     | `Crawler`. |
| 5 | `feature-5: CLI entry and sorted output`                              | `Main`, output file writer. |
| 6 | `docs-2: DECISIONS.md + README polish`                                | The "why" behind every choice. |

## Running it

```bash
./gradlew run --args="https://orf.at"
```

CLI:

| Flag | Default | Meaning |
|------|---------|---------|
| `<seed>` | required | Seed URL - `http`/`https` only. |
| `--max-pages=N` | `500` | Safety cap on pages fetched. |
| `--exclude-subdomains` | off | Tighten same-domain to exact-host. |
| `--timeout-seconds=N` | `10` | Per-request timeout. |
| `--max-bytes=N` | `2000000` | Per-response byte cap. |
| `--output-file=PATH` | `crawl-<ts>.txt` | Where to write the final sorted list. Pass empty to disable. |

Output line format (sorted by label, case-insensitive):

```
[200]<TAB>About us<TAB>https://example.com/about
[404]<TAB>Old promo<TAB>https://example.com/promo-2019
[200]<TAB>Whitepaper<TAB>https://example.com/wp.pdf
```

The `[<status>]` column is an addition over the literal task spec - it makes "collect every URL regardless of status" verifiable at a glance.

## Out of scope

- Automated tests (confirmed with recruiter).
- Persistence, REST API, UI, link-graph analytics.
- `robots.txt`, rate limiting, caching. Could be added; not asked for.

## Status

This is commit 0 - README only. See `git log` for phase progression.

## Legend

 **ReDoS** → Regular-expression Denial of Service. For a crawler, where input is untrusted HTML from arbitrary domains, this is a real attack surface, one bad page can stall a whole virtual thread.
