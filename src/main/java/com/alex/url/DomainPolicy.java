package com.alex.url;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * Decides whether a candidate URL is in scope for the crawl, relative to the seed.
 *
 * <p>A candidate is in scope when its host equals the seed host, or by default
 * when it ends with {@code "." + seedHost} (subdomain). Both sides have a single
 * leading {@code www.} stripped before comparison so that
 * {@code www.example.com} and {@code example.com} are treated as one site.
 *
 * <p>The label-boundary check ({@code endsWith("." + seedHost)}, not plain
 * {@code endsWith(seedHost)}) is critical: a naive suffix match would treat
 * {@code evil-example.com} as a subdomain of {@code example.com}
 *
 * <p>{@code excludeSubdomains == true} tightens to exact-host match, useful when you
 * want {@code example.com} only, no {@code blog.}/{@code shop.} variants.
 */
public final class DomainPolicy {

    private final String seedHost;
    private final boolean excludeSubdomains;

    public DomainPolicy(URI seed, boolean excludeSubdomains) {
        Objects.requireNonNull(seed, "seed cannot be null");
        String host = seed.getHost();
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("seed has no host: " + seed);
        }
        this.seedHost = stripLeadingWww(host.toLowerCase(Locale.ROOT));
        this.excludeSubdomains = excludeSubdomains;
    }

    public boolean isInScope(URI candidate) {
        if (candidate == null) {
            return false;
        }

        String host = candidate.getHost();
        if (host == null || host.isEmpty()) {
            return false;
        }

        host = host.toLowerCase(Locale.ROOT);

        if (host.equals(seedHost)) {
            return true;
        }
        if (excludeSubdomains) {
            return false;
        }
        return host.endsWith("." + seedHost);
    }

    public String seedHost(){
        return seedHost;
    }

    private static String stripLeadingWww(String host) {
        return host.startsWith("www.") ? host.substring(4) : host;
    }
}
