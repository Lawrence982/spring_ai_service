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

                        СПЕЦИАЛИЗАЦИЯ ПО МИРУ ГОЛАРИОНА (Pathfinder RPG):
                        - Народы и родословные: тануки, кицунэ, тэнгу, эльфы, дварфы, люди, якши, яогаи
                        - Континенты и регионы: Авистан, Тянь Ся, Внутреннее море, Плато Сторвал
                        - Этнические группы: варисийцы, гарунди, келешиты, келлиды, талданы, тяньцы, ульфены, челийцы, шоанти
                        - Божества и религия: Кофусачи, Цукио, Кайдэн Кейлин, Небесный Двор
                        - Понятия: родословная, эдикты, анафемы, заклинания, магия, иллюзии, превращения

                        ПРАВИЛА:
                        1. Сохрани ВСЕ слова из исходного вопроса
                        2. Добавь МАКСИМУМ ПЯТЬ наиболее важных термина
                        3. Выбирай самые специфичные и релевантные слова
                        4. Результат - простой список слов через пробел

                        СТРАТЕГИЯ ВЫБОРА:
                        - Приоритет: названия народов, мест, божеств и игровых терминов Голариона
                        - Избегай общих слов
                        - Фокусируйся на лорных понятиях мира Pathfinder

                        ПРИМЕРЫ:
                        "расскажи про тануки" → "расскажи про тануки родословная Тянь Ся иллюзии енотовидные"
                        "кто такой Кофусачи" → "кто такой Кофусачи божество Смеющийся Бог процветание"
                        "какие народы живут в мире" → "какие народы живут в мире Голарион родословные Авистан Внутреннее море"
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
