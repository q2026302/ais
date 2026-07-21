package com.gs.ais.controller;

import com.gs.ais.dto.response.UploadResponse;
import com.gs.ais.service.AttachmentService;
import com.gs.ais.service.OperationLogService;
import com.gs.ais.security.AuthContext;
import jakarta.servlet.http.HttpServletRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api")
@Tag(name = "文件上传", description = "上传图片和文档作为对话附件，支持单文件和多文件上传")
public class UploadController {

    private final AttachmentService attachmentService;
    private final OperationLogService operationLogService;

    public UploadController(AttachmentService attachmentService, OperationLogService operationLogService) {
        this.attachmentService = attachmentService;
        this.operationLogService = operationLogService;
    }

    @Operation(summary = "上传单个文件", description = """
            上传单个文件作为附件。支持图片和文档格式。

            - 支持格式：图片（PNG/JPEG/GIF/WebP）和文档（PDF/Word/Excel/Text）
            - 单文件大小限制：20MB
            - 返回文件的 ID、原始名称、类型及访问 URL
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "文件上传成功，返回文件元数据"),
            @ApiResponse(responseCode = "413", description = "文件大小超过限制（20MB）"),
            @ApiResponse(responseCode = "415", description = "不支持的文件类型")
    })
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponse> uploadFile(
            @Parameter(description = "待上传的文件", required = true) @RequestParam("file") MultipartFile file,
            HttpServletRequest httpRequest) {
        UploadResponse response = attachmentService.upload(file);
        operationLogService.record(AuthContext.get(), "UPLOAD", "ATTACHMENT", response.getId(),
                "上传文件：" + response.getOriginalName(), httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "批量上传文件", description = """
            同时上传多个文件作为附件。每个文件的处理规则与单文件上传一致。

            - 支持同时上传最多 10 个文件
            - 总请求体大小限制：50MB
            - 返回每个文件的元数据列表（按传入顺序排列）
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "所有文件上传成功，返回文件元数据列表"),
            @ApiResponse(responseCode = "413", description = "请求体大小超过限制（50MB）")
    })
    @PostMapping(value = "/upload/multiple", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<List<UploadResponse>> uploadFiles(
            @Parameter(description = "待上传的文件列表（最多 10 个）", required = true) @RequestParam("files") List<MultipartFile> files,
            HttpServletRequest httpRequest) {
        List<UploadResponse> responses = files.stream()
                .map(attachmentService::upload)
                .toList();
        operationLogService.record(AuthContext.get(), "UPLOAD", "ATTACHMENT", null,
                "批量上传文件：" + responses.size() + " 个", httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(responses);
    }
}
