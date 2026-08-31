package com.gs.ais.repository;

import com.gs.ais.model.entity.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    /**
     * All records whose stored filename matches. De-duplication lets several
     * records share one physical file, so access checks must consider every
     * matching record rather than a single one.
     */
    List<Attachment> findByFilename(String filename);

    /** Records whose stored {@code file_url} matches exactly (used for reference checks). */
    List<Attachment> findByFileUrl(String fileUrl);

    List<Attachment> findByMessageId(Long messageId);

    /** Records whose stored content matches the given SHA-256 hex digest. */
    List<Attachment> findByContentSha256(String contentSha256);

    @Query("select a.fileUrl from Attachment a where a.fileUrl is not null and a.fileUrl <> ''")
    List<String> findAllFileUrls();
}
