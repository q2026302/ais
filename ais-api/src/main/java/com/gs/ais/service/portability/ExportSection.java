package com.gs.ais.service.portability;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public enum ExportSection {
    SESSIONS,
    PROVIDERS,
    SETTINGS,
    FILES;

    public static ExportSection from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("导出分区不能为空");
        }
        return ExportSection.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    public static Set<ExportSection> parseAll(Iterable<String> values) {
        Set<ExportSection> sections = new LinkedHashSet<>();
        if (values == null) {
            return sections;
        }
        for (String value : values) {
            sections.add(from(value));
        }
        return sections;
    }
}
