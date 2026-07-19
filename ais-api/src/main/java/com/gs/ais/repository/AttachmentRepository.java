package com.gs.ais.repository;

import com.gs.ais.model.entity.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {
    Attachment findByFilename(String filename);

    List<Attachment> findByMessageId(Long messageId);

    @Query("select a.fileUrl from Attachment a where a.fileUrl is not null and a.fileUrl <> ''")
    List<String> findAllFileUrls();
}
