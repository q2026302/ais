package com.gs.ais.controller;

import com.gs.ais.dto.request.CreateFavoriteRequest;
import com.gs.ais.dto.response.FavoriteResponse;
import com.gs.ais.model.entity.AppUser;
import com.gs.ais.repository.AppUserRepository;
import com.gs.ais.security.AuthContext;
import com.gs.ais.security.AuthRole;
import com.gs.ais.service.FavoriteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * User-level work library. Regular users only see their own favourites;
 * administrators see every user's favourites, mirroring {@code SessionController}.
 * The binary thumbnail endpoints live in {@code ImageController} alongside the
 * other image-serving routes.
 *
 * <p>Cancellation is record-scoped ({@code DELETE /api/favorites/{favoriteId}}):
 * the {@code favoriteId} — not the source message id — is the unit of operation,
 * so a cancel removes exactly one row and never another user's save of the same
 * message. The list response carries the owning {@code userId}/{@code userName}
 * so an administrator can manage records one by one.
 */
@RestController
@RequestMapping("/api/favorites")
@Tag(name = "作品库（收藏）", description = "跨会话聚合的用户级作品库：列表、收藏、取消收藏")
public class FavoriteController {

    private final FavoriteService favoriteService;
    private final AppUserRepository appUserRepository;

    public FavoriteController(FavoriteService favoriteService,
                              AppUserRepository appUserRepository) {
        this.favoriteService = favoriteService;
        this.appUserRepository = appUserRepository;
    }

    private Long getCurrentUserId() {
        var principal = AuthContext.get();
        if (principal == null || principal.subject() == null || principal.subject().isBlank()) {
            return null;
        }
        // Security-disabled mode authenticates as a synthetic ADMIN principal. Resolve
        // it to the persisted first administrator (the same rule used by
        // UserManagementService#requireUser) so saving a work has a concrete owner
        // instead of failing with "未登录".
        if ("security-disabled".equals(principal.subject())) {
            return appUserRepository.findFirstByRoleOrderByIdAsc(AuthRole.ADMIN)
                    .map(AppUser::getId)
                    .orElse(null);
        }
        return appUserRepository.findByUsernameIgnoreCase(principal.subject())
                .map(AppUser::getId)
                .orElse(null);
    }

    /** Effective owner scope: {@code null} for admins (all users), the caller id otherwise. */
    private Long scopeUserId() {
        return AuthContext.isAdmin() ? null : getCurrentUserId();
    }

    @Operation(summary = "获取作品库列表", description = "分页返回当前用户收藏的作品，按收藏时间倒序。管理员可见全部收藏。")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "成功返回作品库分页数据")})
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<FavoriteResponse> favorites = favoriteService.list(scopeUserId(), page, size);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", favorites.getContent());
        body.put("totalElements", favorites.getTotalElements());
        body.put("totalPages", favorites.getTotalPages());
        body.put("number", favorites.getNumber());
        body.put("size", favorites.getSize());
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "收藏作品", description = "按消息 ID 收藏一条包含生成图片的消息。重复收藏幂等。")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "收藏成功"),
            @ApiResponse(responseCode = "400", description = "消息不包含图片"),
            @ApiResponse(responseCode = "403", description = "无权收藏该消息"),
            @ApiResponse(responseCode = "404", description = "消息不存在")
    })
    @PostMapping
    public ResponseEntity<FavoriteResponse> add(@Valid @RequestBody CreateFavoriteRequest request) {
        Long userId = getCurrentUserId();
        FavoriteResponse favorite = favoriteService.add(userId, AuthContext.isAdmin(), request.getMessageId());
        return ResponseEntity.status(HttpStatus.CREATED).body(favorite);
    }

    @Operation(summary = "取消收藏",
            description = "按收藏记录 ID 取消一条作品，幂等：记录不存在时同样返回 204。"
                    + "普通用户只能取消自己的记录，管理员可逐条取消任意用户的记录；"
                    + "一次取消只删除该条记录，不影响同一消息下其他用户的收藏。")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "已取消收藏"),
            @ApiResponse(responseCode = "403", description = "普通用户尝试取消他人记录")
    })
    @DeleteMapping("/{favoriteId}")
    public ResponseEntity<Void> remove(@PathVariable Long favoriteId) {
        favoriteService.remove(favoriteId, getCurrentUserId(), AuthContext.isAdmin());
        return ResponseEntity.noContent().build();
    }
}
