package com.finalYear.smartClassRoom.repository;

import com.finalYear.smartClassRoom.entity.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long> {

    List<DocumentChunk> findByDocumentIdOrderByChunkIndexAsc(Long documentId);

    @Modifying
    @Query("DELETE FROM DocumentChunk c WHERE c.document.id = :documentId")
    void deleteByDocumentId(@Param("documentId") Long documentId);

    /**
     * Retrieval filter by the caller's permissions BEFORE similarity search.
     * Only returns chunks belonging to documents matching the caller's permitted roles and classroom.
     */
    @Query("""
        SELECT c FROM DocumentChunk c
        JOIN FETCH c.document d
        WHERE d.status = 'INDEXED'
          AND (d.targetRole = 'ALL' OR d.targetRole IN :roles)
          AND (:classroomId IS NULL OR d.classroomId IS NULL OR d.classroomId = :classroomId)
    """)
    List<DocumentChunk> findPermittedChunks(
            @Param("roles") List<String> roles,
            @Param("classroomId") Long classroomId
    );
}
