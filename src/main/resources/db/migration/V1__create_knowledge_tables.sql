-- Flyway Migration V1: Create Knowledge Base & Document Chunk Tables for RAG
-- pgvector extension is enabled on Neon PostgreSQL

CREATE EXTENSION IF NOT EXISTS vector;

-- Knowledge Documents Table
CREATE TABLE IF NOT EXISTS knowledge_documents (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    file_size BIGINT NOT NULL,
    file_hash VARCHAR(64) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    failure_reason TEXT,
    target_role VARCHAR(30) NOT NULL DEFAULT 'ALL',
    classroom_id BIGINT,
    uploaded_by BIGINT NOT NULL,
    chunk_count INT DEFAULT 0,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_kdoc_hash ON knowledge_documents(file_hash);
CREATE INDEX IF NOT EXISTS idx_kdoc_role ON knowledge_documents(target_role);
CREATE INDEX IF NOT EXISTS idx_kdoc_classroom ON knowledge_documents(classroom_id);

-- Document Chunks Table with Vector Embeddings (768 dimensions for Gemini embedding model)
CREATE TABLE IF NOT EXISTS document_chunks (
    id BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES knowledge_documents(id) ON DELETE CASCADE,
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,
    page_or_section VARCHAR(100),
    token_count INT,
    embedding vector(768),
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_dchunk_doc ON document_chunks(document_id);

/*
ROLLBACK NOTES:
To roll back this migration:
DROP TABLE IF EXISTS document_chunks CASCADE;
DROP TABLE IF EXISTS knowledge_documents CASCADE;
*/
