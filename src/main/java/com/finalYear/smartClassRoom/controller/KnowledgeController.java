package com.finalYear.smartClassRoom.controller;

import com.finalYear.smartClassRoom.ai.service.KnowledgeBaseService;
import com.finalYear.smartClassRoom.entity.KnowledgeDocument;
import com.finalYear.smartClassRoom.entity.User;
import com.finalYear.smartClassRoom.repository.KnowledgeDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Slf4j
public class KnowledgeController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeDocumentRepository documentRepository;

    /**
     * Upload document for course knowledge base.
     * Enforced in Spring Security: TEACHER, HOD, ADMIN, SUPER_ADMIN only.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('TEACHER', 'HOD', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<?> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "targetRole", defaultValue = "ALL") String targetRole,
            @RequestParam(value = "classroomId", required = false) Long classroomId
    ) {
        Long userId = resolveCurrentUserId();

        try {
            KnowledgeDocument doc = knowledgeBaseService.uploadAndIngest(
                    file, title, targetRole, classroomId, userId
            );

            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                    "status", "SUCCESS",
                    "documentId", doc.getId(),
                    "title", doc.getTitle(),
                    "fileName", doc.getFileName(),
                    "fileHash", doc.getFileHash(),
                    "chunkCount", doc.getChunkCount(),
                    "ingestionStatus", doc.getStatus().name()
            ));

        } catch (IllegalArgumentException e) {
            log.warn("Document validation failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Validation Error",
                    "message", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("Document ingestion error: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "Ingestion Error",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * List all knowledge documents.
     */
    @GetMapping("/documents")
    public ResponseEntity<List<KnowledgeDocument>> listDocuments() {
        return ResponseEntity.ok(documentRepository.findAll());
    }

    /**
     * Get details of a single document.
     */
    @GetMapping("/documents/{id}")
    public ResponseEntity<?> getDocument(@PathVariable Long id) {
        return documentRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Delete document and all associated chunks.
     */
    @DeleteMapping("/documents/{id}")
    @PreAuthorize("hasAnyRole('TEACHER', 'HOD', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<?> deleteDocument(@PathVariable Long id) {
        Long userId = resolveCurrentUserId();
        User.Role role = resolveCurrentUserRole();

        try {
            knowledgeBaseService.deleteDocument(id, userId, role);
            return ResponseEntity.ok(Map.of("message", "Document and chunks deleted successfully."));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Re-index document: clear and recalculate embeddings.
     */
    @PostMapping("/documents/{id}/reindex")
    @PreAuthorize("hasAnyRole('TEACHER', 'HOD', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<?> reindexDocument(@PathVariable Long id) {
        Long userId = resolveCurrentUserId();
        User.Role role = resolveCurrentUserRole();

        try {
            KnowledgeDocument doc = knowledgeBaseService.reindexDocument(id, userId, role);
            return ResponseEntity.ok(Map.of(
                    "message", "Re-indexing initiated.",
                    "documentId", doc.getId(),
                    "status", doc.getStatus().name()
            ));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Search relevant knowledge chunks for testing retrieval.
     */
    @PostMapping("/search")
    public ResponseEntity<?> searchKnowledge(@RequestBody Map<String, Object> body) {
        String query = (String) body.getOrDefault("query", "");
        if (query.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Query parameter is required."));
        }

        User.Role role = resolveCurrentUserRole();
        Long classroomId = body.containsKey("classroomId") ? Long.valueOf(body.get("classroomId").toString()) : null;

        var rankedChunks = knowledgeBaseService.retrieveRelevantChunks(query, role, classroomId, 5);

        List<Map<String, Object>> results = rankedChunks.stream().map(rc -> Map.of(
                "documentTitle", (Object) rc.chunk().getDocument().getTitle(),
                "pageOrSection", (Object) rc.chunk().getPageOrSection(),
                "similarityScore", (Object) rc.score(),
                "content", (Object) rc.chunk().getContent()
        )).toList();

        return ResponseEntity.ok(Map.of(
                "query", query,
                "matchCount", results.size(),
                "results", results
        ));
    }

    private Long resolveCurrentUserId() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof User user) {
                return user.getId();
            }
        } catch (Exception ignored) {}
        return 27L;
    }

    private User.Role resolveCurrentUserRole() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof User user) {
                return user.getRole();
            }
        } catch (Exception ignored) {}
        return User.Role.TEACHER;
    }
}
