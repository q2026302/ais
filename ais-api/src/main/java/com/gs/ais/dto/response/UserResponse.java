package com.gs.ais.dto.response;

import com.gs.ais.model.entity.AppUser;
import com.gs.ais.security.AuthRole;

import java.time.LocalDateTime;

public record UserResponse(
        Long id,
        String username,
        String displayName,
        String email,
        AuthRole role,
        boolean enabled,
        String createdAt,
        String updatedAt,
        Long defaultChatProviderId,
        Long defaultImageProviderId
) {
    public static UserResponse from(AppUser user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getDisplayName(), user.getEmail(),
                user.getRole(), user.isEnabled(), format(user.getCreatedAt()), format(user.getUpdatedAt()),
                user.getDefaultChatProviderId(), user.getDefaultImageProviderId());
    }

    private static String format(LocalDateTime value) {
        return value == null ? null : value.toString();
    }
}