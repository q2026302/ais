package com.gs.ais.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gs.ais.config.SecurityProperties;
import com.gs.ais.dto.response.ErrorResponse;
import com.gs.ais.model.entity.AppUser;
import com.gs.ais.repository.AppUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AuthFilter extends OncePerRequestFilter {

    private static final List<String> ADMIN_ALWAYS_PATTERNS = List.of(
            "/api/admin/**",
            "/api/provider-accounts/**",
            "/api/llm-debug/**",
            "/api/model-catalogs/**"
    );

    private final SecurityProperties properties;
    private final AccessTokenService accessTokenService;
    private final AppUserRepository appUserRepository;
    private final ObjectMapper objectMapper;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public AuthFilter(SecurityProperties properties,
                      AccessTokenService accessTokenService,
                      AppUserRepository appUserRepository,
                      ObjectMapper objectMapper) {
        this.properties = properties;
        this.accessTokenService = accessTokenService;
        this.appUserRepository = appUserRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        String path = path(request);
        // Static images/attachments and non-API assets are public so <img> tags work.
        if (path.startsWith("/api/images/") || path.startsWith("/api/attachments/")) {
            return true;
        }
        if (!path.startsWith("/api/") && !path.startsWith("/actuator")
                && !path.startsWith("/v3/api-docs") && !path.startsWith("/swagger-ui")) {
            return true;
        }
        return isPublic(path, request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = path(request);
        try {
            if (!properties.isEnabled()) {
                AuthContext.set(new AuthPrincipal(AuthRole.ADMIN, "security-disabled"));
                filterChain.doFilter(request, response);
                return;
            }

            String header = request.getHeader("Authorization");
            String token = null;
            if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
                token = header.substring(7).trim();
            }
            AuthPrincipal tokenPrincipal = accessTokenService.parseToken(token);
            AppUser user = appUserRepository.findByUsernameIgnoreCase(tokenPrincipal.subject())
                    .orElseThrow(() -> new AuthException(401, "用户不存在或访问凭证已失效"));
            if (!user.isEnabled()) {
                throw new AuthException(403, "账号已禁用");
            }
            // Resolve role and enabled state from the database so an administrator's
            // role/status changes take effect immediately instead of waiting for token expiry.
            AuthPrincipal principal = new AuthPrincipal(user.getRole(), user.getUsername());
            AuthRole required = requiredRole(path, request.getMethod());
            if (!principal.role().satisfies(required)) {
                writeError(response, 403, "Forbidden", "需要管理员权限");
                return;
            }
            AuthContext.set(principal);
            filterChain.doFilter(request, response);
        } catch (AuthException ex) {
            writeError(response, ex.getStatus(),
                    ex.getStatus() == 403 ? "Forbidden" : "Unauthorized",
                    ex.getMessage());
        } finally {
            AuthContext.clear();
        }
    }

    private AuthRole requiredRole(String path, String method) {
        if (isAdminPath(path, method)) {
            return AuthRole.ADMIN;
        }
        return AuthRole.USER;
    }

    private boolean isAdminPath(String path, String method) {
        for (String pattern : ADMIN_ALWAYS_PATTERNS) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        // User defaults are accessible by any authenticated user
        if (pathMatcher.match("/api/admin/users/defaults", path)) {
            return false;
        }
        // Provider catalog: reads are USER, mutations are ADMIN.
        if (pathMatcher.match("/api/providers/**", path) || "/api/providers".equals(path)) {
            return !HttpMethod.GET.matches(method);
        }
        return false;
    }

    private boolean isPublic(String path, String method) {
        if ("/api/version".equals(path) && HttpMethod.GET.matches(method)) return true;
        if ("/api/auth/captcha".equals(path) && HttpMethod.GET.matches(method)) return true;
        if ("/api/auth/password-key".equals(path) && HttpMethod.GET.matches(method)) return true;
        if ("/api/auth/login".equals(path) && HttpMethod.POST.matches(method)) return true;
        if ("/api/auth/status".equals(path) && HttpMethod.GET.matches(method)) return true;
        if ("/api/auth/me".equals(path) && HttpMethod.GET.matches(method)) return true;
        return "/api/auth/logout".equals(path) && HttpMethod.POST.matches(method);
    }

    private static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            return uri.substring(context.length());
        }
        return uri;
    }

    private void writeError(HttpServletResponse response, int status, String error, String message)
            throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), new ErrorResponse(status, error, message));
    }
}
