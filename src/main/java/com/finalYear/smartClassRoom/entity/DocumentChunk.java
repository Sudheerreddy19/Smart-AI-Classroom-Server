package com.finalYear.smartClassRoom.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "document_chunks",
        indexes = {
                @Index(name = "idx_dchunk_doc", columnList = "document_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private KnowledgeDocument document;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "page_or_section", length = 100)
    private String pageOrSection;

    @Column(name = "token_count")
    private Integer tokenCount;

    /**
     * Stored in pgvector format: '[0.012,-0.043,...]'
     */
    @Column(name = "embedding", columnDefinition = "TEXT")
    private String embedding;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
