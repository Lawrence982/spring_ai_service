package ru.home.vibo.spring_ai_service.advisors.rag;

import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static ru.home.vibo.spring_ai_service.advisors.AdvisorContextKeys.ENRICHED_QUESTION;

@Slf4j
@Builder
public class RagAdvisor implements BaseAdvisor {

    private static final int DEFAULT_TOP_K = 4;
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.62;
    private static final int OVERSAMPLING_FACTOR = 2;

    private static final PromptTemplate template = PromptTemplate.builder().template("""
                    CONTEXT: {context}
                    Question: {question}
                    """)
            .build();

    private VectorStore vectorStore;

    @Builder.Default
    private BM25RerankEngine rerankEngine = BM25RerankEngine.builder().build();

    @Builder.Default
    private SearchRequest searchRequest = SearchRequest.builder().topK(DEFAULT_TOP_K).similarityThreshold(DEFAULT_SIMILARITY_THRESHOLD).build();

    @Getter
    private final int order;

    public static RagAdvisorBuilder builder(VectorStore vectorStore) {
        return new RagAdvisorBuilder().vectorStore(vectorStore);
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        String originalUserQuestion = chatClientRequest.prompt().getUserMessage().getText();
        if (originalUserQuestion == null || originalUserQuestion.isBlank()) {
            return chatClientRequest;
        }
        String queryToRag = chatClientRequest.context().getOrDefault(ENRICHED_QUESTION, originalUserQuestion).toString();

        List<Document> documents = vectorStore.similaritySearch(SearchRequest
                .from(searchRequest)
                .query(queryToRag)
                .topK(searchRequest.getTopK() * OVERSAMPLING_FACTOR)
                .build()
        );

        if (documents.isEmpty()) {
            log.warn("RAG search returned no results. query='{}', originalQuestion='{}', topK={}, threshold={}",
                     queryToRag, originalUserQuestion,
                     searchRequest.getTopK() * OVERSAMPLING_FACTOR,
                     searchRequest.getSimilarityThreshold());
            return chatClientRequest;
        }

        int candidateCount = documents.size();
        documents = rerankEngine.rerank(documents, queryToRag, searchRequest.getTopK());
        log.debug("RAG: {} candidates from vector search, {} after BM25 reranking, query='{}'",
                  candidateCount, documents.size(), queryToRag);

        String llmContext = documents
                .stream()
                .map(Document::getText)
                .collect(Collectors.joining(System.lineSeparator()));

        String finalUserPrompt = template.render(
                Map.of("context", llmContext,
                        "question", originalUserQuestion));

        return chatClientRequest
                .mutate()
                .prompt(chatClientRequest.prompt().augmentUserMessage(finalUserPrompt))
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        return chatClientResponse;
    }
}
