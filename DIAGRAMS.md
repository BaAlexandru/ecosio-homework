# Diagrams

Four views into the crawler:

- **[A — Component / package layout](#a--component--package-layout)** — where the code lives.
- **[B — Parent / child virtual-thread flow](#b--parent--child-virtual-thread-flow)** — the orchestration sequence.
- **[C — Per-thread `visit()` lifecycle](#c--per-thread-visit-lifecycle)** — what one virtual thread does end-to-end.
- **[D — HTTP fetch dispatch with body-handler branching](#d--http-fetch-dispatch)** — the inside of `HttpFetcher.fetch(...)`.

---

## A — Component / package layout

Direction of arrows = "depends on". `cli` depends on everything; nothing depends on `cli`.

```mermaid
graph TD
    Main[com.alex.Main]

    subgraph cli [com.alex.cli]
        Args[Args]
        Format[OutputFormatter]
        Sink[OutputSink]
    end

    subgraph crawl [com.alex.crawl]
        Crawler[Crawler]
    end

    subgraph http [com.alex.http]
        Fetcher[HttpFetcher]
        Result[FetchResult]
        Capped[CappedBodySubscriber]
        Discard[DiscardingBodySubscriber]
    end

    subgraph html [com.alex.html]
        Extract[LinkExtractor]
        Link[Link record]
    end

    subgraph url [com.alex.url]
        Norm[UrlNormalizer]
        Policy[DomainPolicy]
    end

    Main --> Args
    Main --> Format
    Main --> Sink
    Main --> Crawler
    Main --> Fetcher
    Main --> Policy

    Crawler --> Fetcher
    Crawler --> Extract
    Crawler --> Policy

    Fetcher --> Result
    Fetcher --> Capped
    Fetcher --> Discard

    Extract --> Link
    Extract --> Norm

    Policy --> Norm
```

---

## B — Parent / child virtual-thread flow

The seed thread fans out. Each child runs on its own virtual thread, can fan out further, and the run finishes when `inFlight` returns to zero — that's the latch trigger.

```mermaid
sequenceDiagram
    autonumber
    participant Main as Main (platform thread)
    participant Exec as VT Executor
    participant T0 as VT-0 visit(seed)
    participant T1 as VT-1 visit(linkA)
    participant T2 as VT-2 visit(linkB)
    participant Latch as CountDownLatch(1)

    Main->>Main: visited.add(seed) → true<br/>inFlight = 1
    Main->>Exec: submit(visit(seed))
    Main->>Latch: await()

    activate T0
    Exec->>T0: run
    T0->>T0: capFor(origin).acquire()
    T0->>T0: HttpFetcher.fetch(seed)
    T0->>T0: capFor(origin).release()
    T0->>T0: entries.add(Entry)
    T0->>T0: LinkExtractor.extract(body)

    Note over T0: for each child link in scope:
    T0->>T0: visited.add(linkA) → true
    T0->>T0: inFlight.incrementAndGet() = 2
    T0->>Exec: submit(visit(linkA))

    T0->>T0: visited.add(linkB) → true
    T0->>T0: inFlight.incrementAndGet() = 3
    T0->>Exec: submit(visit(linkB))

    T0->>T0: visited.add(seed) → false (dup, skipped)

    Note over T0: finally
    T0->>T0: inFlight.decrementAndGet() = 2
    deactivate T0

    activate T1
    Exec->>T1: run
    T1->>T1: fetch + extract<br/>(0 new links found)
    Note over T1: finally
    T1->>T1: inFlight.decrementAndGet() = 1
    deactivate T1

    activate T2
    Exec->>T2: run
    T2->>T2: fetch + extract<br/>(0 new links found)
    Note over T2: finally
    T2->>T2: inFlight.decrementAndGet() = 0
    T2->>Latch: countDown()
    deactivate T2

    Latch-->>Main: await() returns
    Main->>Exec: close() → shutdown
    Main->>Main: sort entries + write output
```

**Invariants this diagram encodes:**

- **Add-before-schedule.** Every `submit()` is preceded by a `visited.add(url) → true`. The `add()` boolean *is* the dedup gate — there's no `contains` check.
- **Pre-increment in parent.** `inFlight.incrementAndGet()` runs in the **parent**, before `submit()`, so the child can never observe `inFlight = 0` between its scheduling and its `decrementAndGet`. If the parent decremented first, the latch could fire too early.
- **Single-shot latch.** `CountDownLatch(1)` fires exactly once when `inFlight` hits zero. Any thread can be the one to call `countDown()`.

---

## C — Per-thread `visit()` lifecycle

Inside one virtual thread. The `finally` block is the only path that decrements `inFlight` — every code path leads to it, including exceptions.

```mermaid
flowchart TD
    Start([visit url, label]) --> Acq[capFor origin .acquire]
    Acq --> Fetch[HttpFetcher.fetch url]
    Fetch --> Rel[capFor origin .release]
    Rel --> Record[entries.add Entry]
    Record --> RecQ{shouldRecurse?<br/>2xx + text/html}

    RecQ -- no --> Fin
    RecQ -- yes --> Decode[decode body using charset]
    Decode --> Extract[LinkExtractor.extract]
    Extract --> Loop[for each child link]

    Loop --> Cap{visited.size<br/>maxPages?}
    Cap -- size full --> Skip[skip]
    Cap -- room --> Scope{policy.isInScope?}
    Scope -- no --> Skip
    Scope -- yes --> AddQ{visited.add child<br/>true?}
    AddQ -- false, dup --> Skip
    AddQ -- true --> Inc[inFlight.incrementAndGet]
    Inc --> Sub[exec.submit visit child]
    Sub --> Loop
    Skip --> Loop

    Loop -- done --> Fin
    Fin[finally:<br/>inFlight.decrementAndGet] --> ZeroQ{result = 0?}
    ZeroQ -- yes --> CD[done.countDown]
    ZeroQ -- no --> End([thread exits])
    CD --> End
```

**Why the per-origin semaphore wraps only `fetch(...)`:**
the wait happens in the network call, so the lock is held for exactly the duration of the I/O. CPU-only steps (`extract`, `add`, `submit`) run outside the lock — they wouldn't benefit from being serialized per-origin and would just throttle fan-out.

---

## D — HTTP fetch dispatch

Inside `HttpFetcher.fetch(...)`. The `BodyHandler` is invoked **after** response headers arrive and **before** body bytes start flowing — so we can branch on `Content-Type` without paying for the body.

```mermaid
sequenceDiagram
    autonumber
    participant V as Caller (VT in visit)
    participant F as HttpFetcher
    participant C as java.net.http.HttpClient
    participant H as BodyHandler
    participant CS as CappedBodySubscriber
    participant DS as DiscardingBodySubscriber

    V->>F: fetch(url)
    F->>C: send(request, BodyHandler)
    C->>H: apply(ResponseInfo)
    Note over H: read Content-Type<br/>from headers

    alt text/html or application/xhtml+xml
        H->>CS: new CappedBodySubscriber(maxBytes)
        activate CS
        CS->>C: subscription.request(Long.MAX_VALUE)
        loop body buffers arrive
            C->>CS: onNext(List ByteBuffer )
            CS->>CS: write to ByteArrayOutputStream
            alt total > maxBytes
                CS->>C: subscription.cancel()
                CS->>CS: completeExceptionally(IOException)
            end
        end
        C->>CS: onComplete()
        CS->>CS: complete(byte body)
        deactivate CS
    else any other Content-Type
        H->>DS: new DiscardingBodySubscriber
        activate DS
        DS->>C: subscription.cancel()
        Note over DS,C: 0 body bytes pulled<br/>RST_STREAM on h2
        DS->>DS: complete(empty bytes)
        deactivate DS
    end

    C-->>F: HttpResponse byte
    F->>F: build FetchResult

    alt status in 403 or 503 and not retried yet
        F->>F: build retry request<br/>Chrome UA, Sec-Fetch headers
        F->>C: send(retry, BodyHandler)
        Note over F,C: same Content-Type branching applies
        C-->>F: HttpResponse byte
        F->>F: rebuild FetchResult<br/>fallbackUsed = true
    end

    F-->>V: FetchResult
```

**Why the cancel in `DiscardingBodySubscriber` is load-bearing:**
the JDK's `BodySubscribers.replacing(...)` and `discarding()` both back onto `NullSubscriber`, which calls `request(Long.MAX_VALUE)` and *drains* the body before completing. Same network cost as a full GET. The custom subscriber calls `subscription.cancel()` in `onSubscribe` — on HTTP/2 that's a `RST_STREAM` frame, on HTTP/1.1 it closes the connection. Either way zero body bytes flow.
