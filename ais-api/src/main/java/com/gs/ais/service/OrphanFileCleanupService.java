package com.gs.ais.service;

import com.gs.ais.config.StoragePaths;
import com.gs.ais.repository.AttachmentRepository;
import com.gs.ais.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

@Service
public class OrphanFileCleanupService {

    private static final Logger log = LoggerFactory.getLogger(OrphanFileCleanupService.class);
    private static final String IMAGE_URL_PREFIX = "/api/images/";
    private static final String ATTACHMENT_URL_PREFIX = "/api/attachments/";

    private final MessageRepository messageRepository;
    private final AttachmentRepository attachmentRepository;
    private final Path uploadRoot;
    private final Path attachmentRoot;

    @Autowired
    public OrphanFileCleanupService(
            MessageRepository messageRepository,
            AttachmentRepository attachmentRepository,
            StoragePaths storagePaths,
            @Value("${maintenance.orphan-cleanup.upload-root:}") String uploadRoot) {
        this(messageRepository, attachmentRepository, resolveConfiguredRoot(storagePaths, uploadRoot));
    }

    private static Path resolveConfiguredRoot(StoragePaths storagePaths, String configured) {
        if (configured == null || configured.isBlank()) {
            return storagePaths.uploadDir();
        }
        Path path = Path.of(configured);
        return path.isAbsolute() ? path : storagePaths.baseDir().resolve(path);
    }

    OrphanFileCleanupService(
            MessageRepository messageRepository,
            AttachmentRepository attachmentRepository,
            Path uploadRoot) {
        this.messageRepository = messageRepository;
        this.attachmentRepository = attachmentRepository;
        this.uploadRoot = uploadRoot.toAbsolutePath().normalize();
        this.attachmentRoot = this.uploadRoot.resolve("attachments").normalize();
    }

    /**
     * Finds files below the upload root that are not referenced by either
     * messages.image_url or message_attachments.file_url. A dry run never modifies disk.
     */
    @Transactional(readOnly = true)
    public CleanupResult cleanup(boolean dryRun) {
        Set<Path> referencedFiles = loadReferencedFiles();
        List<Path> orphanFiles = findOrphanFiles(referencedFiles);
        long totalBytes = orphanFiles.stream().mapToLong(this::sizeSafely).sum();

        log.info(
                "Orphan file cleanup {}: referenced={}, orphaned={}, bytes={}",
                dryRun ? "dry-run" : "execute", referencedFiles.size(), orphanFiles.size(), totalBytes);
        orphanFiles.stream().limit(100).forEach(path ->
                log.info("{} orphan file: {}", dryRun ? "Would delete" : "Deleting", relativeDisplay(path)));
        if (orphanFiles.size() > 100) {
            log.info("{} additional orphan files omitted from log", orphanFiles.size() - 100);
        }

        int deletedFiles = 0;
        if (!dryRun) {
            for (Path orphanFile : orphanFiles) {
                try {
                    if (Files.deleteIfExists(orphanFile)) {
                        deletedFiles++;
                    }
                } catch (IOException e) {
                    log.warn("Failed to delete orphan file {}", orphanFile, e);
                }
            }
            removeEmptyDirectories();
        }

        return new CleanupResult(
                dryRun,
                referencedFiles.size(),
                orphanFiles.size(),
                totalBytes,
                deletedFiles,
                orphanFiles.stream().map(this::relativeDisplay).toList());
    }

    private Set<Path> loadReferencedFiles() {
        Set<Path> referenced = new HashSet<>();
        for (String imageUrl : messageRepository.findAllImageUrls()) {
            resolveLocalUrl(imageUrl, IMAGE_URL_PREFIX, uploadRoot).ifPresent(referenced::add);
        }
        for (String fileUrl : attachmentRepository.findAllFileUrls()) {
            resolveLocalUrl(fileUrl, ATTACHMENT_URL_PREFIX, attachmentRoot).ifPresent(referenced::add);
        }
        return referenced;
    }

    private java.util.Optional<Path> resolveLocalUrl(String url, String prefix, Path baseDir) {
        if (url == null || !url.startsWith(prefix)) {
            return java.util.Optional.empty();
        }
        String relative = stripQueryAndFragment(url.substring(prefix.length()));
        if (relative.isBlank()) {
            return java.util.Optional.empty();
        }

        Path resolved = baseDir.resolve(relative).normalize().toAbsolutePath();
        if (!resolved.startsWith(baseDir)) {
            log.warn("Ignoring unsafe stored file URL: {}", url);
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(resolved);
    }

    private String stripQueryAndFragment(String value) {
        int query = value.indexOf('?');
        int fragment = value.indexOf('#');
        int end = value.length();
        if (query >= 0) {
            end = Math.min(end, query);
        }
        if (fragment >= 0) {
            end = Math.min(end, fragment);
        }
        return value.substring(0, end);
    }

    private List<Path> findOrphanFiles(Set<Path> referencedFiles) {
        if (!Files.isDirectory(uploadRoot, LinkOption.NOFOLLOW_LINKS)) {
            log.info("Upload directory does not exist: {}", uploadRoot);
            return List.of();
        }

        try (Stream<Path> paths = Files.walk(uploadRoot)) {
            return paths
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> path.toAbsolutePath().normalize())
                    .filter(path -> !referencedFiles.contains(path))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan upload directory: " + uploadRoot, e);
        }
    }

    private void removeEmptyDirectories() {
        if (!Files.isDirectory(uploadRoot, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }

        List<Path> directories = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(uploadRoot)) {
            paths.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.reverseOrder())
                    .forEach(directories::add);
        } catch (IOException e) {
            log.warn("Failed to enumerate empty upload directories", e);
            return;
        }

        for (Path directory : directories) {
            if (directory.equals(uploadRoot) || directory.equals(attachmentRoot)) {
                continue;
            }
            try (Stream<Path> entries = Files.list(directory)) {
                if (entries.findAny().isEmpty()) {
                    Files.deleteIfExists(directory);
                }
            } catch (IOException e) {
                log.warn("Failed to remove empty directory {}", directory, e);
            }
        }
    }

    private long sizeSafely(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            log.warn("Failed to read file size for {}", path, e);
            return 0;
        }
    }

    private String relativeDisplay(Path path) {
        return uploadRoot.relativize(path).toString().replace('\\', '/');
    }

    public record CleanupResult(
            boolean dryRun,
            int referencedFiles,
            int orphanFiles,
            long orphanBytes,
            int deletedFiles,
            List<String> candidates) {
    }
}
