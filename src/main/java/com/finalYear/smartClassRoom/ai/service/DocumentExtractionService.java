package com.finalYear.smartClassRoom.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class DocumentExtractionService {

    private static final long MAX_FILE_SIZE = 15 * 1024 * 1024; // 15 MB
    private static final int CHUNK_SIZE_CHARS = 1000;
    private static final int CHUNK_OVERLAP_CHARS = 150;

    public record ExtractedChunk(
            int chunkIndex,
            String content,
            String pageOrSection,
            int estimatedTokens
    ) {}

    public record ExtractionResult(
            String fileHash,
            String detectedType,
            List<ExtractedChunk> chunks,
            int totalCharacters
    ) {}

    /**
     * Validates file safety, extension, magic header, and extracts text chunks.
     */
    public ExtractionResult validateAndExtract(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file cannot be empty.");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("File size exceeds 15MB maximum limit.");
        }

        byte[] bytes = file.getBytes();
        String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "document";
        String lowerName = originalFilename.toLowerCase();

        // 1. Extension & Executable/Archive Validation
        if (lowerName.endsWith(".exe") || lowerName.endsWith(".bat") || lowerName.endsWith(".sh")
                || lowerName.endsWith(".cmd") || lowerName.endsWith(".jar") || lowerName.endsWith(".bin")) {
            throw new IllegalArgumentException("Executable files are strictly rejected for security.");
        }
        if (lowerName.endsWith(".tar") || lowerName.endsWith(".gz") || lowerName.endsWith(".rar") || lowerName.endsWith(".7z")) {
            throw new IllegalArgumentException("Compressed archive files are not supported. Upload PDF, DOCX, or TXT directly.");
        }

        // Magic byte inspection
        if (bytes.length >= 2 && bytes[0] == 0x4D && bytes[1] == 0x5A) { // MZ header
            throw new IllegalArgumentException("Executable binary rejected.");
        }
        if (bytes.length >= 4 && bytes[0] == 0x7F && bytes[1] == 0x45 && bytes[2] == 0x4C && bytes[3] == 0x46) { // ELF header
            throw new IllegalArgumentException("Executable binary rejected.");
        }

        // 2. Compute SHA-256 Hash
        String fileHash = computeSha256(bytes);

        // 3. Extract text based on detected format
        List<ExtractedChunk> chunks;
        String detectedType;

        if (lowerName.endsWith(".pdf") || isPdfBytes(bytes)) {
            detectedType = "application/pdf";
            chunks = extractFromPdf(bytes);
        } else if (lowerName.endsWith(".docx")) {
            detectedType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            chunks = extractFromDocx(bytes);
        } else if (lowerName.endsWith(".txt") || lowerName.endsWith(".md") || isPlainText(bytes)) {
            detectedType = "text/plain";
            chunks = extractFromTxt(bytes);
        } else {
            throw new IllegalArgumentException("Unsupported file type. Supported formats: .pdf, .docx, .txt, .md");
        }

        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("No text content could be extracted from the document.");
        }

        int totalChars = chunks.stream().mapToInt(c -> c.content().length()).sum();
        log.info("Successfully extracted {} chunks ({} total chars) from {}",
                chunks.size(), totalChars, originalFilename);

        return new ExtractionResult(fileHash, detectedType, chunks, totalChars);
    }

    /**
     * Extracts text page-by-page from PDF using Apache PDFBox.
     * Detects scanned PDFs without selectable text and throws clear OCR notice.
     */
    private List<ExtractedChunk> extractFromPdf(byte[] bytes) throws IOException {
        List<ExtractedChunk> chunks = new ArrayList<>();

        try (PDDocument document = Loader.loadPDF(bytes)) {
            int numberOfPages = document.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();

            int totalTextLength = 0;
            int chunkIndex = 0;

            for (int page = 1; page <= numberOfPages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(document).trim();

                if (!pageText.isBlank()) {
                    totalTextLength += pageText.length();
                    List<String> textSlices = splitIntoChunks(pageText, CHUNK_SIZE_CHARS, CHUNK_OVERLAP_CHARS);
                    for (int i = 0; i < textSlices.size(); i++) {
                        String section = "Page " + page + (textSlices.size() > 1 ? " (part " + (i + 1) + ")" : "");
                        chunks.add(new ExtractedChunk(
                                chunkIndex++,
                                textSlices.get(i),
                                section,
                                estimateTokens(textSlices.get(i))
                        ));
                    }
                }
            }

            // Check if document has 0 text (scanned PDF image)
            if (totalTextLength == 0) {
                throw new IllegalArgumentException("OCR not available. Scanned PDFs with no selectable text cannot be indexed.");
            }
        }

        return chunks;
    }

    /**
     * Extracts text from DOCX using Apache POI.
     */
    private List<ExtractedChunk> extractFromDocx(byte[] bytes) throws IOException {
        List<ExtractedChunk> chunks = new ArrayList<>();
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
             XWPFDocument doc = new XWPFDocument(bais)) {

            StringBuilder sb = new StringBuilder();
            int sectionCounter = 1;
            int chunkIndex = 0;

            for (XWPFParagraph p : doc.getParagraphs()) {
                String text = p.getText().trim();
                if (!text.isBlank()) {
                    sb.append(text).append("\n\n");
                    if (sb.length() >= CHUNK_SIZE_CHARS) {
                        List<String> slices = splitIntoChunks(sb.toString(), CHUNK_SIZE_CHARS, CHUNK_OVERLAP_CHARS);
                        for (String slice : slices) {
                            chunks.add(new ExtractedChunk(
                                    chunkIndex++,
                                    slice,
                                    "Section " + sectionCounter,
                                    estimateTokens(slice)
                            ));
                        }
                        sb.setLength(0);
                        sectionCounter++;
                    }
                }
            }

            if (sb.length() > 0) {
                List<String> slices = splitIntoChunks(sb.toString(), CHUNK_SIZE_CHARS, CHUNK_OVERLAP_CHARS);
                for (String slice : slices) {
                    chunks.add(new ExtractedChunk(
                            chunkIndex++,
                            slice,
                            "Section " + sectionCounter,
                            estimateTokens(slice)
                    ));
                }
            }
        }
        return chunks;
    }

    /**
     * Extracts text from plain text UTF-8 files.
     */
    private List<ExtractedChunk> extractFromTxt(byte[] bytes) {
        String fullText = new String(bytes, StandardCharsets.UTF_8).trim();
        if (fullText.isBlank()) {
            throw new IllegalArgumentException("Text file is empty.");
        }

        List<ExtractedChunk> chunks = new ArrayList<>();
        List<String> slices = splitIntoChunks(fullText, CHUNK_SIZE_CHARS, CHUNK_OVERLAP_CHARS);
        for (int i = 0; i < slices.size(); i++) {
            chunks.add(new ExtractedChunk(
                    i,
                    slices.get(i),
                    "Part " + (i + 1),
                    estimateTokens(slices.get(i))
            ));
        }
        return chunks;
    }

    /**
     * Splits text into windows of chunkSize with overlap.
     */
    public List<String> splitIntoChunks(String text, int chunkSize, int overlap) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isBlank()) return result;

        int length = text.length();
        if (length <= chunkSize) {
            result.add(text.trim());
            return result;
        }

        int start = 0;
        while (start < length) {
            int end = Math.min(start + chunkSize, length);

            // Attempt to break at paragraph or sentence boundary
            if (end < length) {
                int lastNewline = text.lastIndexOf('\n', end);
                if (lastNewline > start + (chunkSize / 2)) {
                    end = lastNewline + 1;
                } else {
                    int lastPeriod = text.lastIndexOf(". ", end);
                    if (lastPeriod > start + (chunkSize / 2)) {
                        end = lastPeriod + 2;
                    }
                }
            }

            String chunk = text.substring(start, end).trim();
            if (!chunk.isBlank()) {
                result.add(chunk);
            }

            start = Math.max(start + 1, end - overlap);
        }

        return result;
    }

    public String computeSha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    private boolean isPdfBytes(byte[] bytes) {
        return bytes.length >= 4 && bytes[0] == 0x25 && bytes[1] == 0x50 && bytes[2] == 0x44 && bytes[3] == 0x46; // %PDF
    }

    private boolean isPlainText(byte[] bytes) {
        int checkLen = Math.min(bytes.length, 512);
        for (int i = 0; i < checkLen; i++) {
            byte b = bytes[i];
            if (b == 0 || (b < 7 && b != 0x09 && b != 0x0A && b != 0x0D)) {
                return false;
            }
        }
        return true;
    }

    private int estimateTokens(String text) {
        return Math.max(1, text.length() / 4);
    }
}
