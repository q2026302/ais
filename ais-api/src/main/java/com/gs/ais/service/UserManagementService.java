package com.gs.ais.service;

import com.gs.ais.dto.request.ChangePasswordRequest;
import com.gs.ais.dto.request.CreateUserRequest;
import com.gs.ais.dto.request.ResetUserPasswordRequest;
import com.gs.ais.dto.request.UpdateUserRequest;
import com.gs.ais.dto.request.UserProfileRequest;
import com.gs.ais.dto.response.UserResponse;
import com.gs.ais.model.entity.AppUser;
import com.gs.ais.repository.AppUserRepository;
import com.gs.ais.security.AccessTokenService;
import com.gs.ais.security.AuthException;
import com.gs.ais.security.AuthPrincipal;
import com.gs.ais.security.AuthRole;
import com.gs.ais.security.RsaPasswordCryptoService;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class UserManagementService {

    private final AppUserRepository repository;
    private final AccessTokenService accessTokenService;
    private final RsaPasswordCryptoService rsaPasswordCryptoService;
    private final PasswordEncoder passwordEncoder;

    public UserManagementService(AppUserRepository repository,
                                  AccessTokenService accessTokenService,
                                  RsaPasswordCryptoService rsaPasswordCryptoService,
                                  PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.accessTokenService = accessTokenService;
        this.rsaPasswordCryptoService = rsaPasswordCryptoService;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public UserResponse currentUser(AuthPrincipal principal) {
        AppUser user = requireUser(principal);
        if (!user.isEnabled()) {
            throw new AuthException(403, "账号已禁用");
        }
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse updateProfile(AuthPrincipal principal, UserProfileRequest request) {
        AppUser user = requireUser(principal);
        user.setDisplayName(normalizeOptional(request.getDisplayName()));
        user.setEmail(normalizeOptional(request.getEmail()));
        return UserResponse.from(repository.save(user));
    }

    @Transactional
    public void changePassword(AuthPrincipal principal, ChangePasswordRequest request) {
        AppUser user = requireUser(principal);
        String currentDigest = rsaPasswordCryptoService.decryptPasswordDigest(
                request.getCurrentKeyId(), request.getCurrentEncryptedPassword());
        String newDigest = rsaPasswordCryptoService.decryptPasswordDigest(
                request.getNewKeyId(), request.getNewEncryptedPassword());
        if (!passwordEncoder.matches(currentDigest, user.getPasswordHash())) {
            throw new AuthException(401, "当前密码不正确");
        }
        if (currentDigest.equalsIgnoreCase(newDigest)) {
            throw new AuthException(400, "新密码不能与当前密码相同");
        }
        user.setPasswordHash(accessTokenService.hashPasswordDigest(newDigest));
        repository.save(user);
    }

    @Transactional(readOnly = true)
    public List<UserResponse> listUsers() {
        return repository.findAll(Sort.by(Sort.Direction.ASC, "username")).stream()
                .map(UserResponse::from)
                .toList();
    }

    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        String username = normalizeRequired(request.getUsername(), "用户名不能为空");
        if (repository.existsByUsernameIgnoreCase(username)) {
            throw new AuthException(409, "用户名已存在");
        }
        String passwordDigest = rsaPasswordCryptoService.decryptPasswordDigest(
                request.getKeyId(), request.getEncryptedPassword());
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setDisplayName(normalizeOptional(request.getDisplayName()));
        user.setEmail(normalizeOptional(request.getEmail()));
        user.setRole(request.getRole() == null ? AuthRole.USER : request.getRole());
        user.setEnabled(request.isEnabled());
        user.setPasswordHash(accessTokenService.hashPasswordDigest(passwordDigest));
        return UserResponse.from(repository.save(user));
    }

    @Transactional
    public UserResponse updateUser(Long id, UpdateUserRequest request, AuthPrincipal operator) {
        AppUser user = requireById(id);
        ensureAdminInvariant(user, request.getRole(), request.isEnabled(), operator);
        user.setDisplayName(normalizeOptional(request.getDisplayName()));
        user.setEmail(normalizeOptional(request.getEmail()));
        user.setRole(request.getRole());
        user.setEnabled(request.isEnabled());
        return UserResponse.from(repository.save(user));
    }

    @Transactional
    public void deleteUser(Long id, AuthPrincipal operator) {
        AppUser user = requireById(id);
        if (sameUser(user, operator)) {
            throw new AuthException(400, "不能删除当前登录账号");
        }
        ensureAdminInvariant(user, null, false, operator);
        repository.delete(user);
    }

    @Transactional
    public void resetPassword(Long id, ResetUserPasswordRequest request) {
        AppUser user = requireById(id);
        String passwordDigest = rsaPasswordCryptoService.decryptPasswordDigest(
                request.getKeyId(), request.getEncryptedPassword());
        user.setPasswordHash(accessTokenService.hashPasswordDigest(passwordDigest));
        repository.save(user);
    }

    private AppUser requireUser(AuthPrincipal principal) {
        if (principal == null || !StringUtils.hasText(principal.subject())) {
            throw new AuthException(401, "缺少访问凭证");
        }
        if ("security-disabled".equals(principal.subject())) {
            return repository.findFirstByRoleOrderByIdAsc(AuthRole.ADMIN)
                    .orElseThrow(() -> new AuthException(401, "当前没有可用的管理员账号"));
        }
        return repository.findByUsernameIgnoreCase(principal.subject())
                .orElseThrow(() -> new AuthException(401, "用户不存在或访问凭证已失效"));
    }

    private AppUser requireById(Long id) {
        if (id == null) {
            throw new AuthException(400, "用户 ID 无效");
        }
        return repository.findById(id)
                .orElseThrow(() -> new AuthException(404, "用户不存在"));
    }

    private void ensureAdminInvariant(AppUser target, AuthRole newRole, Boolean newEnabled, AuthPrincipal operator) {
        boolean remainsAdmin = newRole == null ? target.getRole() == AuthRole.ADMIN : newRole == AuthRole.ADMIN;
        boolean remainsEnabled = newEnabled == null ? target.isEnabled() : newEnabled;
        if (target.getRole() == AuthRole.ADMIN && target.isEnabled() && (!remainsAdmin || !remainsEnabled)) {
            long otherEnabledAdmins = repository.countByRoleAndEnabledTrue(AuthRole.ADMIN)
                    - 1L;
            if (otherEnabledAdmins <= 0) {
                throw new AuthException(400, "不能禁用、降级或删除系统最后一个启用中的管理员");
            }
        }
        if (operator != null && sameUser(target, operator) && (!remainsAdmin || !remainsEnabled)) {
            throw new AuthException(400, "不能禁用或降级当前登录管理员账号");
        }
    }

    private static boolean sameUser(AppUser user, AuthPrincipal principal) {
        return principal != null && principal.subject() != null
                && user.getUsername().equalsIgnoreCase(principal.subject());
    }

    private static String normalizeRequired(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new AuthException(400, message);
        }
        return value.trim();
    }

    private static String normalizeOptional(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
