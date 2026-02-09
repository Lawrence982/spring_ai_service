package ru.home.vibo.spring_ai_service.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.home.vibo.spring_ai_service.model.LoadedDocument;

public interface DocumentRepository extends JpaRepository<LoadedDocument, Long> {

    boolean existsByFileNameAndContentHash(String fileName, String contentHash);

}
