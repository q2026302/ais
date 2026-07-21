package com.gs.ais.service;

import com.gs.ais.model.entity.OperationLog;
import com.gs.ais.repository.AppUserRepository;
import com.gs.ais.repository.OperationLogRepository;
import com.gs.ais.security.AuthPrincipal;
import com.gs.ais.util.ClientIpUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class OperationLogService {

    private static final Logger log = LoggerFactory.getLogger(OperationLogService.class);
    private static final int MAX_PAGE_SIZE = 100;

    private final OperationLogRepository repository;
    private final AppUserRepository appUserRepository;
    private final TaskExecutor taskExecutor;

    public OperationLogService(OperationLogRepository repository,
                               AppUserRepository appUserRepository,
                               @Qualifier("operationLogTaskExecutor") TaskExecutor taskExecutor) {
        this.repository = repository;
        this.appUserRepository = appUserRepository;
        this.taskExecutor = taskExecutor;
    }

    /**
     * Captures the request actor synchronously, then persists the audit entry outside
     * the request path. Audit failures must never break the original business action.
     */
    public void record(AuthPrincipal principal,
                       String action,
                       String targetType,
                       Object targetId,
                       String detail,
                       HttpServletRequest request) {
        String username = principal == null ? null : trim(principal.subject(), 64);
        Long userId = resolveUserId(username);
        String ip = trim(ClientIpUtils.resolve(request), 64);
        String normalizedAction = trim(action, 64);
        String normalizedTargetType = trim(targetType, 64);
        String normalizedTargetId = targetId == null ? null : trim(String.valueOf(targetId), 128);
        String normalizedDetail = trim(detail, 1000);

        taskExecutor.execute(() -> {
            try {
                OperationLog operationLog = new OperationLog();
                operationLog.setUserId(userId);
                operationLog.setUsername(username);
                operationLog.setAction(normalizedAction == null ? "UNKNOWN" : normalizedAction);
                operationLog.setTargetType(normalizedTargetType);
                operationLog.setTargetId(normalizedTargetId);
                operationLog.setDetail(normalizedDetail);
                operationLog.setIp(ip);
                operationLog.setCreatedAt(LocalDateTime.now());
                repository.save(operationLog);
            } catch (Exception ex) {
                log.warn("Unable to persist operation log action={}", normalizedAction, ex);
            }
        });
    }

    public Page<OperationLog> findPage(int page,
                                       int size,
                                       String username,
                                       String action,
                                       LocalDateTime start,
                                       LocalDateTime end) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        Specification<OperationLog> specification = (root, query, builder) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            if (StringUtils.hasText(username)) {
                predicates.add(builder.like(builder.lower(root.get("username")), "%" + username.trim().toLowerCase() + "%"));
            }
            if (StringUtils.hasText(action)) {
                predicates.add(builder.equal(root.get("action"), action.trim()));
            }
            if (start != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("createdAt"), start));
            }
            if (end != null) {
                predicates.add(builder.lessThanOrEqualTo(root.get("createdAt"), end));
            }
            return predicates.isEmpty() ? builder.conjunction() : builder.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        return repository.findAll(specification, pageable);
    }

    private Long resolveUserId(String username) {
        if (!StringUtils.hasText(username) || "security-disabled".equals(username)) {
            return null;
        }
        return appUserRepository.findByUsernameIgnoreCase(username).map(user -> user.getId()).orElse(null);
    }

    private static String trim(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }
}
