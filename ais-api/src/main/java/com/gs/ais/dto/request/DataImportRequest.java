package com.gs.ais.dto.request;

import java.util.ArrayList;
import java.util.List;

public class DataImportRequest {

    /** merge (default) or replace */
    private String mode = "merge";
    private List<String> sections = new ArrayList<>();
    private boolean includeApiKeys = true;

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

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
}
