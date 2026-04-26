package com.alex.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.Locale;
import java.util.Objects;

public record FetchResult(
        URI finalUrl,
        int status,
        String contentType,
        byte[] body,
        boolean fallbackUsed,
        HttpClient.Version httpVersion,       // null on transport failure; HTTP_2 or HTTP_1_1 otherwise
        String error
) {
    public FetchResult {
        Objects.requireNonNull(finalUrl, "finalUrl");
        Objects.requireNonNull(body, "body");
    }

    public boolean isOk() {
        return error == null && status >= 200 && status < 300;
    }

    public boolean isHtml() {
        return contentType != null
                && contentType.toLowerCase(Locale.ROOT).startsWith("text/html");
    }

    public boolean shouldRecurse() {
        return isOk() && isHtml();
    }

    public static FetchResult transportFailure(URI url, String message) {
        return new FetchResult(url, 0, null, new byte[0], false, null, message);
    }
}