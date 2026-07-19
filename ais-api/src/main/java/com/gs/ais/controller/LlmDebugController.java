package com.gs.ais.controller;

import com.gs.ais.service.LlmDebugService;
import com.gs.ais.service.LlmDebugService.LlmDebugExchange;
import com.gs.ais.service.LlmDebugService.LlmDebugExchangeSummary;
import com.gs.ais.service.LlmDebugService.LlmDebugStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api/admin/llm-debug")
@Tag(name = "LLM 通信调试", description = "运行时启停并查看模型供应商 HTTP 请求与响应数据包")
public class LlmDebugController {

    private final LlmDebugService debugService;

    public LlmDebugController(LlmDebugService debugService) {
        this.debugService = debugService;
    }

    @GetMapping
    @Operation(summary = "获取 LLM 通信调试状态")
    public LlmDebugStatus getStatus() {
        return debugService.getStatus();
    }

    @PutMapping
    @Operation(summary = "运行时启停 LLM 通信调试，无需重启服务")
    public LlmDebugStatus updateStatus(@RequestBody UpdateDebugStatusRequest request) {
        if (request == null || request.enabled() == null) {
            throw new ResponseStatusException(BAD_REQUEST, "enabled is required");
        }
        return debugService.setEnabled(request.enabled());
    }

    @GetMapping("/exchanges")
    @Operation(summary = "获取最近的 LLM HTTP 数据包摘要")
    public List<LlmDebugExchangeSummary> listExchanges(
            @RequestParam(defaultValue = "50") int limit) {
        return debugService.listExchanges(limit);
    }

    @GetMapping("/exchanges/{id}")
    @Operation(summary = "获取单次 LLM HTTP 通信的完整请求和响应")
    public LlmDebugExchange getExchange(@PathVariable String id) {
        return debugService.getExchange(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "LLM debug exchange not found"));
    }

    @DeleteMapping("/exchanges")
    @Operation(summary = "清空已缓存的 LLM HTTP 调试数据")
    public ResponseEntity<LlmDebugStatus> clearExchanges() {
        return ResponseEntity.ok(debugService.clear());
    }

    public record UpdateDebugStatusRequest(Boolean enabled) {
    }
}
