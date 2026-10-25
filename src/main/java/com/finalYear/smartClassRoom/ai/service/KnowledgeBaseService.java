package com.finalYear.smartClassRoom.ai.service;

import com.finalYear.smartClassRoom.entity.DocumentChunk;
import com.finalYear.smartClassRoom.entity.KnowledgeDocument;
import com.finalYear.smartClassRoom.entity.User;
import com.finalYear.smartClassRoom.repository.DocumentChunkRepository;
import com.finalYear.smartClassRoom.repository.KnowledgeDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeBaseService {

    private final KnowledgeDocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final DocumentExtractionService extractionService;
    private final EmbeddingService embeddingService;

    @Value("${app.ai.rag.similarity-threshold:0.60}")
    private double similarityThreshold = 0.60;

    public record Citation(String documentTitle, String pageOrSection, double similarityScore) {}

    public record RagAugmentation(
            boolean hasStrongEvidence,
            String augmentedSystemPrompt,
            List<Citation> citations,
            List<String> contextSnippets
    ) {}

    /**
     * Upload and ingest document with SHA-256 deduplication and async embedding.
     */
    @Transactional
    public KnowledgeDocument uploadAndIngest(
            MultipartFile file,
            String title,
            String targetRole,
            Long classroomId,
            Long userId
    ) throws IOException {

        // 1. Validate file, check magic bytes and extract text
        DocumentExtractionService.ExtractionResult result = extractionService.validateAndExtract(file);
        String fileHash = result.fileHash();

        // 2. Deduplication check: skip reprocessing if identical file is already indexed
        Optional<KnowledgeDocument> existing = documentRepository.findByFileHash(fileHash);
        if (existing.isPresent()) {
            KnowledgeDocument doc = existing.get();
            if (doc.getStatus() == KnowledgeDocument.DocumentStatus.INDEXED) {
                log.info("Document with SHA-256 hash {} already exists and is INDEXED. Skipping re-processing.", fileHash);
                return doc;
            }
        }

        // 3. Save KnowledgeDocument entity with status PENDING
        String cleanTitle = (title != null && !title.isBlank())
                ? title.trim()
                : file.getOriginalFilename();

        String role = (targetRole != null && !targetRole.isBlank())
                ? targetRole.toUpperCase(Locale.ROOT)
                : "ALL";

        KnowledgeDocument document = KnowledgeDocument.builder()
                .title(cleanTitle)
                .fileName(file.getOriginalFilename())
                .contentType(result.detectedType())
                .fileSize(file.getSize())
                .fileHash(fileHash)
                .targetRole(role)
                .classroomId(classroomId)
                .uploadedBy(userId)
                .status(KnowledgeDocument.DocumentStatus.PENDING)
                .build();

        document = documentRepository.save(document);

        // 4. Ingest and embed chunks
        processChunksSynchronously(document, result.chunks());

        return document;
    }

    /**
     * Chunks and embeds text. Render disk is ephemeral: raw file is not kept on disk.
     */
    @Transactional
    public void processChunksSynchronously(KnowledgeDocument document, List<DocumentExtractionService.ExtractedChunk> rawChunks) {
        try {
            List<DocumentChunk> chunks = new ArrayList<>();

            for (DocumentExtractionService.ExtractedChunk raw : rawChunks) {
                float[] vector = embeddingService.embedText(raw.content());
                String vectorStr = embeddingService.vectorToString(vector);

                DocumentChunk chunk = DocumentChunk.builder()
                        .document(document)
                        .chunkIndex(raw.chunkIndex())
                        .content(raw.content())
                        .pageOrSection(raw.pageOrSection())
                        .tokenCount(raw.estimatedTokens())
                        .embedding(vectorStr)
                        .build();

                chunks.add(chunk);
            }

            chunkRepository.saveAll(chunks);

            document.setStatus(KnowledgeDocument.DocumentStatus.INDEXED);
            document.setChunkCount(chunks.size());
            document.setUpdatedAt(LocalDateTime.now());
            documentRepository.save(document);

            log.info("Successfully indexed document '{}' with {} chunks and embeddings",
                    document.getTitle(), chunks.size());

        } catch (Exception e) {
            log.error("Failed to process chunks for document {}", document.getId(), e);
            document.setStatus(KnowledgeDocument.DocumentStatus.FAILED);
            document.setFailureReason("Embedding failed: " + e.getMessage());
            documentRepository.save(document);
        }
    }

    /**
     * Delete document and all associated chunks.
     */
    @Transactional
    public void deleteDocument(Long documentId, Long userId, User.Role userRole) {
        KnowledgeDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found with ID: " + documentId));

        if (userRole != User.Role.ADMIN && userRole != User.Role.SUPER_ADMIN && !document.getUploadedBy().equals(userId)) {
            throw new SecurityException("You do not have permission to delete this document.");
        }

        chunkRepository.deleteByDocumentId(documentId);
        documentRepository.delete(document);
        log.info("Deleted document ID {} and its associated chunks", documentId);
    }

    /**
     * Re-index document: clears old chunks and recalculates embeddings.
     */
    @Transactional
    public KnowledgeDocument reindexDocument(Long documentId, Long userId, User.Role userRole) {
        KnowledgeDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found with ID: " + documentId));

        if (userRole != User.Role.ADMIN && userRole != User.Role.SUPER_ADMIN && !document.getUploadedBy().equals(userId)) {
            throw new SecurityException("You do not have permission to re-index this document.");
        }

        chunkRepository.deleteByDocumentId(documentId);
        document.setChunkCount(0);
        document.setStatus(KnowledgeDocument.DocumentStatus.PENDING);
        documentRepository.save(document);

        return document;
    }

    /**
     * Retrieve relevant chunks with role and classroom security filtering applied BEFORE similarity search.
     */
    public List<RankedChunk> retrieveRelevantChunks(String query, User.Role userRole, Long classroomId, int maxResults) {
        List<String> permittedRoles = getPermittedRoles(userRole);

        // Security filter: fetch only chunks permitted for this caller
        List<DocumentChunk> permittedChunks = chunkRepository.findPermittedChunks(permittedRoles, classroomId);
        if (permittedChunks.isEmpty()) {
            return Collections.emptyList();
        }

        // Generate embedding for query
        float[] queryEmbedding = embeddingService.embedText(query);

        // Calculate cosine similarity for permitted chunks
        List<RankedChunk> ranked = new ArrayList<>();
        for (DocumentChunk chunk : permittedChunks) {
            float[] chunkVec = embeddingService.stringToVector(chunk.getEmbedding());
            double score = embeddingService.cosineSimilarity(queryEmbedding, chunkVec);
            ranked.add(new RankedChunk(chunk, score));
        }

        // Sort descending by similarity
        ranked.sort((a, b) -> Double.compare(b.score(), a.score()));

        return ranked.stream()
                .limit(maxResults)
                .toList();
    }

    /**
     * Builds RAG prompt augmentation with source citations and weak-evidence fallback.
     */
    public RagAugmentation augmentPromptWithKnowledge(String query, User.Role userRole, Long classroomId) {
        List<RankedChunk> topChunks = retrieveRelevantChunks(query, userRole, classroomId, 3);

        if (topChunks.isEmpty() || topChunks.get(0).score() < similarityThreshold) {
            // Weak evidence
            String weakPrompt = """
                You are a Smart Classroom AI knowledge assistant.
                Notice: The user's question was checked against uploaded course materials, but no strong evidence was found (below similarity threshold).
                RULE:
                1. Begin your answer explicitly with: "I couldn't find this in the uploaded materials."
                2. Then provide a helpful general explanation clearly labelled with: "[General Knowledge]: "
                """;
            return new RagAugmentation(false, weakPrompt, Collections.emptyList(), Collections.emptyList());
        }

        // Strong evidence found
        StringBuilder contextBuilder = new StringBuilder();
        List<Citation> citations = new ArrayList<>();
        List<String> snippets = new ArrayList<>();

        contextBuilder.append("You are a Smart Classroom AI assistant. Use the following verified classroom materials to answer the question accurately.\n\n");
        contextBuilder.append("--- UPLOADED COURSE MATERIALS ---\n");

        for (int i = 0; i < topChunks.size(); i++) {
            RankedChunk rc = topChunks.get(i);
            String docTitle = rc.chunk().getDocument().getTitle();
            String section = rc.chunk().getPageOrSection();

            contextBuilder.append(String.format("[Source %d: %s, %s]\n%s\n\n",
                    i + 1, docTitle, section, rc.chunk().getContent()));

            citations.add(new Citation(docTitle, section, rc.score()));
            snippets.add(rc.chunk().getContent());
        }

        contextBuilder.append("--- END OF COURSE MATERIALS ---\n\n");
        contextBuilder.append("INSTRUCTIONS:\n");
        contextBuilder.append("1. Answer using the course materials provided above.\n");
        contextBuilder.append("2. Always cite your sources at the end of your response using format: 'Sources: [Document Title, Page/Section]'.\n");

        return new RagAugmentation(true, contextBuilder.toString(), citations, snippets);
    }

    private List<String> getPermittedRoles(User.Role userRole) {
        if (userRole == null) return List.of("ALL");
        return switch (userRole) {
            case SUPER_ADMIN, ADMIN -> List.of("ALL", "ADMIN", "TEACHER", "STUDENT");
            case HOD -> List.of("ALL", "TEACHER", "STUDENT");
            case TEACHER -> List.of("ALL", "TEACHER", "STUDENT");
            case STUDENT -> List.of("ALL", "STUDENT");
        };
    }

    public record RankedChunk(DocumentChunk chunk, double score) {}
}
