--liquibase formatted sql
--changeset vibo:005-add-from-tool-to-chat-entry

ALTER TABLE public.chat_entry
    ADD COLUMN from_tool BOOLEAN NOT NULL DEFAULT FALSE;
