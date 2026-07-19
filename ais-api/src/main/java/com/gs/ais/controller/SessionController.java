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
import com.gs.ais.model.entity.AppUser;
import com.gs.ais.model.entity.Message;
import com.gs.ais.model.entity.Session;
import com.gs.ais.model.entity.ModelProvider;
import com.gs.ais.model.enums.MessageStatus;
import com.gs.ais.repository.AppUserRepository;
import com.gs.ais.security.AuthContext;
import com.gs.ais.security.AuthRole;
import com.gs.ais.service.BillingService;
import com.gs.ais.service.ImageGenerationQueueService;
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
    private final ImageGenerationQueueService queueService;
    private final AppUserRepository appUserRepository;
    private final BillingService billingService;

    public SessionController(SessionService sessionService,
                             ImageGenerationService imageGenerationService,
                             ImageGenerationQueueService queueService,
                             AppUserRepository appUserRepository,
                             BillingService billingService) {
        this.sessionService = sessionService;
        this.imageGenerationService = imageGenerationService;
        this.queueService = queueService;
        this.appUserRepository = appUserRepository;
        this.billingService = billingService;
    }

    private Long getCurrentUserId() {
        var principal = AuthContext.get();
        if (principal == null) return null;
        return appUserRepository.findByUsernameIgnoreCase(principal.subject())
                .map(AppUser::getId)
                .orElse(null);
    }

    @Operation(summary = "获取会话列表", description = "获取当前用户的所有会话列表，按创建时间倒序排列。管理员可查看所有会话。")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "成功返回会话列表")})
    @GetMapping
    public ResponseEntity<List<Session>> listSessions() {
        Long userId = AuthContext.isAdmin() ? null : getCurrentUserId();
        return ResponseEntity.ok(sessionService.getSessionsByUserId(userId));
    }

    @Operation(summary = "创建新会话", description = "创建一个新的对话会话。")
    @ApiResponses({@ApiResponse(responseCode = "201", description = "会话创建成功")})
    @PostMapping
    public ResponseEntity<Session> createSession(@Valid @RequestBody(required = false) CreateSessionRequest request) {
        Long userId = getCurrentUserId();
        Session session = sessionService.createSession(
                request == null ? null : request.getTitle(),
                userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(session);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Session> getSession(@PathVariable Long id) {
        Session session = sessionService.getSession(id);
        checkSessionAccess(session);
        return ResponseEntity.ok(session);
    }

    @PatchMapping("/{id}/title")
    public ResponseEntity<Session> updateSessionTitle(@PathVariable Long id,
                                                       @Valid @RequestBody UpdateSessionTitleRequest request) {
        Session session = sessionService.getSession(id);
        checkSessionAccess(session);
        return ResponseEntity.ok(sessionService.updateSessionTitle(id, request.getTitle()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSession(@PathVariable Long id) {
        Session session = sessionService.getSession(id);
        checkSessionAccess(session);
        sessionService.deleteSession(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/messages")
    public ResponseEntity<List<MessageResponse>> getMessages(@PathVariable Long id) {
        Session session = sessionService.getSession(id);
        checkSessionAccess(session);
        List<Message> messages = imageGenerationService.getMessages(id);
        return ResponseEntity.ok(messages.stream().map(MessageResponse::from).toList());
    }

    @PostMapping("/{id}/messages")
    public ResponseEntity<GenerateResponse> generate(@PathVariable Long id,
                                                      @RequestBody GenerateRequest request) {
        return ResponseEntity.ok(new GenerateResponse(null, "", "", null));
    }

    @PostMapping("/{id}/chat")
    public ResponseEntity<Map<String, Object>> chat(@PathVariable Long id,
                                                     @Valid @RequestBody GenerateRequest request) {
        Session session = sessionService.getSession(id);
        checkSessionAccess(session);

        // Use user's default chat provider if session has none
        Long resolvedChatProviderId = request.getChatProviderId();
        if (resolvedChatProviderId == null && session.getChatProviderId() == null) {
            Long userId = getCurrentUserId();
            if (userId != null) {
                resolvedChatProviderId = appUserRepository.findById(userId)
                        .map(AppUser::getDefaultChatProviderId)
                        .orElse(null);
            }
        }

        ImageGenerationService.ChatMessageResult result = imageGenerationService.chat(
                id, request.getPrompt(),
                request.getAttachmentIds(),
                resolvedChatProviderId != null ? resolvedChatProviderId : request.getChatProviderId());

        // Record billing for successful chat
        if (result.status() == MessageStatus.SUCCESS && result.tokenUsage() != null) {
            Long userId = getCurrentUserId();
            if (userId != null) {
                try {
                    ModelProvider chatProvider = imageGenerationService.getProviderById(
                            resolvedChatProviderId != null ? resolvedChatProviderId : session.getChatProviderId());
                    if (chatProvider != null) {
                        billingService.recordChat(chatProvider, userId, id, result.assistantMessageId(),
                                result.tokenUsage().getPromptTokens(),
                                result.tokenUsage().getCompletionTokens(),
                                result.tokenUsage().getTotalTokens());
                    }
                } catch (Exception e) {
                    // Billing recording is non-critical
                }
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", result.content());
        body.put("assistantMessageId", result.assistantMessageId());
        body.put("tokenUsage", result.tokenUsage());
        body.put("status", result.status());
        body.put("errorMessage", result.errorMessage());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/{id}/draw")
    public ResponseEntity<Map<String, Object>> draw(@PathVariable Long id,
                                                     @RequestBody(required = false) DrawRequest request) {
        Session session = sessionService.getSession(id);
        checkSessionAccess(session);

        DrawRequest safeRequest = request != null ? request : new DrawRequest();

        // Resolve image provider: use session's, then user's default, then request's
        Long imageProviderId = safeRequest.getImageProviderId();
        if (imageProviderId == null && session.getImageProviderId() == null) {
            Long userId = getCurrentUserId();
            if (userId != null) {
                imageProviderId = appUserRepository.findById(userId)
                        .map(AppUser::getDefaultImageProviderId)
                        .orElse(null);
            }
        }
        if (imageProviderId != null) {
            safeRequest.setImageProviderId(imageProviderId);
        }

        ImageGenerationService.DrawResult result = imageGenerationService.draw(id, safeRequest);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("assistantMessageId", result.assistantMessageId());
        body.put("imageUrl", result.imageUrl());
        body.put("prompt", result.prompt());
        body.put("status", result.status());
        body.put("errorMessage", result.errorMessage());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{id}/messages/{messageId}/status")
    public ResponseEntity<Map<String, Object>> getMessageStatus(@PathVariable Long id,
                                                                  @PathVariable Long messageId) {
        Message message = queueService.getMessage(messageId);
        if (message == null) {
            return ResponseEntity.notFound().build();
        }
        Session session = message.getSession();
        if (session != null && !session.getId().equals(id)) {
            return ResponseEntity.notFound().build();
        }
        checkSessionAccess(session);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messageId", messageId);
        body.put("status", message.getStatus() != null ? message.getStatus().name() : "PENDING");
        body.put("imageUrl", message.getImageUrl());
        body.put("content", message.getContent());
        body.put("errorMessage", message.getErrorMessage());
        body.put("processingInfo", queueService.getProcessingInfo(messageId));
        return ResponseEntity.ok(body);
    }

    @PutMapping("/{id}/messages/{messageId}")
    public ResponseEntity<MessageResponse> editMessage(@PathVariable Long id,
                                                        @PathVariable Long messageId,
                                                        @Valid @RequestBody UpdateMessageRequest request) {
        Session session = sessionService.getSession(id);
        checkSessionAccess(session);
        Message updated = imageGenerationService.editMessage(id, messageId, request.getContent());
        return ResponseEntity.ok(MessageResponse.from(updated));
    }

    @PostMapping("/{id}/messages/{messageId}/regenerate")
    public ResponseEntity<GenerateResponse> regenerate(@PathVariable Long id,
                                                        @PathVariable Long messageId,
                                                        @RequestBody(required = false) RegenerateRequest request) {
        Session session = sessionService.getSession(id);
        checkSessionAccess(session);
        Long chatProviderId = request != null ? request.getChatProviderId() : null;
        Long imageProviderId = request != null ? request.getImageProviderId() : null;
        ImageGenerationService.GenerationResult result = imageGenerationService.regenerateMessage(
                id, messageId, chatProviderId, imageProviderId);
        TokenUsage tokenUsage = result.tokenUsage();
        return ResponseEntity.ok(new GenerateResponse(
                result.messageId(), result.optimizedPrompt(), result.imageUrl(), tokenUsage));
    }

    @DeleteMapping("/{id}/messages/{messageId}")
    public ResponseEntity<Void> deleteMessage(@PathVariable Long id,
                                               @PathVariable Long messageId) {
        Session session = sessionService.getSession(id);
        checkSessionAccess(session);
        imageGenerationService.deleteMessage(id, messageId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancelPending(@PathVariable Long id) {
        Long messageId = queueService.cancelPending(id);
        return ResponseEntity.ok(Map.of(
                "cancelled", messageId != null,
                "messageId", messageId != null ? messageId : -1L));
    }

    @PatchMapping("/{id}/providers")
    public ResponseEntity<Session> updateSessionProviders(@PathVariable Long id,
                                                           @RequestBody Map<String, Long> body) {
        Session session = sessionService.getSession(id);
        checkSessionAccess(session);
        Session updated = sessionService.updateProviders(id,
                body.get("chatProviderId"), body.get("imageProviderId"));
        return ResponseEntity.ok(updated);
    }

    private void checkSessionAccess(Session session) {
        if (session == null) return;
        if (AuthContext.isAdmin()) return;
        Long userId = getCurrentUserId();
        if (userId == null || !userId.equals(session.getUserId())) {
            throw new RuntimeException("无权访问此会话");
        }
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleError(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", ex.getMessage()));
    }
}