package com.gs.ais.controller;

import com.gs.ais.dto.request.DataExportRequest;
import com.gs.ais.dto.request.DataImportRequest;
import com.gs.ais.model.entity.ModelProvider;
import com.gs.ais.repository.ModelProviderRepository;
import com.gs.ais.service.portability.DataPortabilityService;
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

    public AdminDataController(DataPortabilityService dataPortabilityService,
                               ModelProviderRepository modelProviderRepository) {
        this.dataPortabilityService = dataPortabilityService;
        this.modelProviderRepository = modelProviderRepository;
    }

    @GetMapping("/export/preview")
    public Map<String, Object> preview() {
        return dataPortabilityService.preview();
    }

    @PostMapping("/export")
    public ResponseEntity<byte[]> exportData(@RequestBody(required = false) DataExportRequest request)
            throws IOException {
        DataPortabilityService.ExportResult result = dataPortabilityService.exportData(
                request != null ? request : new DataExportRequest());
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
                                          @RequestPart(value = "options", required = false) DataImportRequest options)
            throws IOException {
        return dataPortabilityService.importData(file, options != null ? options : new DataImportRequest());
    }

    @PutMapping("/models/{modelId}/billing")
    public ResponseEntity<Map<String, Object>> updateModelBilling(@PathVariable Long modelId,
                                                                    @RequestBody Map<String, Object> body) {
        ModelProvider model = modelProviderRepository.findById(modelId)
                .orElseThrow(() -> new RuntimeException("Model not found: " + modelId));
        String billingMode = (String) body.get("billingMode");
        if (billingMode != null) {
            model.setBillingMode(billingMode);
        } else {
            model.setBillingMode(null);
        }
        Object pricePerUnitObj = body.get("pricePerUnit");
        if (pricePerUnitObj != null) {
            if (pricePerUnitObj instanceof Number n) {
                model.setPricePerUnit(BigDecimal.valueOf(n.doubleValue()));
            }
        } else {
            model.setPricePerUnit(null);
        }
        modelProviderRepository.save(model);
        return ResponseEntity.ok(Map.of(
                "id", modelId,
                "billingMode", model.getBillingMode(),
                "pricePerUnit", model.getPricePerUnit()
        ));
    }
}
