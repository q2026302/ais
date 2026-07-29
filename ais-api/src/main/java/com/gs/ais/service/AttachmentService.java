package com.gs.ais.service;

import com.gs.ais.dto.response.UploadResponse;
import com.gs.ais.config.StoragePaths;
import com.gs.ais.model.entity.Attachment;
import com.gs.ais.repository.AttachmentRepository;
import com.gs.ais.repository.MessageRepository;
import com.gs.ais.model.entity.Message;
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

        // Generated image: /api/images/{id}/thumbnail  → load Message → get imageUrl
        if (fileUrl.matches("^/api/images/\\d+/thumbnail$")) {
            String idStr = fileUrl.replaceAll("^/api/images/(\\d+)/thumbnail$", "$1");
            Long messageId = Long.parseLong(idStr);
            Message msg = messageRepository.findById(messageId)
                    .orElseThrow(() -> new RuntimeException("Message not found: " + messageId));
            String imageUrl = msg.getImageUrl();
            if (imageUrl == null || imageUrl.isBlank() || !imageUrl.startsWith("/api/images/")) {
                throw new RuntimeException("Message has no valid imageUrl");
            }
            String relative = imageUrl.substring("/api/images/".length());
            return uploadDir.resolve(relative).normalize();
        }

        // Attachment thumbnail: /api/attachments/{id}/thumbnail → load Attachment → get fileUrl
        if (fileUrl.matches("^/api/attachments/\\d+/thumbnail$")) {
            String idStr = fileUrl.replaceAll("^/api/attachments/(\\d+)/thumbnail$", "$1");
            Long attachmentId = Long.parseLong(idStr);
            Attachment att = attachmentRepository.findById(attachmentId)
                    .orElseThrow(() -> new RuntimeException("Attachment not found: " + attachmentId));
            String attFileUrl = att.getFileUrl();
            if (attFileUrl == null || attFileUrl.isBlank() || !attFileUrl.startsWith("/api/attachments/")) {
                throw new RuntimeException("Attachment has no valid fileUrl");
            }
            String filename = attFileUrl.substring("/api/attachments/".length());
            return attachmentDir.resolve(filename).normalize();
        }

        // Generated image: /api/images/...
        if (fileUrl.startsWith("/api/images/")) {
            String relative = fileUrl.substring("/api/images/".length());
            Path resolved = uploadDir.resolve(relative).normalize();
            // Security: prevent directory traversal
            if (!resolved.startsWith(uploadDir.normalize())) {
                throw new RuntimeException("Invalid image path");
            }
            return resolved;
        }

        // Attachment: /api/attachments/...
        if (fileUrl.startsWith("/api/attachments/")) {
            String relative = fileUrl.substring("/api/attachments/".length());
            Path resolved = attachmentDir.resolve(relative).normalize();
            if (!resolved.startsWith(attachmentDir.normalize())) {
                throw new RuntimeException("Invalid attachment path");
            }
            return resolved;
        }

        throw new RuntimeException("Unsupported fileUrl format: " + fileUrl);
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
