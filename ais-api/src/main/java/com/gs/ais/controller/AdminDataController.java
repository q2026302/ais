package com.gs.ais.controller;

import com.gs.ais.dto.request.DataExportRequest;
import com.gs.ais.dto.request.DataImportRequest;
import com.gs.ais.model.entity.ModelProvider;
import com.gs.ais.repository.ModelProviderRepository;
import com.gs.ais.service.portability.DataPortabilityService;
import com.gs.ais.service.OperationLogService;
import com.gs.ais.security.AuthContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminDataController {

    private final DataPortabilityService dataPortabilityService;
    private final ModelProviderRepository modelProviderRepository;
    private final OperationLogService operationLogService;

    public AdminDataController(DataPortabilityService dataPortabilityService,
                               ModelProviderRepository modelProviderRepository,
                               OperationLogService operationLogService) {
        this.dataPortabilityService = dataPortabilityService;
        this.modelProviderRepository = modelProviderRepository;
        this.operationLogService = operationLogService;
    }

    @GetMapping("/export/preview")
    public Map<String, Object> preview() {
        return dataPortabilityService.preview();
    }

    @PostMapping("/export")
    public ResponseEntity<byte[]> exportData(@RequestBody(required = false) DataExportRequest request,
                                             HttpServletRequest httpRequest)
            throws IOException {
        DataPortabilityService.ExportResult result = dataPortabilityService.exportData(
                request != null ? request : new DataExportRequest());
        operationLogService.record(AuthContext.get(), "ADMIN_DATA_EXPORT", "DATA", null,
                "导出系统数据", httpRequest);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(result.filename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(result.content().length)
                .body(result.content());
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> importData(@RequestPart("file") MultipartFile file,
                                          @RequestPart(value = "options", required = false) DataImportRequest options,
                                          HttpServletRequest httpRequest)
            throws IOException {
        Map<String, Object> result = dataPortabilityService.importData(file, options != null ? options : new DataImportRequest());
        operationLogService.record(AuthContext.get(), "ADMIN_DATA_IMPORT", "DATA", null,
                "导入系统数据：" + file.getOriginalFilename(), httpRequest);
        return result;
    }

    @PutMapping("/models/{modelId}/billing")
    public ResponseEntity<Map<String, Object>> updateModelBilling(@PathVariable Long modelId,
                                                                    @RequestBody Map<String, Object> body,
                                                                    HttpServletRequest httpRequest) {
        ModelProvider model = modelProviderRepository.findById(modelId)
                .orElseThrow(() -> new RuntimeException("Model not found: " + modelId));
        String billingMode = (String) body.get("billingMode");
        if (billingMode != null && !billingMode.equalsIgnoreCase("PER_CALL") && !billingMode.equalsIgnoreCase("PER_TOKEN")) {
            throw new IllegalArgumentException("不支持的计费模式");
        }
        model.setBillingMode(billingMode);
        if ("PER_CALL".equalsIgnoreCase(billingMode)) {
            if (body.get("pricePerUnit") == null || toBigDecimal(body.get("pricePerUnit")).signum() <= 0) {
                throw new IllegalArgumentException("按次收费单价必须大于 0");
            }
        } else if ("PER_TOKEN".equalsIgnoreCase(billingMode)
                && (toBigDecimal(body.get("inputPricePerMillion")) == null || toBigDecimal(body.get("inputPricePerMillion")).signum() <= 0
                || toBigDecimal(body.get("outputPricePerMillion")) == null || toBigDecimal(body.get("outputPricePerMillion")).signum() <= 0
                || toBigDecimal(body.get("cacheReadPricePerMillion")) == null || toBigDecimal(body.get("cacheReadPricePerMillion")).signum() <= 0)) {
            throw new IllegalArgumentException("按 Token 收费必须填写三项单价且都大于 0");
        }
        Object pricePerUnitObj = body.get("pricePerUnit");
        if (pricePerUnitObj != null) {
            if (pricePerUnitObj instanceof Number n) {
                model.setPricePerUnit(BigDecimal.valueOf(n.doubleValue()));
            }
        } else {
            model.setPricePerUnit(null);
        }
        model.setInputPricePerMillion(toBigDecimal(body.get("inputPricePerMillion")));
        model.setOutputPricePerMillion(toBigDecimal(body.get("outputPricePerMillion")));
        model.setCacheReadPricePerMillion(toBigDecimal(body.get("cacheReadPricePerMillion")));
        modelProviderRepository.save(model);
        operationLogService.record(AuthContext.get(), "ADMIN_BILLING_UPDATE", "MODEL", modelId,
                "更新模型计费配置", httpRequest);
        return ResponseEntity.ok(Map.of(
                "id", modelId,
                "billingMode", model.getBillingMode(),
                "pricePerUnit", model.getPricePerUnit(),
                "inputPricePerMillion", model.getInputPricePerMillion(),
                "outputPricePerMillion", model.getOutputPricePerMillion(),
                "cacheReadPricePerMillion", model.getCacheReadPricePerMillion()
        ));
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return BigDecimal.valueOf(number.doubleValue());
        try { return new BigDecimal(String.valueOf(value)); } catch (NumberFormatException e) { return null; }
    }
}
