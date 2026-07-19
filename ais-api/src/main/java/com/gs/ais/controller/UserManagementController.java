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

    public UserManagementController(UserManagementService userManagementService,
                                    AppUserRepository appUserRepository) {
        this.userManagementService = userManagementService;
        this.appUserRepository = appUserRepository;
    }

    @GetMapping
    public List<UserResponse> list() {
        return userManagementService.listUsers();
    }

    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userManagementService.createUser(request));
    }

    @PutMapping("/{id}")
    public UserResponse update(@PathVariable Long id,
                               @Valid @RequestBody UpdateUserRequest request) {
        return userManagementService.updateUser(id, request, AuthContext.get());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        userManagementService.deleteUser(id, AuthContext.get());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/password")
    public ResponseEntity<Void> resetPassword(@PathVariable Long id,
                                              @Valid @RequestBody ResetUserPasswordRequest request) {
        userManagementService.resetPassword(id, request);
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