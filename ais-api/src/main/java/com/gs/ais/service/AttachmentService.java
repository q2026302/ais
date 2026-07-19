package com.gs.ais.service;

import com.gs.ais.dto.response.UploadResponse;
import com.gs.ais.config.StoragePaths;
import com.gs.ais.model.entity.Attachment;
import com.gs.ais.repository.AttachmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Service
@Transactional
public class AttachmentService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

    private final AttachmentRepository attachmentRepository;
    private final Path uploadDir;

    public AttachmentService(AttachmentRepository attachmentRepository, StoragePaths storagePaths) {
        this.attachmentRepository = attachmentRepository;
        this.uploadDir = storagePaths.attachmentDir();
        try {
            Files.createDirectories(uploadDir);
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
            Files.write(uploadDir.resolve(filename), safeContent);
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
}
