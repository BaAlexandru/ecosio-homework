package com.alex.crawl;

import java.net.URI;

/** One row in the final crawl report. */
public record Entry(
        URI url,
        int status,
        String label,
        boolean fallbackUsed,
        String error
) {
}
