package com.finalYear.smartClassRoom.repository;

import com.finalYear.smartClassRoom.entity.KnowledgeDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {

    Optional<KnowledgeDocument> findByFileHash(String fileHash);

    boolean existsByFileHash(String fileHash);

    List<KnowledgeDocument> findByStatus(KnowledgeDocument.DocumentStatus status);

    List<KnowledgeDocument> findByUploadedBy(Long uploadedBy);

    List<KnowledgeDocument> findByTargetRoleIn(List<String> roles);
}
