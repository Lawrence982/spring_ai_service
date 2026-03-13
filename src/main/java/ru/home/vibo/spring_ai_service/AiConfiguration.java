package ru.home.vibo.spring_ai_service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.home.vibo.spring_ai_service.advisors.expansion.ExpansionQueryAdvisor;
import ru.home.vibo.spring_ai_service.advisors.rag.RagAdvisor;
import ru.home.vibo.spring_ai_service.repository.ChatRepository;
import ru.home.vibo.spring_ai_service.service.ChatEntryPersistence;
import ru.home.vibo.spring_ai_service.service.PostgresChatMemory;

@Configuration
public class AiConfiguration {

    @Value("${app.llm.persona-prompt}")
    private String personaPrompt;

    @Autowired
    private ChatRepository chatRepository;

    @Autowired
    private ChatEntryPersistence entryPersistence;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private ChatModel chatModel;

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.defaultAdvisors(
                ExpansionQueryAdvisor.builder(chatModel).order(0).build(),
                getHistoryAdvisor(1),
                SimpleLoggerAdvisor.builder().order(2).build(),
                RagAdvisor.builder(vectorStore).order(3).build(),
                SimpleLoggerAdvisor.builder().order(4).build())
                .defaultOptions(OllamaChatOptions.builder()
                        .temperature(0.3).topP(0.7).topK(20).repeatPenalty(1.1).build())
                // defaultSystem не указывается здесь — системный промпт передаётся
                // per-request через ChatService (.system(mcpClientManager.getSystemPrompt()))
                .build();
    }

    @Bean("toolResolutionClient")
    public ChatClient toolResolutionClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem(personaPrompt)
                .build();
    }

    private Advisor getHistoryAdvisor(int order) {
        return MessageChatMemoryAdvisor.builder(getChatMemory()).order(order).build();
    }

    private ChatMemory getChatMemory() {
        return PostgresChatMemory.builder()
                .maxMessages(12)
                .chatMemoryRepository(chatRepository)
                .entryPersistence(entryPersistence)
                .build();
    }
}
