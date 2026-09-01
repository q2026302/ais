package com.gs.ais.controller;

import com.gs.ais.config.StoragePaths;
import com.gs.ais.model.entity.Attachment;
import com.gs.ais.model.entity.Message;
import com.gs.ais.repository.AttachmentRepository;
import com.gs.ais.repository.MessageRepository;
import com.gs.ais.security.ResourceUrlSigner;
import com.gs.ais.service.ResourceAccessService;
import com.gs.ais.util.PureThumbnail;
import com.gs.ais.util.ThumbnailSize;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Serves image/attachment binaries and (lazily generated) thumbnails, enforcing
 * ownership on every request. Static {@code /api/images/**} and
 * {@code /api/attachments/**} resource handling in {@code WebConfig} was replaced
 * by these controller endpoints so each response can run the ownership chain.
 *
 * <p>Authenticated resources are served with a <em>private</em> cache directive
 * whose {@code max-age} matches the signed URL's remaining validity, so the
 * browser reuses the copy only while the signature is still live. The signature
 * itself is bound to the requesting user + path, and {@code private} stops shared
 * caches from ever storing the body, so cross-user cache leakage (串号) remains
 * impossible. Public test images ({@code test_*.png}) keep their original
 * {@code no-store} behaviour.
 */
@RestController
public class ImageController {

    private static final Logger log = LoggerFactory.getLogger(ImageController.class);

    private static final String IMAGE_URL_PREFIX = "/api/images/";
    private static final String ATTACHMENT_URL_PREFIX = "/api/attachments/";

    private final MessageRepository messageRepository;
    private final AttachmentRepository attachmentRepository;
    private final ResourceAccessService resourceAccessService;
    private final ResourceUrlSigner resourceUrlSigner;
    private final Path uploadDir;
    private final Path attachmentDir;

    public ImageController(
            MessageRepository messageRepository,
            AttachmentRepository attachmentRepository,
            ResourceAccessService resourceAccessService,
            ResourceUrlSigner resourceUrlSigner,
            StoragePaths storagePaths) {
        this.messageRepository = messageRepository;
        this.attachmentRepository = attachmentRepository;
        this.resourceAccessService = resourceAccessService;
        this.resourceUrlSigner = resourceUrlSigner;
        this.uploadDir = storagePaths.uploadDir();
        this.attachmentDir = storagePaths.attachmentDir();
    }

    /**
     * Serves an AI-generated image file. Ownership follows
     * {@code message.imageUrl → message → session} for the caller; provider test
     * images ({@code test_*.png}) are public (they have no DB record).
     */
    @GetMapping("/api/images/**")
    public ResponseEntity<Resource> imageFile(HttpServletRequest request) {
        String filename = subPath(request, IMAGE_URL_PREFIX);
        if (filename == null || filename.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        boolean publicImage = ResourceUrlSigner.isPublicTestImage(IMAGE_URL_PREFIX + filename);
        if (!publicImage && !resourceAccessService.canAccessImageByPath(filename)) {
            return ResponseEntity.notFound().build();
        }
        return serveFile(uploadDir, filename, publicImage, request);
    }

    /**
     * Serves an uploaded attachment file. Ownership follows the attachment
     * {@code owner_id} or the attachment's message → session chain.
     */
    @GetMapping("/api/attachments/**")
    public ResponseEntity<Resource> attachmentFile(HttpServletRequest request) {
        String filename = subPath(request, ATTACHMENT_URL_PREFIX);
        if (filename == null || filename.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        if (!resourceAccessService.canAccessAttachmentByFilename(filename)) {
            return ResponseEntity.notFound().build();
        }
        return serveFile(attachmentDir, filename, false, request);
    }

    @GetMapping("/api/images/{id}/thumbnail")
    public ResponseEntity<Resource> messageThumbnail(
            @PathVariable("id") Long id,
            @RequestParam(name = "size", defaultValue = "small") String sizeParam,
            HttpServletRequest request) {
        Message message = messageRepository.findById(id).orElse(null);
        if (message == null || !resourceAccessService.canAccessMessage(message)) {
            return ResponseEntity.notFound().build();
        }

        String imageUrl = message.getImageUrl();
        if (imageUrl == null || imageUrl.isBlank() || !imageUrl.startsWith(IMAGE_URL_PREFIX)) {
            return ResponseEntity.notFound().build();
        }

        String relative = imageUrl.substring(IMAGE_URL_PREFIX.length());
        return serveThumbnail(uploadDir, relative, sizeParam, request);
    }

    /**
     * Thumbnail for a user-uploaded attachment image. Resolves the file from the
     * attachment record and lazily builds a longest-edge PNG when missing.
     */
    @GetMapping("/api/attachments/{id}/thumbnail")
    public ResponseEntity<Resource> attachmentThumbnail(
            @PathVariable("id") Long id,
            @RequestParam(name = "size", defaultValue = "small") String sizeParam,
            HttpServletRequest request) {
        Attachment attachment = attachmentRepository.findById(id).orElse(null);
        if (attachment == null || !resourceAccessService.canAccessAttachment(attachment)) {
            return ResponseEntity.notFound().build();
        }

        String contentType = attachment.getContentType();
        if (contentType != null && !contentType.isBlank() && !contentType.toLowerCase().startsWith("image/")) {
            return ResponseEntity.notFound().build();
        }

        String fileUrl = attachment.getFileUrl();
        if (fileUrl == null || fileUrl.isBlank() || !fileUrl.startsWith(ATTACHMENT_URL_PREFIX)) {
            return ResponseEntity.notFound().build();
        }

        String relative = fileUrl.substring(ATTACHMENT_URL_PREFIX.length());
        return serveThumbnail(attachmentDir, relative, sizeParam, request);
    }

    private ResponseEntity<Resource> serveFile(Path rootDir, String relative,
                                               boolean publicResource, HttpServletRequest request) {
        Path file = resolveWithin(rootDir, relative);
        if (file == null) {
            return ResponseEntity.notFound().build();
        }
        CacheControl cacheControl = publicResource
                ? CacheControl.noStore()
                : authenticatedCacheControl(requestPath(request), request.getParameter("sig"));
        return ResponseEntity.ok()
                .cacheControl(cacheControl)
                .contentType(mediaTypeFor(file))
                .body(new FileSystemResource(file));
    }

    private ResponseEntity<Resource> serveThumbnail(Path rootDir, String relative, String sizeParam,
                                                    HttpServletRequest request) {
        Path original = resolveWithin(rootDir, relative);
        if (original == null) {
            return ResponseEntity.notFound().build();
        }

        // Parse size with safe fallback - never throw
        ThumbnailSize size = ThumbnailSize.fromParam(sizeParam);

        Path root = rootDir.toAbsolutePath().normalize();
        Path thumbPath = thumbnailPathFor(original, size);
        // Reuse the safeRegularFile pattern (NOFOLLOW_LINKS probe + real-root
        // containment) so a symlinked thumbnail cannot escape the root on read,
        // and never lazy-write through a symlink that could point outside.
        Path safeThumb = safeRegularFile(root, thumbPath);
        if (safeThumb == null && !Files.exists(thumbPath, LinkOption.NOFOLLOW_LINKS)) {
            generateThumbnail(original, thumbPath, size);
            safeThumb = safeRegularFile(root, thumbPath);
        }

        Path toServe = safeThumb != null ? safeThumb : original;
        MediaType mediaType = mediaTypeFor(toServe);
        Resource resource = new FileSystemResource(toServe);
        return ResponseEntity.ok()
                .cacheControl(authenticatedCacheControl(requestPath(request), request.getParameter("sig")))
                .contentType(mediaType)
                .body(resource);
    }

    /**
     * Cache policy for an authenticated (signed-URL) resource: {@code private}
     * with a {@code max-age} equal to the <em>verified</em> signature's remaining
     * validity. The remaining TTL is read only after the HMAC + expiry + path have
     * been verified, so a caller cannot forge a large {@code max-age} by attaching a
     * decoded-but-unsigned {@code sig} (e.g. alongside an {@code Authorization}
     * header that bypasses URL-signature checks). Falls back to {@code noStore}
     * whenever the signature is absent, forged, expired, or bound to another path —
     * which also covers Bearer-authenticated requests that carry no valid signature.
     */
    private CacheControl authenticatedCacheControl(String path, String sig) {
        long remaining = resourceUrlSigner.remainingValiditySecondsVerified(path, sig);
        if (remaining <= 0L) {
            return CacheControl.noStore();
        }
        return CacheControl.maxAge(remaining, TimeUnit.SECONDS).cachePrivate();
    }

    private Path resolveWithin(Path rootDir, String relative) {
        if (relative == null || relative.isBlank() || relative.contains("..")) {
            return null;
        }
        Path root = rootDir.toAbsolutePath().normalize();
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root)) {
            return null;
        }
        return safeRegularFile(root, file);
    }

    /**
     * Confirms {@code file} is a regular file below {@code root} that is safe to
     * serve. The probe uses {@link LinkOption#NOFOLLOW_LINKS} so a symbolic link is
     * never treated as a regular file, and the real path (via
     * {@link Files#isRegularFile} then {@code toRealPath}) is re-checked against the
     * real root so an intermediate symlinked directory cannot escape the root.
     */
    private static Path safeRegularFile(Path root, Path file) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try {
            Path realRoot = root.toRealPath();
            Path realFile = file.toRealPath();
            if (!realFile.startsWith(realRoot)) {
                return null;
            }
        } catch (IOException e) {
            return null;
        }
        return file;
    }

    /**
     * The context-relative request path (request URI minus servlet context path).
     * This is exactly the {@code path} the {@code AuthFilter} verifies a {@code sig}
     * against, so cache-TTL verification must use the same value.
     */
    private static String requestPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            return uri.substring(context.length());
        }
        return uri;
    }

    /**
     * Extracts the context-relative remainder after {@code prefix} from the request
     * URI, matching the context-path handling used by the auth filter.
     */
    private static String subPath(HttpServletRequest request, String prefix) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        String path = (context != null && !context.isEmpty() && uri.startsWith(context))
                ? uri.substring(context.length())
                : uri;
        if (!path.startsWith(prefix)) {
            return null;
        }
        return path.substring(prefix.length());
    }

    private void generateThumbnail(Path original, Path thumbPath, ThumbnailSize size) {
        try {
            byte[] imageData = Files.readAllBytes(original);
            // PureThumbnail handles size check and skips upscale if original is already small
            PureThumbnail.writeThumbnail(imageData, thumbPath, size.getEdge(), size);
        } catch (IOException e) {
            log.warn("Failed to read original image for thumbnail {}: {}", original, e.getMessage());
        } catch (Throwable e) {
            // Catch Error as well (e.g. NoClassDefFoundError from AWT in native image)
            log.warn("Failed to generate thumbnail for {}: {}", original, e.toString());
        }
    }

    /**
     * Builds thumbnail file name using new rule: {@code xxx_thumb_256.png} or {@code xxx_thumb_512.png}.
     */
    static Path thumbnailPathFor(Path original, ThumbnailSize size) {
        String fileName = original.getFileName().toString();
        int lastDot = fileName.lastIndexOf('.');
        String baseName = (lastDot >= 0 ? fileName.substring(0, lastDot) : fileName);
        String thumbName = baseName + "_thumb_" + size.getEdge() + ".png";
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
