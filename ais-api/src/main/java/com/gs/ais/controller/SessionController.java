package com.gs.ais.controller;

import com.gs.ais.dto.request.CreateSessionRequest;
import com.gs.ais.dto.request.DrawRequest;
import com.gs.ais.dto.request.RegenerateRequest;
import com.gs.ais.dto.request.GenerateRequest;
import com.gs.ais.dto.request.UpdateMessageRequest;
import com.gs.ais.dto.request.UpdateSessionTitleRequest;
import com.gs.ais.dto.response.GenerateResponse;
import com.gs.ais.dto.response.MessageResponse;
import com.gs.ais.dto.response.TokenUsage;
import com.gs.ais.model.entity.Message;
import com.gs.ais.model.entity.Session;
import com.gs.ais.service.ImageGenerationService;
import com.gs.ais.service.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sessions")
@Tag(name = "会话管理", description = "管理多轮对话会话，包括会话的创建、删除、消息发送、图像生成、消息编辑与重新生成等操作")
public class SessionController {

    private final SessionService sessionService;
    private final ImageGenerationService imageGenerationService;

    public SessionController(SessionService sessionService,
                             ImageGenerationService imageGenerationService) {
        this.sessionService = sessionService;
        this.imageGenerationService = imageGenerationService;
    }

    @Operation(summary = "获取会话列表", description = "获取所有会话的列表，按创建时间倒序排列。每个会话包含标题、创建时间、更新时间及其关联的供应商配置。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "成功返回会话列表")
    })
    @GetMapping
    public ResponseEntity<List<Session>> listSessions() {
        return ResponseEntity.ok(sessionService.getAllSessions());
    }

    @Operation(summary = "创建新会话", description = "创建一个新的对话会话。创建成功后会自动分配 ID 并记录创建时间。")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "会话创建成功"),
            @ApiResponse(responseCode = "500", description = "创建失败，请检查数据库连接")
    })
    @PostMapping
    public ResponseEntity<Session> createSession(@Valid @RequestBody(required = false) CreateSessionRequest request) {
        Session session = sessionService.createSession(request == null ? null : request.getTitle());
        return ResponseEntity.status(HttpStatus.CREATED).body(session);
    }

    @Operation(summary = "获取单个会话", description = "根据会话 ID 获取会话的详细信息，包括标题、供应商配置和时间信息。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "成功返回会话信息"),
            @ApiResponse(responseCode = "404", description = "会话不存在")
    })
    @GetMapping("/{id}")
    public ResponseEntity<Session> getSession(
            @Parameter(description = "会话 ID", required = true, example = "1") @PathVariable Long id) {
        return ResponseEntity.ok(sessionService.getSession(id));
    }

    @Operation(summary = "修改会话标题", description = "更新指定会话的显示标题。")
    @PatchMapping("/{id}/title")
    public ResponseEntity<Session> updateSessionTitle(
            @Parameter(description = "会话 ID", required = true, example = "1") @PathVariable Long id,
            @Valid @RequestBody UpdateSessionTitleRequest request) {
        return ResponseEntity.ok(sessionService.updateSessionTitle(id, request.getTitle()));
    }

    @Operation(summary = "删除会话", description = "删除指定的会话及其关联的所有消息和附件。此操作不可撤销。")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "删除成功"),
            @ApiResponse(responseCode = "404", description = "会话不存在")
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSession(
            @Parameter(description = "会话 ID", required = true, example = "1") @PathVariable Long id) {
        sessionService.deleteSession(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "获取会话消息列表", description = "获取指定会话的所有消息，按创建时间正序排列。每条消息包含发送者角色（USER/ASSISTANT）、内容、关联图片、Token 消耗等信息。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "成功返回消息列表"),
            @ApiResponse(responseCode = "404", description = "会话不存在")
    })
    @GetMapping("/{id}/messages")
    public ResponseEntity<List<MessageResponse>> getMessages(
            @Parameter(description = "会话 ID", required = true, example = "1") @PathVariable Long id) {
        List<Message> messages = imageGenerationService.getMessages(id);
        List<MessageResponse> responses = messages.stream()
                .map(MessageResponse::from)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @Operation(summary = "生成（预留）", description = "预留的生成接口，当前为桩实现，返回空响应。后续将用于自定义参数生成。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "返回空的生成响应（预留）")
    })
    @PostMapping("/{id}/messages")
    public ResponseEntity<GenerateResponse> generate(
            @Parameter(description = "会话 ID", required = true, example = "1") @PathVariable Long id,
            @RequestBody GenerateRequest request) {
        return ResponseEntity.ok(new GenerateResponse(null, "", "", null));
    }

    @Operation(summary = "发送聊天消息", description = """
            向指定会话发送用户消息并获取 AI 回复。

            - 系统会自动带上历史上下文（最多 128k token）
            - 支持附带已上传的附件（通过 attachmentIds 引用）
            - 可通过 chatProviderId 指定使用的对话模型供应商，不传则使用会话默认值
            - 返回 AI 回复内容、消息 ID 及 Token 使用情况
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "成功返回 AI 回复"),
            @ApiResponse(responseCode = "404", description = "会话不存在"),
            @ApiResponse(responseCode = "500", description = "模型调用失败或 Token 超限")
    })
    @PostMapping("/{id}/chat")
    public ResponseEntity<Map<String, Object>> chat(
            @Parameter(description = "会话 ID", required = true, example = "1") @PathVariable Long id,
            @Valid @RequestBody GenerateRequest request) {
        ImageGenerationService.ChatMessageResult result = imageGenerationService.chat(
                id, request.getPrompt(),
                request.getAttachmentIds(),
                request.getChatProviderId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", result.content());
        body.put("assistantMessageId", result.assistantMessageId());
        body.put("tokenUsage", result.tokenUsage());
        body.put("status", result.status());
        body.put("errorMessage", result.errorMessage());
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "生成图像", description = """
            基于当前会话的最后一条用户消息自动生成图像。

            - 使用会话配置的图像模型供应商（可通过 imageProviderId 覆盖）
            - 系统会先优化提示词（将对话上下文融入图像描述），再调用图像 API
            - 成功时返回生成图像的 URL 和优化后的提示词
            - 支持的格式：PNG、JPEG、GIF、WebP（取决于模型）
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "图像生成成功（或失败，通过响应中的字段判断）"),
            @ApiResponse(responseCode = "404", description = "会话不存在")
    })
    @PostMapping("/{id}/draw")
    public ResponseEntity<Map<String, Object>> draw(
            @Parameter(description = "会话 ID", required = true, example = "1") @PathVariable Long id,
            @RequestBody(required = false) DrawRequest request) {
        ImageGenerationService.DrawResult result = imageGenerationService.draw(id, request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("assistantMessageId", result.assistantMessageId());
        body.put("imageUrl", result.imageUrl());
        body.put("prompt", result.prompt());
        body.put("status", result.status());
        body.put("errorMessage", result.errorMessage());
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "编辑消息", description = "编辑指定会话中的用户消息内容。编辑后消息会标记为已编辑状态（edited=true），可以重新触发 AI 回复。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "编辑成功返回更新后的消息"),
            @ApiResponse(responseCode = "404", description = "会话或消息不存在"),
            @ApiResponse(responseCode = "400", description = "内容不能为空")
    })
    @PutMapping("/{id}/messages/{messageId}")
    public ResponseEntity<MessageResponse> editMessage(
            @Parameter(description = "会话 ID", required = true, example = "1") @PathVariable Long id,
            @Parameter(description = "消息 ID", required = true, example = "10") @PathVariable Long messageId,
            @Valid @RequestBody UpdateMessageRequest request) {
        Message updated = imageGenerationService.editMessage(id, messageId, request.getContent());
        return ResponseEntity.ok(MessageResponse.from(updated));
    }

    @Operation(summary = "重新生成或再次发送消息", description = "助手消息会原位重新生成；用户消息会再次发送，并在该用户消息之后新增一条 AI 回复。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "成功返回重新生成的结果（含优化后提示词和图片 URL）"),
            @ApiResponse(responseCode = "404", description = "会话或消息不存在")
    })
    @PostMapping("/{id}/messages/{messageId}/regenerate")
    public ResponseEntity<GenerateResponse> regenerate(
            @Parameter(description = "会话 ID", required = true, example = "1") @PathVariable Long id,
            @Parameter(description = "用户或助手消息 ID", required = true, example = "10") @PathVariable Long messageId,
            @RequestBody(required = false) RegenerateRequest request) {
        Long chatProviderId = request != null ? request.getChatProviderId() : null;
        Long imageProviderId = request != null ? request.getImageProviderId() : null;
        ImageGenerationService.GenerationResult result = imageGenerationService.regenerateMessage(
                id, messageId, chatProviderId, imageProviderId);
        TokenUsage tokenUsage = result.tokenUsage();
        return ResponseEntity.ok(new GenerateResponse(
                result.messageId(), result.optimizedPrompt(), result.imageUrl(), tokenUsage));
    }

    @Operation(summary = "删除消息", description = "删除指定会话中的一条消息及其关联的附件。如果删除的是用户消息，后续的 AI 回复也会一并删除。")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "删除成功"),
            @ApiResponse(responseCode = "404", description = "会话或消息不存在")
    })
    @DeleteMapping("/{id}/messages/{messageId}")
    public ResponseEntity<Void> deleteMessage(
            @Parameter(description = "会话 ID", required = true, example = "1") @PathVariable Long id,
            @Parameter(description = "消息 ID", required = true, example = "10") @PathVariable Long messageId) {
        imageGenerationService.deleteMessage(id, messageId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancelPending(
            @PathVariable Long id) {
        Long messageId = imageGenerationService.cancelPending(id);
        return ResponseEntity.ok(Map.of(
                "cancelled", messageId != null,
                "messageId", messageId != null ? messageId : -1L));
    }

    @Operation(summary = "更新会话供应商", description = """
            批量更新会话使用的对话和图像供应商。

            - 仅传入需要更新的字段，不传的字段保持原值
            - 供应商 ID 必须已存在于数据库中（CHAT 或 IMAGE 类型）
            - 支持仅更新聊天供应商、仅更新图像供应商或同时更新两者
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "更新成功返回完整的会话信息"),
            @ApiResponse(responseCode = "404", description = "会话或供应商不存在")
    })
    @PatchMapping("/{id}/providers")
    public ResponseEntity<Session> updateSessionProviders(
            @Parameter(description = "会话 ID", required = true, example = "1") @PathVariable Long id,
            @RequestBody Map<String, Long> body) {
        Session updated = sessionService.updateProviders(id,
                body.get("chatProviderId"), body.get("imageProviderId"));
        return ResponseEntity.ok(updated);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleError(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", ex.getMessage()));
    }
}
