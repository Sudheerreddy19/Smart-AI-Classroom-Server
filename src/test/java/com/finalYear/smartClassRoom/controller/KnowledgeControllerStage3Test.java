package com.finalYear.smartClassRoom.controller;

import com.finalYear.smartClassRoom.ai.service.KnowledgeBaseService;
import com.finalYear.smartClassRoom.config.JwtService;
import com.finalYear.smartClassRoom.entity.DocumentChunk;
import com.finalYear.smartClassRoom.entity.KnowledgeDocument;
import com.finalYear.smartClassRoom.repository.KnowledgeDocumentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(KnowledgeController.class)
@AutoConfigureMockMvc(addFilters = false)
public class KnowledgeControllerStage3Test {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private KnowledgeBaseService knowledgeBaseService;

    @MockBean
    private KnowledgeDocumentRepository documentRepository;

    @MockBean
    private JwtService jwtService;

    @Test
    @DisplayName("POST /api/knowledge/upload should ingest document and return metadata")
    void testUploadDocument() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.pdf",
                "application/pdf",
                "%PDF-1.4 test".getBytes()
        );

        KnowledgeDocument doc = KnowledgeDocument.builder()
                .id(42L)
                .title("Advanced AI Syllabus")
                .fileName("test.pdf")
                .fileHash("hash123")
                .status(KnowledgeDocument.DocumentStatus.INDEXED)
                .chunkCount(3)
                .build();

        when(knowledgeBaseService.uploadAndIngest(any(), any(), any(), any(), any())).thenReturn(doc);

        mockMvc.perform(multipart("/api/knowledge/upload")
                        .file(file)
                        .param("title", "Advanced AI Syllabus")
                        .param("targetRole", "ALL"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.documentId").value(42))
                .andExpect(jsonPath("$.title").value("Advanced AI Syllabus"))
                .andExpect(jsonPath("$.chunkCount").value(3))
                .andExpect(jsonPath("$.ingestionStatus").value("INDEXED"));
    }

    @Test
    @DisplayName("GET /api/knowledge/documents should return all documents")
    void testListDocuments() throws Exception {
        KnowledgeDocument doc = KnowledgeDocument.builder()
                .id(1L)
                .title("Syllabus")
                .fileName("syllabus.pdf")
                .status(KnowledgeDocument.DocumentStatus.INDEXED)
                .build();

        when(documentRepository.findAll()).thenReturn(List.of(doc));

        mockMvc.perform(get("/api/knowledge/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Syllabus"));
    }

    @Test
    @DisplayName("POST /api/knowledge/search should return retrieved chunks with similarity scores")
    void testSearchKnowledge() throws Exception {
        KnowledgeDocument doc = KnowledgeDocument.builder().title("AI Guide").build();
        DocumentChunk chunk = DocumentChunk.builder()
                .document(doc)
                .content("Machine learning algorithms optimize cost functions.")
                .pageOrSection("Page 2")
                .build();

        when(knowledgeBaseService.retrieveRelevantChunks(any(), any(), any(), anyInt()))
                .thenReturn(List.of(new KnowledgeBaseService.RankedChunk(chunk, 0.88)));

        mockMvc.perform(post("/api/knowledge/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"machine learning optimization\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchCount").value(1))
                .andExpect(jsonPath("$.results[0].documentTitle").value("AI Guide"))
                .andExpect(jsonPath("$.results[0].pageOrSection").value("Page 2"))
                .andExpect(jsonPath("$.results[0].similarityScore").value(0.88));
    }
}
