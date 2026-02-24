--liquibase formatted sql
--changeset vibo:004-create-indexes

CREATE INDEX IF NOT EXISTS idx_loaded_documents_filename
    ON loaded_document (file_name);

CREATE INDEX IF NOT EXISTS vector_store_hnsw_index
    ON vector_store USING hnsw (embedding vector_cosine_ops);
