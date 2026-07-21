package com.gs.ais.controller;

import com.gs.ais.dto.request.CreateUserRequest;
import com.gs.ais.dto.request.ResetUserPasswordRequest;
import com.gs.ais.dto.request.UpdateUserDefaultsRequest;
import com.gs.ais.dto.request.UpdateUserRequest;
import com.gs.ais.dto.response.UserResponse;
import com.gs.ais.model.entity.AppUser;
import com.gs.ais.repository.AppUserRepository;
import com.gs.ais.security.AuthContext;
import com.gs.ais.service.UserManagementService;
import com.gs.ais.service.OperationLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/users")
public class UserManagementController {

    private final UserManagementService userManagementService;
    private final AppUserRepository appUserRepository;
    private final OperationLogService operationLogService;

    public UserManagementController(UserManagementService userManagementService,
                                    AppUserRepository appUserRepository,
                                    OperationLogService operationLogService) {
        this.userManagementService = userManagementService;
        this.appUserRepository = appUserRepository;
        this.operationLogService = operationLogService;
    }

    @GetMapping
    public List<UserResponse> list() {
        return userManagementService.listUsers();
    }

    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request,
                                               HttpServletRequest httpRequest) {
        UserResponse user = userManagementService.createUser(request);
        operationLogService.record(AuthContext.get(), "ADMIN_USER_CREATE", "USER", user.id(),
                "创建用户：" + user.username(), httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(user);
    }

    @PutMapping("/{id}")
    public UserResponse update(@PathVariable Long id,
                               @Valid @RequestBody UpdateUserRequest request,
                               HttpServletRequest httpRequest) {
        UserResponse user = userManagementService.updateUser(id, request, AuthContext.get());
        operationLogService.record(AuthContext.get(), "ADMIN_USER_UPDATE", "USER", id,
                (user.enabled() ? "启用" : "禁用") + "用户：" + user.username(), httpRequest);
        return user;
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, HttpServletRequest httpRequest) {
        userManagementService.deleteUser(id, AuthContext.get());
        operationLogService.record(AuthContext.get(), "ADMIN_USER_DELETE", "USER", id,
                "删除用户", httpRequest);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/password")
    public ResponseEntity<Void> resetPassword(@PathVariable Long id,
                                              @Valid @RequestBody ResetUserPasswordRequest request,
                                              HttpServletRequest httpRequest) {
        userManagementService.resetPassword(id, request);
        operationLogService.record(AuthContext.get(), "ADMIN_PASSWORD_RESET", "USER", id,
                "重置用户密码", httpRequest);
        return ResponseEntity.noContent().build();
    }

    // User default model settings
    @GetMapping("/defaults")
    public ResponseEntity<Map<String, Object>> getDefaults() {
        var principal = AuthContext.get();
        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录"));
        }
        AppUser user = appUserRepository.findByUsernameIgnoreCase(principal.subject())
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("defaultChatProviderId", user.getDefaultChatProviderId());
        body.put("defaultImageProviderId", user.getDefaultImageProviderId());
        return ResponseEntity.ok(body);
    }

    @PutMapping("/defaults")
    public ResponseEntity<Map<String, Object>> updateDefaults(
            @Valid @RequestBody UpdateUserDefaultsRequest request) {
        var principal = AuthContext.get();
        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录"));
        }
        AppUser user = appUserRepository.findByUsernameIgnoreCase(principal.subject())
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        if (request.getDefaultChatProviderId() != null) {
            user.setDefaultChatProviderId(request.getDefaultChatProviderId());
        }
        if (request.getDefaultImageProviderId() != null) {
            user.setDefaultImageProviderId(request.getDefaultImageProviderId());
        }
        appUserRepository.save(user);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("defaultChatProviderId", user.getDefaultChatProviderId());
        body.put("defaultImageProviderId", user.getDefaultImageProviderId());
        return ResponseEntity.ok(body);
    }
}