package com.alex.url;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;


/**
 * Stateless normalization of HTTP(S) URIs.
 * <p>
 * Two URIs that point at the same web page should normalize to byte-identical
 * strings, so the visited set in the crawler can dedupe with a plain {@code String}
 * key. The transformation, in order:
 *
 * <ol>
 *   <li>scheme is lowercased and must be {@code http} or {@code https} —
 *       everything else (mailto, javascript, ftp, file, …) is rejected;</li>
 *   <li>host is lowercased and ASCII-encoded via {@link IDN#toASCII} so
 *       internationalized domain names compare equal regardless of source encoding;</li>
 *   <li>{@code userinfo} is dropped — it has no role in identifying the page and
 *       leaking it into logs/output would be a credential disclosure;</li>
 *   <li>fragment is dropped — purely client-side, never sent to the server;</li>
 *   <li>port is dropped when it equals the scheme default, so
 *       {@code https://example.com:443/x} and {@code https://example.com/x} dedupe;</li>
 *   <li>path is normalized via {@link URI#normalize()} (collapses {@code .}/{@code ..})
 *       and an empty path is rewritten to {@code /} so
 *       {@code https://example.com} and {@code https://example.com/} dedupe.</li>
 * </ol>
 */
public final class UrlNormalizer {

    private static final Set<String> ALLOWED_SCHEMAS = Set.of("http", "https");
    private static final int MAX_URL_LENGTH = 2_000;
    private static final Pattern REPEATED_PERCENT_ENCODING =
            Pattern.compile("%(?:25){3,}");

    private UrlNormalizer() {}

    public static Optional<URI> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }

        try {
            return normalize(new URI(trimmed));
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    public static Optional<URI> normalize(URI uri) {
        if (uri == null) return Optional.empty();

        String scheme = uri.getScheme();
        if (scheme == null) {
            return Optional.empty();
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMAS.contains(scheme)) {
            return Optional.empty();
        }

        String host =  uri.getHost();
        if (host == null || host.isEmpty()) {
            return Optional.empty();
        }

        try{
            host = IDN.toASCII(host).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }

        int port  = uri.getPort();
        if (isDefaultPort(scheme, port)) {
            port = -1;
        }

        String path =  uri.getPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }

        String query = uri.getRawQuery();

        try {
            URI reconstructedUri = new URI(scheme, null, host, port, path, query, null);
            URI out = reconstructedUri.normalize();
            String s = out.toString();
            if (s.length() > MAX_URL_LENGTH)                         return Optional.empty();
            if (REPEATED_PERCENT_ENCODING.matcher(s).find())         return Optional.empty();
            return Optional.of(out);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    /**
     * Resolve a (possibly relative) {@code href} against {@code base} and normalize
     * the result. Returns empty if the resolved URI is not a normalized HTTP(S) URL.
     */
    public static Optional<URI> resolve(URI base, String href) {
        if (href == null) {
            return Optional.empty();
        }
        String trimmed =  href.strip();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }

        try {
            return normalize(base.resolve(trimmed));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static boolean isDefaultPort(String scheme, int port) {
        if (port == -1) {
            return true;
        }
        return scheme.equals("http") && port == 80
                || scheme.equals("https") && port == 443;
    }
}
