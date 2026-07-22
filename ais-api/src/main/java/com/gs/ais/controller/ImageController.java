package com.gs.ais.controller;

import com.gs.ais.config.StoragePaths;
import com.gs.ais.model.entity.Message;
import com.gs.ais.repository.MessageRepository;
import com.gs.ais.util.PureThumbnail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Serves generated image thumbnails. Historical images may lack a prebuilt
 * {@code *_thumb.png}; this endpoint generates one on demand via {@link PureThumbnail}.
 */
@RestController
public class ImageController {

    private static final Logger log = LoggerFactory.getLogger(ImageController.class);
    private static final String IMAGE_URL_PREFIX = "/api/images/";
    private static final int THUMB_MAX_EDGE = 256;

    private final MessageRepository messageRepository;
    private final Path uploadDir;

    public ImageController(MessageRepository messageRepository, StoragePaths storagePaths) {
        this.messageRepository = messageRepository;
        this.uploadDir = storagePaths.uploadDir();
    }

    @GetMapping("/api/images/{id}/thumbnail")
    public ResponseEntity<Resource> thumbnail(@PathVariable("id") Long id) {
        Message message = messageRepository.findById(id).orElse(null);
        if (message == null) {
            return ResponseEntity.notFound().build();
        }

        String imageUrl = message.getImageUrl();
        if (imageUrl == null || imageUrl.isBlank() || !imageUrl.startsWith(IMAGE_URL_PREFIX)) {
            return ResponseEntity.notFound().build();
        }

        String relative = imageUrl.substring(IMAGE_URL_PREFIX.length());
        if (relative.isBlank() || relative.contains("..")) {
            return ResponseEntity.notFound().build();
        }

        Path original = uploadDir.resolve(relative).normalize();
        if (!original.startsWith(uploadDir) || !Files.isRegularFile(original)) {
            return ResponseEntity.notFound().build();
        }

        Path thumbPath = thumbnailPathFor(original);
        if (!Files.isRegularFile(thumbPath)) {
            generateThumbnail(original, thumbPath);
        }

        Path toServe = Files.isRegularFile(thumbPath) ? thumbPath : original;
        MediaType mediaType = mediaTypeFor(toServe);
        Resource resource = new FileSystemResource(toServe);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic())
                .contentType(mediaType)
                .body(resource);
    }

    private void generateThumbnail(Path original, Path thumbPath) {
        try {
            byte[] imageData = Files.readAllBytes(original);
            PureThumbnail.writeLongestEdgePng(imageData, thumbPath, THUMB_MAX_EDGE);
        } catch (IOException e) {
            log.warn("Failed to read original image for thumbnail {}: {}", original, e.getMessage());
        } catch (Throwable e) {
            // Catch Error as well (e.g. NoClassDefFoundError from AWT in native image)
            log.warn("Failed to generate thumbnail for {}: {}", original, e.toString());
        }
    }

    static Path thumbnailPathFor(Path original) {
        String fileName = original.getFileName().toString();
        int lastDot = fileName.lastIndexOf('.');
        String thumbName = (lastDot >= 0 ? fileName.substring(0, lastDot) : fileName) + "_thumb.png";
        Path parent = original.getParent();
        return parent == null ? Path.of(thumbName) : parent.resolve(thumbName);
    }

    private static MediaType mediaTypeFor(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        if (name.endsWith(".gif")) {
            return MediaType.IMAGE_GIF;
        }
        if (name.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        }
        try {
            String probed = Files.probeContentType(path);
            if (probed != null && !probed.isBlank()) {
                return MediaType.parseMediaType(probed);
            }
        } catch (Exception ignored) {
            // fall through
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
