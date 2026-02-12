package ru.home.vibo.spring_ai_service.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import ru.home.vibo.spring_ai_service.model.LoadedDocument;
import ru.home.vibo.spring_ai_service.repository.DocumentRepository;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

@Slf4j
@Service
public class DocumentLoaderService implements CommandLineRunner {

    private static final String DOCUMENT_TYPE_TXT = "txt";

    @Value("${app.knowledgebase.path}")
    private String knowledgeBasePath;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private ResourcePatternResolver resolver;

    @Autowired
    private VectorStore vectorStore;

    public void loadDocuments() {
        log.info("Starting document loading from {}", knowledgeBasePath);

        try {
            Resource[] resources = resolver.getResources(knowledgeBasePath);

            if (resources.length == 0) {
                log.warn("No documents found in knowledgebase directory");
                return;
            }

            log.info("Found {} documents to process", resources.length);

            int successCount = 0;
            int errorCount = 0;
            int skippedCount = 0;

            for (Resource resource : resources) {
                try {
                    String contentHash = calcContentHash(resource);
                    String fileName = resource.getFilename();

                    if (documentRepository.existsByFileNameAndContentHash(fileName, contentHash)) {
                        log.debug("Document already loaded, skipping: {}", fileName);
                        skippedCount++;
                        continue;
                    }

                    List<Document> documents = new TextReader(resource).get();
                    TokenTextSplitter textSplitter = TokenTextSplitter.builder()
                            .withChunkSize(200)
                            .build();
                    List<Document> chunks = textSplitter.apply(documents);

                    vectorStore.accept(chunks);

                    LoadedDocument loadedDocument = LoadedDocument.builder()
                            .documentType(DOCUMENT_TYPE_TXT)
                            .chunkCount(chunks.size())
                            .fileName(fileName)
                            .contentHash(contentHash)
                            .build();

                    documentRepository.save(loadedDocument);

                    log.info("Successfully loaded document: {} ({} chunks)", fileName, chunks.size());
                    successCount++;

                } catch (Exception e) {
                    log.error("Failed to load document: {}", resource.getFilename(), e);
                    errorCount++;
                }
            }

            log.info("Document loading completed. Success: {}, Errors: {}, Skipped: {}",
                    successCount, errorCount, skippedCount);

        } catch (Exception e) {
            log.error("Failed to scan knowledgebase directory", e);
            throw new RuntimeException("Document loading failed", e);
        }
    }

    private String calcContentHash(Resource resource) throws IOException {
        try (InputStream is = resource.getInputStream()) {
            return DigestUtils.md5DigestAsHex(is);
        }
    }

    @Override
    public void run(String... args) throws Exception {
        loadDocuments();
    }
}
