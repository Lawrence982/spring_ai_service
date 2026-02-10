package ru.home.vibo.spring_ai_service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import ru.home.vibo.spring_ai_service.repository.ChatRepository;
import ru.home.vibo.spring_ai_service.service.PostgresChatMemory;

@SpringBootApplication
public class SpringAiServiceApplication {

    private static final PromptTemplate MY_PROMPT_TEMPLATE = new PromptTemplate(
            "{query}\n\n" +
                    "Контекст:\n" +
                    "---------------------\n" +
                    "{question_answer_context}\n" +
                    "---------------------\n\n" +
                    "Отвечай только на основе контекста выше. Если информации нет в контексте, сообщи, что не можешь ответить."
    );

    @Autowired
    private ChatRepository chatRepository;

    @Autowired
    private VectorStore vectorStore;

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.defaultAdvisors(
                getHistoryAdvisor(),
                SimpleLoggerAdvisor.builder().build(),
                getRagAdvisor())
                .build();
    }

    private Advisor getRagAdvisor() {
        return QuestionAnswerAdvisor.builder(vectorStore).promptTemplate(MY_PROMPT_TEMPLATE).searchRequest(
                SearchRequest.builder().topK(4).build() // default (just example)
        ).build();
    }

    private Advisor getHistoryAdvisor() {
        return MessageChatMemoryAdvisor.builder(getChatMemory()).build();
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
