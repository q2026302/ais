package com.gs.ais.service;

import com.gs.ais.model.entity.AppUser;
import com.gs.ais.model.entity.Attachment;
import com.gs.ais.model.entity.Message;
import com.gs.ais.repository.AppUserRepository;
import com.gs.ais.repository.AttachmentRepository;
import com.gs.ais.repository.MessageRepository;
import com.gs.ais.security.AuthContext;
import com.gs.ais.security.AuthException;
import com.gs.ais.security.AuthPrincipal;
import com.gs.ais.security.ResourceUrlSigner;
import org.springframework.stereotype.Service;

/**
 * Central ownership checks for image and attachment binary resources.
 *
 * <p>Rules (admin always allowed, mirroring {@code checkSessionAccess}):
 * <ul>
 *   <li>AI-generated image ({@code /api/images/...}): accessible when a message
 *       whose {@code imageUrl} matches belongs to a session owned by the caller.</li>
 *   <li>Attachment ({@code /api/attachments/...}): accessible when the caller is the
 *       attachment {@code owner_id}, or the attachment is referenced by a message in a
 *       session owned by the caller.</li>
 *   <li>Provider test images ({@code test_*.png}) are public and not DB-backed.</li>
 * </ul>
 */
@Service
public class ResourceAccessService {

    private static final String IMAGE_URL_PREFIX = "/api/images/";
    private static final String ATTACHMENT_URL_PREFIX = "/api/attachments/";

    private final AppUserRepository appUserRepository;
    private final MessageRepository messageRepository;
    private final AttachmentRepository attachmentRepository;

    public ResourceAccessService(AppUserRepository appUserRepository,
                                 MessageRepository messageRepository,
                                 AttachmentRepository attachmentRepository) {
        this.appUserRepository = appUserRepository;
        this.messageRepository = messageRepository;
        this.attachmentRepository = attachmentRepository;
    }

    public boolean isAdmin() {
        return AuthContext.isAdmin();
    }

    public Long currentUserId() {
        AuthPrincipal principal = AuthContext.get();
        if (principal == null || principal.subject() == null || principal.subject().isBlank()) {
            return null;
        }
        return appUserRepository.findByUsernameIgnoreCase(principal.subject())
                .map(AppUser::getId)
                .orElse(null);
    }

    /** Image access by the path segment following {@code /api/images/}. */
    public boolean canAccessImageByPath(String relativePath) {
        if (isAdmin()) {
            return true;
        }
        Long userId = currentUserId();
        if (userId == null) {
            return false;
        }
        String imageUrl = IMAGE_URL_PREFIX + relativePath;
        return messageRepository.findByImageUrl(imageUrl).stream()
                .anyMatch(message -> message.getSession() != null
                        && userId.equals(message.getSession().getUserId()));
    }

    /** Image access by message record (thumbnail endpoints resolve by message id). */
    public boolean canAccessMessage(Message message) {
        if (isAdmin()) {
            return true;
        }
        Long userId = currentUserId();
        if (userId == null) {
            return false;
        }
        return message != null && message.getSession() != null
                && userId.equals(message.getSession().getUserId());
    }

    public boolean canAccessAttachment(Attachment attachment) {
        if (isAdmin()) {
            return true;
        }
        Long userId = currentUserId();
        if (userId == null || attachment == null) {
            return false;
        }
        if (userId.equals(attachment.getOwnerId())) {
            return true;
        }
        Message message = attachment.getMessage();
        return message != null && message.getSession() != null
                && userId.equals(message.getSession().getUserId());
    }

    /**
     * Attachment access by its stored filename (the segment following
     * {@code /api/attachments/}). De-duplication lets several records share one
     * physical file, so the caller is granted access when <em>any</em> matching
     * record is theirs (owner or message → session chain).
     */
    public boolean canAccessAttachmentByFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return false;
        }
        if (isAdmin()) {
            return true;
        }
        return attachmentRepository.findByFilename(filename).stream()
                .anyMatch(this::canAccessAttachment);
    }

    /**
     * Enforces that a {@code fileUrl} (image, attachment, or either thumbnail form)
     * is accessible to the current user before it may be reused. Throws a 404
     * {@link AuthException} on missing resources or missing access so the caller
     * cannot distinguish the two.
     */
    public void requireSourceAccess(String fileUrl) {
        String path = stripQuery(fileUrl);
        if (path == null || path.isBlank()) {
            throw new AuthException(404, "不支持的 fileUrl 格式");
        }

        if (path.matches("^/api/images/\\d+/thumbnail$")) {
            Long id = parseId(path, "^/api/images/(\\d+)/thumbnail$");
            Message message = id == null ? null : messageRepository.findById(id).orElse(null);
            if (message == null || !canAccessMessage(message)) {
                throw new AuthException(404, "资源不存在或无权访问");
            }
            return;
        }

        if (path.matches("^/api/attachments/\\d+/thumbnail$")) {
            Long id = parseId(path, "^/api/attachments/(\\d+)/thumbnail$");
            Attachment attachment = id == null ? null : attachmentRepository.findById(id).orElse(null);
            if (attachment == null || !canAccessAttachment(attachment)) {
                throw new AuthException(404, "资源不存在或无权访问");
            }
            return;
        }

        if (path.startsWith(IMAGE_URL_PREFIX)) {
            String relative = path.substring(IMAGE_URL_PREFIX.length());
            if (!ResourceUrlSigner.isPublicTestImage(path) && !canAccessImageByPath(relative)) {
                throw new AuthException(404, "资源不存在或无权访问");
            }
            return;
        }

        if (path.startsWith(ATTACHMENT_URL_PREFIX)) {
            String filename = path.substring(ATTACHMENT_URL_PREFIX.length());
            if (!canAccessAttachmentByFilename(filename)) {
                throw new AuthException(404, "资源不存在或无权访问");
            }
            return;
        }

        throw new AuthException(404, "不支持的 fileUrl 格式");
    }

    private static String stripQuery(String url) {
        if (url == null) {
            return null;
        }
        int query = url.indexOf('?');
        return query >= 0 ? url.substring(0, query) : url;
    }

    private static Long parseId(String path, String pattern) {
        String idStr = path.replaceAll(pattern, "$1");
        try {
            return Long.parseLong(idStr);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
