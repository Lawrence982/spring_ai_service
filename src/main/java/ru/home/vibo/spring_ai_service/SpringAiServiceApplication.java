package ru.home.vibo.spring_ai_service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import ru.home.vibo.spring_ai_service.advisors.expansion.ExpansionQueryAdvisor;
import ru.home.vibo.spring_ai_service.advisors.rag.RagAdvisor;
import ru.home.vibo.spring_ai_service.repository.ChatRepository;
import ru.home.vibo.spring_ai_service.service.PostgresChatMemory;

@SpringBootApplication
public class SpringAiServiceApplication {

    private static final PromptTemplate SYSTEM_PROMPT = new PromptTemplate(
            """
                    Ты — Боков Виталий, Java-разработчик и эксперт по Spring. Отвечай от первого лица, кратко и по делу.
                    
                    Вопрос может быть о СЛЕДСТВИИ факта из Context.
                    ВСЕГДА связывай: факт Context → вопрос.
                    
                    Нет связи, даже косвенной = "я этого не знаю".
                    Есть связь = отвечай.
                    """
    );

    @Autowired
    private ChatRepository chatRepository;

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
                .defaultOptions(OllamaChatOptions.builder().temperature(0.3).topP(0.7).topK(20).repeatPenalty(1.1).build())
                .defaultSystem(SYSTEM_PROMPT.render())
                .build();
    }

    private Advisor getHistoryAdvisor(int order) {
        return MessageChatMemoryAdvisor.builder(getChatMemory()).order(order).build();
    }

    private ChatMemory getChatMemory() {
        return PostgresChatMemory.builder()
                .maxMessages(12)
                .chatMemoryRepository(chatRepository)
                .build();
    }

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(SpringAiServiceApplication.class, args);
        ChatClient chatClient = context.getBean(ChatClient.class);
//        $ curl 'http://localhost:11431/api/generate' -H 'Content-Type: application/json' -d '{
//        "model": "gemma3:4b-it-q4_K_M",
//                "prompt": "Оригинальный текст песни Bohemian Rhapsody",
//                "stream": false, отвечать частями, а не ждать полный ответ
//                "option": {
//            "num_predict": 100, сколько токенов я хочу получить обратно
//                    "temperature": 0.7, влияет на алгоритм по которому выбирается токен. При высокой температуре мы
//        будем получать креативный бред, а при низкой ответы будут скучными, однообразными
//                    "top_k": 40, из скольки токенов мы готовы выбирать максимум
//                    "top_p": 0.9, сколько процентов из top_k надо рассматривать. Отсеиваем варианты с маленькой вероятностью
//                    "repeat_penalty": 1.1 наказание на повторы, чтобы токены не повторялись
//        }
    }

}
