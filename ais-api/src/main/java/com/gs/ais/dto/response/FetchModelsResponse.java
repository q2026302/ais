package com.gs.ais.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "模型列表响应")
public class FetchModelsResponse {

    @Schema(description = "可用模型 ID 列表", example = "[\"gpt-4o\", \"gpt-4o-mini\", \"gpt-4-turbo\"]")
    private List<String> models;

    public List<String> getModels() {
        return models;
    }

    public void setModels(List<String> models) {
        this.models = models;
    }
}
