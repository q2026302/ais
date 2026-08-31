package com.gs.ais.config;

import com.gs.ais.model.entity.Attachment;
import com.gs.ais.repository.AttachmentRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * One-time backfill of the {@code message_attachments.owner_id} column introduced
 * for image/attachment access control. Existing attachments that are already linked
 * to a message inherit that message's session owner, so upgraded installations keep
 * accessing their historical attachments through the owner clause (in addition to
 * the message → session chain). Orphan uploads with no message chain stay owner-less
 * and become admin-only, which is the intended tightening.
 *
 * <p>Follows the {@link SessionMigrationRunner} pattern: the column itself is added
 * by Hibernate {@code ddl-auto:update}; this runner only backfills data.
 */
@Component
public class AttachmentOwnershipMigrationRunner {

    private static final Logger log = LoggerFactory.getLogger(AttachmentOwnershipMigrationRunner.class);

    private final AttachmentRepository attachmentRepository;

    public AttachmentOwnershipMigrationRunner(AttachmentRepository attachmentRepository) {
        this.attachmentRepository = attachmentRepository;
    }

    @PostConstruct
    @Transactional
    public void backfillAttachmentOwners() {
        List<Attachment> withoutOwner = attachmentRepository.findAll().stream()
                .filter(attachment -> attachment.getOwnerId() == null)
                .filter(attachment -> attachment.getMessage() != null
                        && attachment.getMessage().getSession() != null
                        && attachment.getMessage().getSession().getUserId() != null)
                .toList();

        if (withoutOwner.isEmpty()) {
            return;
        }

        log.info("Backfilling owner_id for {} attachments from their message/session chain", withoutOwner.size());
        for (Attachment attachment : withoutOwner) {
            attachment.setOwnerId(attachment.getMessage().getSession().getUserId());
            attachmentRepository.save(attachment);
        }
        log.info("Attachment ownership backfill complete");
    }
}
