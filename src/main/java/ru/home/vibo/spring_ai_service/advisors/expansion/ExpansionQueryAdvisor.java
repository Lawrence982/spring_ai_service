package ru.home.vibo.spring_ai_service.advisors.expansion;

import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.ollama.api.OllamaChatOptions;

import java.util.Map;

import static ru.home.vibo.spring_ai_service.advisors.AdvisorContextKeys.*;

@Slf4j
@Builder
public class ExpansionQueryAdvisor implements BaseAdvisor {

    private static final PromptTemplate template = PromptTemplate.builder()
            .template("""
                        Instruction: Расширь поисковый запрос, добавив наиболее релевантные термины.

                        ПРАВИЛА:
                        1. Сохрани ВСЕ слова из исходного вопроса
                        2. Добавь МАКСИМУМ ПЯТЬ наиболее важных термина
                        3. Выбирай самые специфичные и релевантные слова по теме запроса
                        4. Результат - простой список слов через пробел, без пояснений

                        СТРАТЕГИЯ ВЫБОРА:
                        - Если запрос о мире Голариона/Pathfinder RPG: добавляй названия народов, мест, божеств, игровых терминов
                        - Для любого другого запроса: добавляй профессиональные или тематические термины по сути вопроса
                        - Избегай общих слов, предлогов, союзов

                        ПРИМЕРЫ:
                        "расскажи про тануки" → "расскажи про тануки родословная Тянь Ся иллюзии енотовидные"
                        "кто такой Кофусачи" → "кто такой Кофусачи божество Смеющийся Бог процветание"
                        "какой мой пульс за 6 дней" → "какой мой пульс за 6 дней сердцебиение частота удары минуту кардио"
                        "сколько стоит биткоин" → "сколько стоит биткоин криптовалюта курс цена рынок"
                        "что за класс сыщик" → "что за класс сыщик расследование улики интеллект разведчик"

                        Question: {question}
                        Expanded query:
                    """).build();


    private ChatClient chatClient;

    public static ExpansionQueryAdvisorBuilder builder(ChatModel chatModel) {
        return new ExpansionQueryAdvisorBuilder().chatClient(ChatClient.builder(chatModel)
                .defaultOptions(OllamaChatOptions.builder().temperature(0.0).topK(1).topP(0.1).repeatPenalty(1.0).build())
                .build());
    }

    @Getter
    private final int order;

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {

        String userQuestion = chatClientRequest.prompt().getUserMessage().getText();
        if (userQuestion.isBlank()) {
            return chatClientRequest;
        }

        String enrichedQuestion;
        try {
            enrichedQuestion = chatClient
                    .prompt()
                    .user(template.render(Map.of("question", userQuestion)))
                    .call()
                    .content();
        } catch (Exception e) {
            log.warn("Query expansion failed, using original question: {}", e.getMessage());
            enrichedQuestion = null;
        }

        if (enrichedQuestion == null || enrichedQuestion.isBlank()) {
            enrichedQuestion = userQuestion;
        }

        double ratio = enrichedQuestion.length() / (double) userQuestion.length();

        return chatClientRequest.mutate()
                .context(ORIGINAL_QUESTION, userQuestion)
                .context(ENRICHED_QUESTION, enrichedQuestion)
                .context(EXPANSION_RATIO, ratio)
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        return chatClientResponse;
    }

}
