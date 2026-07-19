package com.gs.ais.controller;

import com.gs.ais.dto.request.DataExportRequest;
import com.gs.ais.dto.request.DataImportRequest;
import com.gs.ais.service.portability.DataPortabilityService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminDataController {

    private final DataPortabilityService dataPortabilityService;

    public AdminDataController(DataPortabilityService dataPortabilityService) {
        this.dataPortabilityService = dataPortabilityService;
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
}
