--liquibase formatted sql
--changeset vibo:001-create-extension runInTransaction:false

CREATE EXTENSION IF NOT EXISTS vector;
