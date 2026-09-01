package com.gs.ais.service;

import com.gs.ais.config.StoragePaths;
import com.gs.ais.model.entity.Message;
import com.gs.ais.repository.AttachmentRepository;
import com.gs.ais.repository.MessageRepository;
import com.gs.ais.util.ReferenceFileUrls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Reference-safe deletion of AI-generated image files.
 *
 * <p>Before de-duplication and materialization, an AI-generated image was
 * referenced by exactly one message, so deleting a message/session could delete
 * the file directly. Deletion must now follow the same strategy as attachment
 * deletion: remove the physical file only when no other message or attachment
 * record still references it, otherwise drop only the database relationship and
 * leave the bytes for the remaining references.
 *
 * <p>It also deletes every thumbnail variant the generated image may have
 * produced — the eagerly-written legacy {@code _thumb.png} plus the lazily
 * generated {@code _thumb_256.png} / {@code _thumb_512.png} — so no thumbnail
 * leaks behind.
 */
@Service
public class GeneratedImageFileService {

    private static final Logger log = LoggerFactory.getLogger(GeneratedImageFileService.class);
    private static final String IMAGE_URL_PREFIX = "/api/images/";
    private static final List<String> THUMBNAIL_SUFFIXES =
            List.of("_thumb.png", "_thumb_256.png", "_thumb_512.png");

    private final MessageRepository messageRepository;
    private final AttachmentRepository attachmentRepository;
    private final Path uploadDir;

    public GeneratedImageFileService(MessageRepository messageRepository,
                                     AttachmentRepository attachmentRepository,
                                     StoragePaths storagePaths) {
        this.messageRepository = messageRepository;
        this.attachmentRepository = attachmentRepository;
        this.uploadDir = storagePaths.uploadDir().toAbsolutePath().normalize();
    }

    /**
     * Deletes {@code imageUrl}'s physical file (original + all thumbnail variants)
     * only when it is not referenced by any other record.
     *
     * @param imageUrl              the persisted {@code message.imageUrl} value
     * @param excludingMessageIds   message ids whose {@code imageUrl} reference is
     *                              about to be dropped (the records being deleted),
     *                              so they are not counted as "still referenced"
     * @return {@code true} when the physical file was removed
     */
    public boolean deleteIfUnreferenced(String imageUrl, Collection<Long> excludingMessageIds) {
        if (imageUrl == null || !imageUrl.startsWith(IMAGE_URL_PREFIX)) {
            return false;
        }
        String relative = imageUrl.substring(IMAGE_URL_PREFIX.length());
        if (relative.isBlank() || relative.contains("..")) {
            return false;
        }
        Path original = uploadDir.resolve(relative).normalize();
        if (!original.startsWith(uploadDir)) {
            return false;
        }
        if (isReferencedElsewhere(imageUrl, excludingMessageIds)) {
            log.info("Skipping delete of still-referenced generated image {}", relative);
            return false;
        }
        deleteFileAndThumbnails(original);
        return true;
    }

    /** Convenience overload for callers with no messages to exclude. */
    public boolean deleteIfUnreferenced(String imageUrl) {
        return deleteIfUnreferenced(imageUrl, Collections.emptySet());
    }

    private boolean isReferencedElsewhere(String imageUrl, Collection<Long> excludingMessageIds) {
        boolean referencedByOtherMessage = messageRepository.findByImageUrl(imageUrl).stream()
                .anyMatch(message -> message.getId() == null || !excludingMessageIds.contains(message.getId()));
        if (referencedByOtherMessage) {
            return true;
        }
        // Existing-file references persisted on surviving messages (draw requests that
        // reuse a history generated image without an attachment record). These are
        // stored in Message.reference_file_urls as raw /api/images/... paths, so a
        // source image referenced this way must not be physically deleted.
        if (isReferencedByReferenceUrls(imageUrl, excludingMessageIds)) {
            return true;
        }
        // Defensive: attachment records always store under /api/attachments/ today, so
        // this is empty in practice, but a same-URL attachment reference would still
        // keep the file alive. Cheap to check and matches the review requirement to
        // consider both messages and attachment records before physical deletion.
        return !attachmentRepository.findByFileUrl(imageUrl).isEmpty();
    }

    /**
     * True when any surviving message (excluding {@code excludingMessageIds})
     * references {@code imageUrl}'s raw path in its {@code reference_file_urls}.
     * The match is exact and query-stripped so it cannot protect a different file
     * that merely shares a prefix or a name.
     */
    private boolean isReferencedByReferenceUrls(String imageUrl, Collection<Long> excludingMessageIds) {
        List<String> storedValues = messageRepository.findMessagesWithReferenceFileUrls().stream()
                .filter(message -> message.getId() == null || !excludingMessageIds.contains(message.getId()))
                .map(Message::getReferenceFileUrls)
                .toList();
        return ReferenceFileUrls.containsPath(storedValues, imageUrl);
    }

    private void deleteFileAndThumbnails(Path original) {
        deleteIfExists(original);
        String fileName = original.getFileName().toString();
        int lastDot = fileName.lastIndexOf('.');
        String baseName = lastDot >= 0 ? fileName.substring(0, lastDot) : fileName;
        Path parent = original.getParent();
        for (String suffix : THUMBNAIL_SUFFIXES) {
            deleteIfExists(parent.resolve(baseName + suffix));
        }
    }

    private void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to delete generated image file {}", path, e);
        }
    }
}
