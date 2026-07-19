package com.gs.ais.controller;

import com.gs.ais.client.LlmClient;
import com.gs.ais.dto.request.FetchModelsRequest;
import com.gs.ais.dto.request.ModelProviderRequest;
import com.gs.ais.dto.request.TestConnectionRequest;
import com.gs.ais.dto.response.FetchModelsResponse;
import com.gs.ais.dto.response.ModelProviderResponse;
import com.gs.ais.dto.response.TestConnectionResponse;
import com.gs.ais.model.ModelProviderDefaults;
import com.gs.ais.model.entity.ModelProvider;
import com.gs.ais.model.enums.ProviderType;
import com.gs.ais.service.ModelProviderService;
import com.gs.ais.service.ModelProviderResponseMapper;
import com.gs.ais.config.StoragePaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/providers")
@Tag(name = "供应商管理", description = "配置和管理 LLM 服务供应商（对话/图像），支持连接测试、模型列表获取、图片生成测试")
public class ModelProviderController {

    private final ModelProviderService service;
    private final LlmClient llmClient;
    private final ModelProviderResponseMapper responseMapper;
    private final StoragePaths storagePaths;

    public ModelProviderController(ModelProviderService service, LlmClient llmClient,
                                   ModelProviderResponseMapper responseMapper,
                                   StoragePaths storagePaths) {
        this.service = service;
        this.llmClient = llmClient;
        this.responseMapper = responseMapper;
        this.storagePaths = storagePaths;
    }

    @Operation(summary = "获取活跃供应商", description = "根据类型获取当前已激活的供应商。每种类型（CHAT/IMAGE）只能有一个活跃供应商。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "成功返回活跃供应商"),
            @ApiResponse(responseCode = "404", description = "该类型下没有活跃供应商")
    })
    @GetMapping("/active")
    public ResponseEntity<ModelProviderResponse> getActiveProvider(
            @Parameter(description = "供应商类型", required = true, examples = {
                    @ExampleObject(name = "CHAT", value = "CHAT"),
                    @ExampleObject(name = "IMAGE", value = "IMAGE")
            })
            @RequestParam ProviderType type) {
        ModelProvider provider = service.getActiveProvider(type);
        return ResponseEntity.ok(toResponse(provider));
    }

    @Operation(summary = "获取供应商列表", description = "获取所有供应商，可按类型过滤。不传 type 参数时返回全部。")
    @GetMapping
    public ResponseEntity<List<ModelProviderResponse>> getAllProviders(
            @Parameter(description = "供应商类型过滤（可选）", required = false)
            @RequestParam(required = false) ProviderType type) {
        List<ModelProvider> providers = service.getAll(type);
        List<ModelProviderResponse> responses = providers.stream()
                .filter(this::isAvailableForUse)
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @Operation(summary = "获取单个供应商", description = "根据 ID 获取供应商的详细信息。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "成功"),
            @ApiResponse(responseCode = "404", description = "供应商不存在")
    })
    @GetMapping("/{id}")
    public ResponseEntity<ModelProviderResponse> getProvider(
            @Parameter(description = "供应商 ID", required = true) @PathVariable Long id) {
        ModelProvider provider = service.getById(id);
        return ResponseEntity.ok(toResponse(provider));
    }

    @Operation(summary = "创建供应商", description = """
            创建一个新的服务供应商。

            - `providerId`: 供应商名称，全局唯一，创建后不可修改
            - `type`: CHAT（对话模型）或 IMAGE（图像生成模型）
            - `modelName`: 模型名称，支持从服务端点获取模型列表后选择
            - `baseUrl`: API 请求地址；仅填写域名时会自动补全为 https://域名/v1
            - `apiKey`: 可选，编辑时留空则不修改已有密钥
            - `isActive`: 是否设为活跃状态
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "创建成功"),
            @ApiResponse(responseCode = "409", description = "供应商名称已存在")
    })
    @PostMapping
    public ResponseEntity<ModelProviderResponse> createProvider(
            @Valid @RequestBody ModelProviderRequest request) {
        ModelProvider provider = service.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(provider));
    }

    @Operation(summary = "更新供应商", description = """
            更新供应商配置。

            - 编辑时如 apiKey 字段为空，后端将保留已有的 API 密钥
            - providerId 和 type 唯一性检查
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "更新成功"),
            @ApiResponse(responseCode = "404", description = "供应商不存在"),
            @ApiResponse(responseCode = "409", description = "providerId 与 type 组合冲突")
    })
    @PutMapping("/{id}")
    public ResponseEntity<ModelProviderResponse> updateProvider(
            @Parameter(description = "供应商 ID", required = true) @PathVariable Long id,
            @Valid @RequestBody ModelProviderRequest request) {
        ModelProvider provider = service.update(id, request);
        return ResponseEntity.ok(toResponse(provider));
    }

    @Operation(summary = "删除供应商", description = "删除供应商，如果当前为活跃状态则先取消其活跃状态。")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "删除成功"),
            @ApiResponse(responseCode = "404", description = "供应商不存在")
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProvider(
            @Parameter(description = "供应商 ID", required = true) @PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "激活供应商", description = "将指定供应商设为活跃状态，同时自动停用同类型的其他活跃供应商。")
    @PatchMapping("/{id}/activate")
    public ResponseEntity<ModelProviderResponse> activateProvider(
            @Parameter(description = "供应商 ID", required = true) @PathVariable Long id) {
        ModelProvider provider = service.setActive(id);
        return ResponseEntity.ok(toResponse(provider));
    }

    @Operation(summary = "测试连接（端点测试）", description = """
            使用 API 请求地址和 apiKey 测试服务可用性。

            - 适用场景：供应商配置对话框中，用户输入新的 API 请求地址/apiKey 后点击「测试连接」
            - OpenAI 兼容供应商向 `{baseUrl}/models` 发送 GET 请求
            - Grsai 使用其任务结果接口进行无消耗的鉴权与连通性验证
            - 返回响应时间便于判断网络延迟
            """)
    @PostMapping("/test")
    public ResponseEntity<TestConnectionResponse> testConnection(
            @RequestBody TestConnectionRequest request) {
        TestConnectionResponse response = isGrsai(request.getProviderKey())
                ? llmClient.testGrsaiConnection(request.getBaseUrl(), request.getApiKey())
                : llmClient.testConnection(request.getBaseUrl(), request.getApiKey());
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "测试已存储供应商的连接", description = """
            使用数据库中已存储的供应商凭据测试连接。

            - 适用场景：供应商列表中的「测试」按钮，或编辑弹窗中复用原始密钥测试新地址
            - apiKey 不传或为空时从数据库读取原始密钥；传入时使用用户输入的新值
            """)
    @PostMapping("/{id}/test")
    public ResponseEntity<TestConnectionResponse> testProviderConnection(
            @Parameter(description = "供应商 ID", required = true) @PathVariable Long id,
            @RequestBody(required = false) TestConnectionRequest request) {
        ModelProvider provider = service.getById(id);
        String baseUrl = resolveBaseUrl(provider, request);
        String apiKey = resolveApiKey(provider, request);
        TestConnectionResponse response = isGrsai(provider.getProviderId())
                ? llmClient.testGrsaiConnection(baseUrl, apiKey)
                : llmClient.testConnection(baseUrl, apiKey);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "获取模型列表（端点）", description = """
            从指定端点获取可用模型名称列表。

            - 适用场景：供应商配置对话框中，用户输入 API 请求地址/apiKey 后点击「获取模型」
            - 返回标准 OpenAI 兼容格式的模型 ID 列表
            """)
    @PostMapping("/models")
    public ResponseEntity<FetchModelsResponse> fetchModels(
            @RequestBody FetchModelsRequest request) {
        FetchModelsResponse response = llmClient.fetchModels(
                request.getBaseUrl(), request.getApiKey());
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "获取已存储供应商的模型列表", description = "使用数据库中的供应商凭据获取模型列表；apiKey 不传或为空时使用原始密钥，传入时使用用户输入的新值。")
    @PostMapping("/{id}/models")
    public ResponseEntity<FetchModelsResponse> fetchProviderModels(
            @Parameter(description = "供应商 ID", required = true) @PathVariable Long id,
            @RequestBody(required = false) FetchModelsRequest request) {
        ModelProvider provider = service.getById(id);
        FetchModelsResponse response = llmClient.fetchModels(
                resolveBaseUrl(provider, request), resolveApiKey(provider, request));
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "测试图像生成", description = """
            对 IMAGE 类型供应商进行实际图像生成测试。

            - 生成一张简单的测试图片（红色圆形），以验证图像 API 配置正确
            - 成功时返回生成的图片 URL，可在前端直接预览
            - 适用于 IMAGE 类型供应商的配置验证
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "测试完成，success 字段指示是否成功")
    })
    @PostMapping("/{id}/test-image")
    public ResponseEntity<Map<String, Object>> testImageGeneration(
            @Parameter(description = "供应商 ID（应为 IMAGE 类型）", required = true) @PathVariable Long id) {
        ModelProvider provider = service.getById(id);
        try {
            byte[] imageData = llmClient.testImageGeneration(provider);
            Path uploadDir = storagePaths.uploadDir();
            Files.createDirectories(uploadDir);
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            String filename = "test_" + provider.getId() + "_" + timestamp + "_" + UUID.randomUUID().toString().substring(0, 8) + ".png";
            Files.write(uploadDir.resolve(filename), imageData);
            String imageUrl = "/api/images/" + filename;
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "imageUrl", imageUrl,
                    "message", "Test image generated successfully"
            ));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "imageUrl", (Object) null,
                    "message", "Image generation failed: " + e.getMessage()
            ));
        }
    }

    private String resolveBaseUrl(ModelProvider provider, TestConnectionRequest request) {
        return request != null && hasText(request.getBaseUrl())
                ? request.getBaseUrl()
                : provider.getBaseUrl();
    }

    private String resolveApiKey(ModelProvider provider, TestConnectionRequest request) {
        return request != null && hasText(request.getApiKey())
                ? request.getApiKey()
                : provider.getApiKey();
    }

    private String resolveBaseUrl(ModelProvider provider, FetchModelsRequest request) {
        return request != null && hasText(request.getBaseUrl())
                ? request.getBaseUrl()
                : provider.getBaseUrl();
    }

    private String resolveApiKey(ModelProvider provider, FetchModelsRequest request) {
        return request != null && hasText(request.getApiKey())
                ? request.getApiKey()
                : provider.getApiKey();
    }

    private boolean isGrsai(String providerKey) {
        return providerKey != null && "grsai".equalsIgnoreCase(providerKey.trim());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean isAvailableForUse(ModelProvider provider) {
        return !"grsai".equalsIgnoreCase(provider.getProviderId())
                || (provider.getApiKey() != null && !provider.getApiKey().isBlank());
    }

    private ModelProviderResponse toResponse(ModelProvider provider) {
        return responseMapper.toResponse(provider);
    }

}
