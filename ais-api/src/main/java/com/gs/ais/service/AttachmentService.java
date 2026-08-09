package com.gs.ais.service;

import com.gs.ais.dto.response.UploadResponse;
import com.gs.ais.config.StoragePaths;
import com.gs.ais.model.entity.Attachment;
import com.gs.ais.repository.AttachmentRepository;
import com.gs.ais.repository.MessageRepository;
import com.gs.ais.model.entity.Message;
import com.gs.ais.util.ThumbnailSize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Service
@Transactional
public class AttachmentService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

    private final AttachmentRepository attachmentRepository;
    private final MessageRepository messageRepository;
    private final Path uploadDir;
    private final Path attachmentDir;

    public AttachmentService(AttachmentRepository attachmentRepository, MessageRepository messageRepository, StoragePaths storagePaths) {
        this.attachmentRepository = attachmentRepository;
        this.messageRepository = messageRepository;
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
        String filename = UUID.randomUUID() + ext;

        try {
            Files.write(attachmentDir.resolve(filename), safeContent);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save attachment file", e);
        }

        Attachment attachment = new Attachment();
        attachment.setFilename(filename);
        attachment.setOriginalName(safeOriginalName);
        attachment.setContentType(contentType);
        attachment.setFileSize((long) safeContent.length);
        attachment.setFileUrl("/api/attachments/" + filename);
        attachment = attachmentRepository.save(attachment);

        log.info("Attachment saved: {} ({} bytes)", filename, safeContent.length);
        return new UploadResponse(attachment.getId(), attachment.getOriginalName(), attachment.getContentType(),
                attachment.getFileSize(), attachment.getFileUrl());
    }

    /**
     * Creates a new Attachment record from an existing server-side file,
     * avoiding a download-then-upload cycle. The file is copied to the
     * attachment directory so it can be served via the standard attachment URL.
     *
     * @param fileUrl      the existing file URL (e.g. /api/images/generated/… or /api/attachments/…)
     * @param originalName optional original filename; derived from fileUrl if absent
     * @param contentType  optional MIME type; probed if absent
     * @return UploadResponse for the newly created attachment
     */
    public UploadResponse reuseAttachment(String fileUrl, String originalName, String contentType) {
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
            throw new RuntimeException("Failed to read source file size", e);
        }

        // 3. Copy to attachment directory with a new UUID filename
        String newFilename = UUID.randomUUID() + ext;
        Path destPath = attachmentDir.resolve(newFilename);
        try {
            Files.copy(sourcePath, destPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Failed to copy attachment file", e);
        }

        // 4. Create the Attachment record
        Attachment attachment = new Attachment();
        attachment.setFilename(newFilename);
        attachment.setOriginalName(name);
        attachment.setContentType(ct);
        attachment.setFileSize(fileSize);
        attachment.setFileUrl("/api/attachments/" + newFilename);
        attachment = attachmentRepository.save(attachment);

        log.info("Attachment reused from {}: {} ({} bytes)", fileUrl, newFilename, fileSize);
        return new UploadResponse(attachment.getId(), attachment.getOriginalName(), attachment.getContentType(),
                attachment.getFileSize(), attachment.getFileUrl());
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
