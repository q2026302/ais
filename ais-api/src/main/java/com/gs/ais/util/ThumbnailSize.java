package com.gs.ais.util;

import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thumbnail size enumeration for {@link PureThumbnail}.
 * Used by {@link com.gs.ais.controller.ImageController} to decide
 * output file name and target edge.
 */
public enum ThumbnailSize {
    /**
     * 256px longest edge. Corresponds to {@code ?size=small}.
     */
    SMALL(256),

    /**
     * 512px longest edge. Corresponds to {@code ?size=medium}.
     */
    MEDIUM(512);

    private static final Logger log = LoggerFactory.getLogger(ThumbnailSize.class);

    private final int edge;

    ThumbnailSize(int edge) {
        this.edge = edge;
    }

    public int getEdge() {
        return edge;
    }

    public static ThumbnailSize fromString(String value) {
        if (value == null || value.isBlank()) {
            return SMALL;
        }
        try {
            return valueOf(value.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown thumbnail size: '{}', defaulting to SMALL", value);
            return SMALL;
        }
    }

    public static ThumbnailSize fromParam(String sizeParam) {
        if (sizeParam == null || sizeParam.isBlank()) {
            return SMALL;
        }
        try {
            return valueOf(sizeParam.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid size parameter: '{}', defaulting to SMALL", sizeParam);
            return SMALL;
        }
    }
}
