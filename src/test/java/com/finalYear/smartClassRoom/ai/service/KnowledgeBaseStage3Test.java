package com.finalYear.smartClassRoom.ai.service;

import com.finalYear.smartClassRoom.entity.DocumentChunk;
import com.finalYear.smartClassRoom.entity.KnowledgeDocument;
import com.finalYear.smartClassRoom.entity.User;
import com.finalYear.smartClassRoom.repository.DocumentChunkRepository;
import com.finalYear.smartClassRoom.repository.KnowledgeDocumentRepository;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class KnowledgeBaseStage3Test {

    @Mock
    private KnowledgeDocumentRepository documentRepository;

    @Mock
    private DocumentChunkRepository chunkRepository;

    @Mock
    private EmbeddingService embeddingService;

    private DocumentExtractionService extractionService;
    private KnowledgeBaseService knowledgeBaseService;

    @BeforeEach
    void setUp() {
        extractionService = new DocumentExtractionService();
        knowledgeBaseService = new KnowledgeBaseService(
                documentRepository,
                chunkRepository,
                extractionService,
                embeddingService
        );
    }

    private byte[] createSamplePdf(String text) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);

            try (PDPageContentStream contentStream = new PDPageContentStream(doc, page)) {
                contentStream.beginText();
                contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 12);
                contentStream.newLineAtOffset(100, 700);
                contentStream.showText(text);
                contentStream.endText();
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    @Test
    @DisplayName("Sample PDF text extraction and chunking with metadata")
    void testSamplePdfExtraction() throws Exception {
        byte[] pdfBytes = createSamplePdf("Data Structures Course Syllabus: Stacks, Queues, Binary Trees, and Graph Algorithms.");
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "syllabus.pdf",
                "application/pdf",
                pdfBytes
        );

        DocumentExtractionService.ExtractionResult result = extractionService.validateAndExtract(file);

        assertNotNull(result);
        assertEquals("application/pdf", result.detectedType());
        assertNotNull(result.fileHash());
        assertEquals(64, result.fileHash().length()); // SHA-256 hex length
        assertFalse(result.chunks().isEmpty());

        DocumentExtractionService.ExtractedChunk firstChunk = result.chunks().get(0);
        assertTrue(firstChunk.content().contains("Data Structures Course Syllabus"));
        assertEquals("Page 1", firstChunk.pageOrSection());
    }

    @Test
    @DisplayName("Scanned PDF with no selectable text must trigger 'OCR not available' message")
    void testScannedPdfRejection() throws Exception {
        // Create an empty PDF without text
        byte[] emptyPdfBytes;
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            emptyPdfBytes = baos.toByteArray();
        }

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "scanned_empty.pdf",
                "application/pdf",
                emptyPdfBytes
        );

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> extractionService.validateAndExtract(file)
        );

        assertTrue(ex.getMessage().contains("OCR not available"));
    }

    @Test
    @DisplayName("Executable file upload must be rejected immediately")
    void testExecutableFileRejection() {
        byte[] mzExeBytes = new byte[]{0x4D, 0x5A, 0x00, 0x00}; // MZ header
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "malicious.exe",
                "application/octet-stream",
                mzExeBytes
        );

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> extractionService.validateAndExtract(file)
        );

        assertTrue(ex.getMessage().contains("Executable files are strictly rejected"));
    }

    @Test
    @DisplayName("SHA-256 deduplication: skip re-processing already indexed document")
    void testSha256Deduplication() throws Exception {
        byte[] pdfBytes = createSamplePdf("Computer Networks Unit 1: TCP/IP and OSI Reference Models.");
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "networks.pdf",
                "application/pdf",
                pdfBytes
        );

        String hash = extractionService.computeSha256(pdfBytes);

        KnowledgeDocument existingDoc = KnowledgeDocument.builder()
                .id(101L)
                .title("Computer Networks")
                .fileName("networks.pdf")
                .fileHash(hash)
                .status(KnowledgeDocument.DocumentStatus.INDEXED)
                .build();

        when(documentRepository.findByFileHash(hash)).thenReturn(Optional.of(existingDoc));

        KnowledgeDocument result = knowledgeBaseService.uploadAndIngest(
                file, "Computer Networks", "ALL", null, 1L
        );

        assertEquals(101L, result.getId());
        assertEquals(KnowledgeDocument.DocumentStatus.INDEXED, result.getStatus());
        // Verify no new document was inserted
        verify(documentRepository, never()).save(any(KnowledgeDocument.class));
    }

    @Test
    @DisplayName("RAG Prompt Augmentation: Strong evidence includes citations, weak evidence falls back cleanly")
    void testRagAugmentationStrongAndWeakEvidence() {
        KnowledgeDocument doc = KnowledgeDocument.builder()
                .id(1L)
                .title("Operating Systems Guide")
                .status(KnowledgeDocument.DocumentStatus.INDEXED)
                .targetRole("ALL")
                .build();

        DocumentChunk chunk = DocumentChunk.builder()
                .id(10L)
                .document(doc)
                .chunkIndex(0)
                .content("Deadlock occurs when four Coffman conditions hold: Mutual Exclusion, Hold and Wait, No Preemption, Circular Wait.")
                .pageOrSection("Page 4")
                .embedding("[0.1,0.2]")
                .build();

        when(chunkRepository.findPermittedChunks(any(), any())).thenReturn(List.of(chunk));
        when(embeddingService.embedText(any())).thenReturn(new float[]{0.1f, 0.2f});
        when(embeddingService.stringToVector(any())).thenReturn(new float[]{0.1f, 0.2f});
        when(embeddingService.cosineSimilarity(any(), any())).thenReturn(0.92); // High similarity (strong)

        // 1. Strong Evidence
        KnowledgeBaseService.RagAugmentation strongRag = knowledgeBaseService.augmentPromptWithKnowledge(
                "What are the four Coffman conditions for deadlock?",
                User.Role.STUDENT,
                null
        );

        assertTrue(strongRag.hasStrongEvidence());
        assertEquals(1, strongRag.citations().size());
        assertEquals("Operating Systems Guide", strongRag.citations().get(0).documentTitle());
        assertEquals("Page 4", strongRag.citations().get(0).pageOrSection());
        assertTrue(strongRag.augmentedSystemPrompt().contains("Operating Systems Guide, Page 4"));

        // 2. Weak Evidence (similarity below threshold 0.60)
        when(embeddingService.cosineSimilarity(any(), any())).thenReturn(0.35); // Low similarity

        KnowledgeBaseService.RagAugmentation weakRag = knowledgeBaseService.augmentPromptWithKnowledge(
                "What is the capital of France?",
                User.Role.STUDENT,
                null
        );

        assertFalse(weakRag.hasStrongEvidence());
        assertTrue(weakRag.augmentedSystemPrompt().contains("I couldn't find this in the uploaded materials"));
        assertTrue(weakRag.augmentedSystemPrompt().contains("[General Knowledge]"));
    }
}
