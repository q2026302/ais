package com.gs.ais.service;

import com.gs.ais.dto.response.UploadResponse;
import com.gs.ais.config.StoragePaths;
import com.gs.ais.model.entity.Attachment;
import com.gs.ais.repository.AttachmentRepository;
import com.gs.ais.repository.MessageRepository;
import com.gs.ais.model.entity.Message;
import com.gs.ais.security.AuthException;
import com.gs.ais.util.ContentHashUtil;
import com.gs.ais.util.ThumbnailSize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Transactional
public class AttachmentService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

    /**
     * Upper bound for a file read by the reuse path. The reuse endpoint reads a
     * server-side file straight off disk, so it needs its own bound; this is aligned
     * with the HTTP upload path's {@code spring.servlet.multipart.max-file-size}
     * (20MB). The in-memory {@code uploadBytes} path (Feishu) receives bytes that are
     * already resident in heap, so a size check there would not prevent the memory
     * pressure and is intentionally not duplicated here.
     */
    private static final long MAX_REUSE_SOURCE_BYTES = 20L * 1024 * 1024;

    private final AttachmentRepository attachmentRepository;
    private final MessageRepository messageRepository;
    private final ResourceAccessService resourceAccessService;
    private final Path uploadDir;
    private final Path attachmentDir;

    /**
     * Per-content-hash single-flight guards plus an in-process filename cache.
     *
     * <p>De-duplication is "hash lookup → write file → persist record" and is not
     * atomic at the database level: two concurrent uploads of the same content could
     * both observe "no file yet" and write two physical files. A per-hash lock plus
     * an in-memory filename cache (populated right after the physical write, before
     * the DB record commits) collapses that race to a single physical file within
     * this JVM. The cache falls back to the {@code content_sha256} column after a
     * restart, and stale entries pointing to deleted files are re-checked against
     * the filesystem before reuse. Only JDK standard library classes are used.
     */
    private final ConcurrentHashMap<String, Object> dedupLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> dedupFileByHash = new ConcurrentHashMap<>();

    public AttachmentService(AttachmentRepository attachmentRepository,
                             MessageRepository messageRepository,
                             ResourceAccessService resourceAccessService,
                             StoragePaths storagePaths) {
        this.attachmentRepository = attachmentRepository;
        this.messageRepository = messageRepository;
        this.resourceAccessService = resourceAccessService;
        this.uploadDir = storagePaths.uploadDir();
        this.attachmentDir = storagePaths.attachmentDir();
        try {
            Files.createDirectories(attachmentDir);
        } catch (IOException e) {
            throw new RuntimeException("Could not create attachments directory", e);
        }
    }

    public UploadResponse upload(MultipartFile file) {
        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "unnamed";
        try {
            return uploadBytes(originalName, file.getContentType(), file.getBytes());
        } catch (IOException e) {
            throw new RuntimeException("Failed to read upload file", e);
        }
    }

    /** Stores a file received from a server-side integration such as Feishu. */
    public UploadResponse uploadBytes(String originalName, String contentType, byte[] content) {
        String safeOriginalName = originalName != null && !originalName.isBlank() ? originalName : "unnamed";
        byte[] safeContent = content != null ? content : new byte[0];
        String ext = "";
        if (safeOriginalName.contains(".")) {
            ext = safeOriginalName.substring(safeOriginalName.lastIndexOf('.'));
        }

        String sha256 = ContentHashUtil.sha256Hex(safeContent);
        // 秒传：when an identical physical file already exists, reference it instead
        // of writing a new one. Guarded by a per-hash lock + single-flight cache.
        FilenameAllocation allocation = allocateDedupFilename(sha256, ext, safeContent);

        Attachment attachment = new Attachment();
        attachment.setFilename(allocation.filename());
        attachment.setOriginalName(safeOriginalName);
        attachment.setContentType(contentType);
        attachment.setFileSize((long) safeContent.length);
        attachment.setContentSha256(sha256);
        attachment.setFileUrl("/api/attachments/" + allocation.filename());
        // Ownership is captured at upload time so the uploader can access the file
        // before it is associated with a message. Server-side integrations (Feishu)
        // have no AuthContext and leave this null; access then flows via message chain.
        attachment.setOwnerId(resourceAccessService.currentUserId());
        attachment = attachmentRepository.save(attachment);

        log.info("Attachment {}: {} ({} bytes, sha256={})", allocation.deduplicated() ? "deduplicated" : "saved",
                allocation.filename(), safeContent.length, sha256);
        return toResponse(attachment);
    }

    /**
     * Creates a new Attachment record from an existing server-side file,
     * avoiding a download-then-upload cycle. The new record references the
     * existing physical file instead of copying it: a de-duplication lookup on
     * the content hash reuses a known attachment file, an attachment-directory
     * source is referenced in place, and only genuinely new content (an image or
     * thumbnail being turned into an attachment for the first time) is written
     * once.
     *
     * <p>The source is hashed and materialized via streaming (never loaded whole
     * into the heap), and its size is capped at {@link #MAX_REUSE_SOURCE_BYTES}.
     *
     * @param fileUrl      the existing file URL (e.g. /api/images/generated/… or /api/attachments/…)
     * @param originalName optional original filename; derived from fileUrl if absent
     * @param contentType  optional MIME type; probed if absent
     * @return UploadResponse for the newly created attachment
     */
    public UploadResponse reuseAttachment(String fileUrl, String originalName, String contentType) {
        // 0. Reject reuse of resources the caller cannot access (image, attachment,
        //    or either thumbnail form). This prevents referencing another user's files.
        resourceAccessService.requireSourceAccess(fileUrl);

        // 1. Resolve the source filesystem path from the URL
        Path sourcePath = resolveSourcePath(fileUrl);
        if (!Files.isRegularFile(sourcePath)) {
            throw new RuntimeException("Source file not found: " + sourcePath);
        }

        // 2. Determine metadata
        String name = (originalName != null && !originalName.isBlank())
                ? originalName
                : sourcePath.getFileName().toString();
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot >= 0) ext = name.substring(dot);
        String ct = (contentType != null && !contentType.isBlank())
                ? contentType
                : probeContentType(sourcePath);

        long fileSize;
        try {
            fileSize = Files.size(sourcePath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read source file", e);
        }
        if (fileSize > MAX_REUSE_SOURCE_BYTES) {
            throw new RuntimeException("Source file exceeds " + MAX_REUSE_SOURCE_BYTES + " bytes: " + sourcePath);
        }

        // 3. Hash streamed from disk (DigestInputStream), never read whole into heap.
        String sha256;
        try {
            sha256 = ContentHashUtil.sha256Hex(sourcePath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to hash source file", e);
        }

        // 4. Reference the existing physical file; only write when the content is
        //    not yet stored as an attachment. Materialization uses a stream copy.
        String newFilename = referenceExistingOrMaterialize(sourcePath, ext, sha256);

        // 5. Create the Attachment record
        Attachment attachment = new Attachment();
        attachment.setFilename(newFilename);
        attachment.setOriginalName(name);
        attachment.setContentType(ct);
        attachment.setFileSize(fileSize);
        attachment.setContentSha256(sha256);
        attachment.setFileUrl("/api/attachments/" + newFilename);
        attachment.setOwnerId(resourceAccessService.currentUserId());
        attachment = attachmentRepository.save(attachment);

        log.info("Attachment reused from {}: {} ({} bytes, sha256={})", fileUrl, newFilename, fileSize, sha256);
        return toResponse(attachment);
    }

    /**
     * Resolves a server-side file URL to its physical path after enforcing that the
     * current caller may access it. Unlike {@link #reuseAttachment}, this creates no
     * attachment record and copies no bytes — the caller reads the original file in
     * place. A missing or inaccessible source surfaces as a 404 (indistinguishable
     * from a non-existent resource, matching the ownership rules).
     *
     * @param fileUrl an original-file URL ({@code /api/images/...} or
     *                {@code /api/attachments/...}); the {@code ?sig=...} query is ignored.
     */
    public Path resolveExistingSourcePath(String fileUrl) {
        resourceAccessService.requireSourceAccess(fileUrl);
        Path sourcePath = resolveSourcePath(fileUrl);
        if (sourcePath == null || !Files.isRegularFile(sourcePath)) {
            throw new AuthException(404, "资源不存在或无权访问");
        }
        return sourcePath;
    }

    private UploadResponse toResponse(Attachment attachment) {
        UploadResponse response = new UploadResponse(attachment.getId(), attachment.getOriginalName(),
                attachment.getContentType(), attachment.getFileSize(), attachment.getFileUrl());
        response.setThumbnailUrl("/api/attachments/" + attachment.getId() + "/thumbnail");
        return response;
    }

    private record FilenameAllocation(String filename, boolean deduplicated) {
    }

    /**
     * Collapses a content hash to a single stored filename under a per-hash lock so
     * concurrent identical uploads converge on one physical file and all succeed.
     */
    private FilenameAllocation allocateDedupFilename(String sha256, String ext, byte[] content) {
        Object lock = dedupLocks.computeIfAbsent(sha256, key -> new Object());
        synchronized (lock) {
            String existing = findExistingFilenameForHash(sha256);
            if (existing != null) {
                return new FilenameAllocation(existing, true);
            }
            String filename = UUID.randomUUID() + ext;
            try {
                Files.write(attachmentDir.resolve(filename), content);
            } catch (IOException e) {
                throw new RuntimeException("Failed to save attachment file", e);
            }
            dedupFileByHash.put(sha256, filename);
            return new FilenameAllocation(filename, false);
        }
    }

    /**
     * Returns the filename of an existing attachment file whose content matches
     * {@code sha256}, or {@code null} when no such file exists. Checks the
     * in-process single-flight cache first, then the {@code content_sha256} column.
     */
    private String findExistingFilenameForHash(String sha256) {
        if (sha256 == null) {
            return null;
        }
        String cached = dedupFileByHash.get(sha256);
        if (cached != null && Files.isRegularFile(attachmentDir.resolve(cached))) {
            return cached;
        }
        for (Attachment existing : attachmentRepository.findByContentSha256(sha256)) {
            String filename = existing.getFilename();
            if (filename != null && Files.isRegularFile(attachmentDir.resolve(filename))) {
                dedupFileByHash.putIfAbsent(sha256, filename);
                return filename;
            }
        }
        return null;
    }

    /**
     * Chooses a stored filename for reused content without duplicating bytes:
     * <ol>
     *   <li>an attachment file already exists with the same hash → reference it;</li>
     *   <li>the source is already a flat file in the attachment directory → reference it in place;</li>
     *   <li>otherwise (image / thumbnail source) → materialize it once (streamed) into the attachment directory.</li>
     * </ol>
     */
    private String referenceExistingOrMaterialize(Path sourcePath, String ext, String sha256) {
        Object lock = dedupLocks.computeIfAbsent(sha256, key -> new Object());
        synchronized (lock) {
            String existing = findExistingFilenameForHash(sha256);
            if (existing != null) {
                return existing;
            }

            Path normalizedAttachment = attachmentDir.toAbsolutePath().normalize();
            Path normalizedSource = sourcePath.toAbsolutePath().normalize();
            if (normalizedSource.getParent() != null && normalizedSource.getParent().equals(normalizedAttachment)) {
                String inPlace = normalizedSource.getFileName().toString();
                dedupFileByHash.put(sha256, inPlace);
                return inPlace;
            }

            String filename = UUID.randomUUID() + ext;
            try {
                Files.copy(sourcePath, attachmentDir.resolve(filename));
            } catch (IOException e) {
                throw new RuntimeException("Failed to save attachment file", e);
            }
            dedupFileByHash.put(sha256, filename);
            return filename;
        }
    }

    /**
     * Resolves a fileUrl to a physical filesystem path.
     */
    private Path resolveSourcePath(String fileUrl) {
        if (fileUrl == null || fileUrl.isBlank()) {
            throw new RuntimeException("fileUrl must not be empty");
        }

        int queryStart = fileUrl.indexOf('?');
        String path = queryStart >= 0 ? fileUrl.substring(0, queryStart) : fileUrl;
        String sizeParam = queryStart >= 0 ? fileUrl.substring(queryStart + 1) : "";

        // Generated image: /api/images/{id}/thumbnail → resolve the requested thumbnail file.
        if (path.matches("^/api/images/\\d+/thumbnail$")) {
            String idStr = path.replaceAll("^/api/images/(\\d+)/thumbnail$", "$1");
            Long messageId = Long.parseLong(idStr);
            Message msg = messageRepository.findById(messageId)
                    .orElseThrow(() -> new RuntimeException("Message not found: " + messageId));
            String imageUrl = msg.getImageUrl();
            Path original = resolveStoredPath(imageUrl, "/api/images/", uploadDir, "Message has no valid imageUrl");
            return thumbnailPathFor(original, thumbnailSize(sizeParam));
        }

        // Attachment thumbnail: /api/attachments/{id}/thumbnail → resolve the requested thumbnail file.
        if (path.matches("^/api/attachments/\\d+/thumbnail$")) {
            String idStr = path.replaceAll("^/api/attachments/(\\d+)/thumbnail$", "$1");
            Long attachmentId = Long.parseLong(idStr);
            Attachment att = attachmentRepository.findById(attachmentId)
                    .orElseThrow(() -> new RuntimeException("Attachment not found: " + attachmentId));
            Path original = resolveStoredPath(att.getFileUrl(), "/api/attachments/", attachmentDir,
                    "Attachment has no valid fileUrl");
            return thumbnailPathFor(original, thumbnailSize(sizeParam));
        }

        // Generated image: /api/images/...
        if (path.startsWith("/api/images/")) {
            return resolvePath(path.substring("/api/images/".length()), uploadDir, "Invalid image path");
        }

        // Attachment: /api/attachments/...
        if (path.startsWith("/api/attachments/")) {
            return resolvePath(path.substring("/api/attachments/".length()), attachmentDir, "Invalid attachment path");
        }

        throw new RuntimeException("Unsupported fileUrl format: " + fileUrl);
    }

    private static ThumbnailSize thumbnailSize(String query) {
        for (String parameter : query.split("&")) {
            int separator = parameter.indexOf('=');
            if (separator >= 0 && "size".equals(parameter.substring(0, separator))) {
                return ThumbnailSize.fromParam(parameter.substring(separator + 1));
            }
        }
        return ThumbnailSize.fromParam(null);
    }

    private static Path thumbnailPathFor(Path original, ThumbnailSize size) {
        String filename = original.getFileName().toString();
        int lastDot = filename.lastIndexOf('.');
        String baseName = lastDot >= 0 ? filename.substring(0, lastDot) : filename;
        return original.resolveSibling(baseName + "_thumb_" + size.getEdge() + ".png");
    }

    private static Path resolveStoredPath(String fileUrl, String prefix, Path root, String errorMessage) {
        if (fileUrl == null || fileUrl.isBlank() || !fileUrl.startsWith(prefix)) {
            throw new RuntimeException(errorMessage);
        }
        return resolvePath(fileUrl.substring(prefix.length()), root, errorMessage);
    }

    private static Path resolvePath(String relative, Path root, String errorMessage) {
        Path normalizedRoot = root.normalize();
        Path resolved = normalizedRoot.resolve(relative).normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            throw new RuntimeException(errorMessage);
        }
        return resolved;
    }

    private static String probeContentType(Path path) {
        try {
            String probed = Files.probeContentType(path);
            if (probed != null && !probed.isBlank()) return probed;
        } catch (IOException ignored) {
        }
        return "application/octet-stream";
    }
}
