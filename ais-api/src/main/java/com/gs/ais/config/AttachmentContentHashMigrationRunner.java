package com.gs.ais.config;

import com.gs.ais.model.entity.Attachment;
import com.gs.ais.repository.AttachmentRepository;
import com.gs.ais.util.ContentHashUtil;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * One-time backfill of the {@code message_attachments.content_sha256} column
 * introduced for SHA-256 upload de-duplication ("秒传"). The column itself is
 * added by Hibernate {@code ddl-auto:update}; this runner computes and stores the
 * digest for every existing attachment that does not have one yet, so upgraded
 * installations participate in de-duplication without re-uploading content.
 *
 * <p>Follows the {@link AttachmentOwnershipMigrationRunner} pattern. Files are
 * streamed through {@link ContentHashUtil} rather than loaded into memory, and a
 * missing physical file (e.g. an already-orphaned upload) is skipped with a
 * warning and left to the orphan cleanup.
 */
@Component
public class AttachmentContentHashMigrationRunner {

    private static final Logger log = LoggerFactory.getLogger(AttachmentContentHashMigrationRunner.class);

    private final AttachmentRepository attachmentRepository;
    private final Path attachmentDir;

    public AttachmentContentHashMigrationRunner(AttachmentRepository attachmentRepository,
                                                StoragePaths storagePaths) {
        this.attachmentRepository = attachmentRepository;
        this.attachmentDir = storagePaths.attachmentDir();
    }

    @PostConstruct
    @Transactional
    public void backfillContentHashes() {
        List<Attachment> missingHash = attachmentRepository.findAll().stream()
                .filter(attachment -> attachment.getContentSha256() == null
                        || attachment.getContentSha256().isBlank())
                .toList();

        if (missingHash.isEmpty()) {
            return;
        }

        log.info("Backfilling content_sha256 for {} attachments", missingHash.size());
        int backfilled = 0;
        int skipped = 0;
        for (Attachment attachment : missingHash) {
            String filename = attachment.getFilename();
            if (filename == null) {
                skipped++;
                continue;
            }
            Path file = attachmentDir.resolve(filename);
            if (!Files.isRegularFile(file)) {
                log.warn("Skipping content_sha256 backfill for missing file: {}", filename);
                skipped++;
                continue;
            }
            try {
                attachment.setContentSha256(ContentHashUtil.sha256Hex(file));
                attachmentRepository.save(attachment);
                backfilled++;
            } catch (IOException e) {
                log.warn("Failed to hash attachment file {}: {}", filename, e.getMessage());
                skipped++;
            }
        }
        log.info("Attachment content_sha256 backfill complete (backfilled={}, skipped={})",
                backfilled, skipped);
    }
}
