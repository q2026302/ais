package com.gs.ais.util;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Helpers for the newline-separated {@code Message.referenceFileUrls} text field,
 * which stores raw context-relative server file paths ({@code /api/images/...} or
 * {@code /api/attachments/...}) without signatures.
 *
 * <p>Physical-file deletion paths use {@link #containsPath(Collection, String)} to
 * decide whether a file must be kept alive because a surviving message still
 * references it. Matching is exact and prefix-aware: the candidate's query string
 * is stripped and the remainder is compared verbatim against every stored entry
 * (also query-stripped), so {@code /api/images/a.png} and
 * {@code /api/attachments/a.png} never collide.
 */
public final class ReferenceFileUrls {

    private ReferenceFileUrls() {
    }

    /** Splits a stored reference string into its individual raw paths. */
    public static List<String> split(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        return Arrays.stream(stored.split("\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** Returns the path portion of a URL (everything before {@code ?}), or {@code null}. */
    public static String stripQuery(String url) {
        if (url == null) {
            return null;
        }
        int query = url.indexOf('?');
        return query >= 0 ? url.substring(0, query) : url;
    }

    /**
     * True when any stored reference path equals {@code fileUrl}'s path, after
     * stripping the query string on both sides. The comparison is exact string
     * equality of the context-relative path (no prefix fallback), so a reference
     * can only protect the precise file it names.
     */
    public static boolean containsPath(Collection<String> storedValues, String fileUrl) {
        String path = stripQuery(fileUrl);
        if (path == null || path.isBlank()) {
            return false;
        }
        for (String stored : storedValues) {
            if (stored == null) {
                continue;
            }
            for (String reference : split(stored)) {
                if (path.equals(stripQuery(reference))) {
                    return true;
                }
            }
        }
        return false;
    }
}
