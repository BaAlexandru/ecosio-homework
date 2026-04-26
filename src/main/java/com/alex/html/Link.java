package com.alex.html;

import java.net.URI;
import java.util.Objects;

/**
 * A link extracted from an HTML page: the (already-normalized) target URL and
 * the visible anchor(can be empty) label.
 */
public record Link(
        URI url,
        String label
) {
    public Link {
        Objects.requireNonNull(url, "url should not be null");
        Objects.requireNonNull(label, "label should not be null");
    }
}
