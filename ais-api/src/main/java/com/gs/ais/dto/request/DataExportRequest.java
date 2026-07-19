package com.gs.ais.dto.request;

import java.util.ArrayList;
import java.util.List;

public class DataExportRequest {

    private List<String> sections = new ArrayList<>(List.of("sessions", "providers", "settings", "files"));
    private boolean includeApiKeys;
    private List<Long> sessionIds;

    public List<String> getSections() {
        return sections;
    }

    public void setSections(List<String> sections) {
        this.sections = sections;
    }

    public boolean isIncludeApiKeys() {
        return includeApiKeys;
    }

    public void setIncludeApiKeys(boolean includeApiKeys) {
        this.includeApiKeys = includeApiKeys;
    }

    public List<Long> getSessionIds() {
        return sessionIds;
    }

    public void setSessionIds(List<Long> sessionIds) {
        this.sessionIds = sessionIds;
    }
}
