package ru.home.vibo.spring_ai_service.service;

import io.modelcontextprotocol.spec.McpSchema;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import ru.home.vibo.spring_ai_service.model.Chat;
import ru.home.vibo.spring_ai_service.repository.ChatRepository;
import ru.home.vibo.spring_ai_service.utils.CallToolUtil;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static ru.home.vibo.spring_ai_service.model.Role.ASSISTANT;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    @Autowired
    private ChatRepository chatRepository;

    @Autowired
    private ChatClient chatClient;

    @Autowired
    @Qualifier("toolResolutionClient")
    private ChatClient toolResolutionClient;

    @Autowired
    private McpClientManager mcpClientManager;

    @Autowired
    private ChatEntryPersistence entryPersistence;


    public List<Chat> getAllChats() {
        return chatRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    public Chat getChat(Long chatId) {
        return chatRepository.findById(chatId)
                .orElseThrow(() -> new EntityNotFoundException("Chat not found with id: " + chatId));
    }

    public Chat createNewChat(String title) {
        Chat chat = Chat.builder().title(title).build();
        return chatRepository.save(chat);
    }

    public void deleteChat(Long chatId) {
        chatRepository.deleteById(chatId);
    }

    public void proceedInteraction(Long chatId, String prompt) {
        chatClient.prompt()
                .system(mcpClientManager.getSystemPromptForQuestion(prompt))
                .user(prompt)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .content();
    }

    public SseEmitter proceedInteractionWithStreaming(Long chatId, String userPrompt) {
        // Long.MAX_VALUE: отключаем таймаут эмиттера — LLM может думать дольше дефолтного
        // async-таймаута Tomcat (~30s). Без этого SseEmitter завершается по таймауту
        // пока Фаза 1 ещё выполняется, и processToken получает IllegalStateException.
        SseEmitter sseEmitter = new SseEmitter(Long.MAX_VALUE);

        // Весь блок обработки запускается в отдельном виртуальном потоке.
        // Это обязательно: SseEmitter должен быть возвращён контроллером ДО того,
        // как Tomcat переключится в async-режим. Любой вызов sseEmitter.send/complete
        // на том же потоке запроса приводит к RecycleRequiredException.
        Thread.ofVirtual().name("chat-stream-" + chatId).start(() -> {
            try {
                doStreamInteraction(chatId, userPrompt, sseEmitter);
            } catch (Exception e) {
                log.error("proceedInteractionWithStreaming: unexpected error for chatId={}", chatId, e);
                sseEmitter.completeWithError(e);
            }
        });

        return sseEmitter;
    }

    private void doStreamInteraction(Long chatId, String userPrompt, SseEmitter sseEmitter) {
        // Keepalive: отправляем SSE-комментарий каждые 15 сек, пока Phase 1 блокируется.
        // Без этого прокси/браузер закрывает idle-соединение (Broken pipe) раньше,
        // чем LLM успевает ответить. SSE-комментарии (: ping) игнорируются EventSource на клиенте.
        AtomicBoolean keepAlive = new AtomicBoolean(true);
        Thread pingThread = Thread.ofVirtual().name("sse-ping-" + chatId).start(() -> {
            while (keepAlive.get()) {
                try {
                    Thread.sleep(15_000);
                    if (keepAlive.get()) {
                        sseEmitter.send(SseEmitter.event().comment("ping"));
                    }
                } catch (IOException | InterruptedException e) {
                    break;
                }
            }
        });

        try {
            doPhases(chatId, userPrompt, sseEmitter);
        } finally {
            keepAlive.set(false);
            pingThread.interrupt();
        }
    }

    private void doPhases(Long chatId, String userPrompt, SseEmitter sseEmitter) {
        // Фаза 1: блокирующий вызов для детекции <tool_call>.
        // Нельзя стримить — паттерн <tool_call> виден только в полном тексте ответа.
        AssistantMessage assistantMessage = executePhase1(chatId, userPrompt, sseEmitter);
        if (assistantMessage == null) {
            return;
        }

        boolean toolRequired = CallToolUtil.isToolRequired(assistantMessage.getText());

        if (toolRequired) {
            executePhase2WithTool(chatId, userPrompt, assistantMessage, sseEmitter);
        } else {
            // Иначе MessageChatMemoryAdvisor из фазы 1 уже сохранил пару user/assistant в БД.
            executePhase2Direct(assistantMessage, sseEmitter);
        }
    }

    private AssistantMessage executePhase1(Long chatId, String userPrompt, SseEmitter sseEmitter) {
        // MessageChatMemoryAdvisor загружает историю диалога и сохраняет этот обмен в БД.
        // systemPrompt зашит в chatClient через mutate() в @PostConstruct.
        ChatResponse firstResponse = chatClient
                .prompt()
                .system(mcpClientManager.getSystemPromptForQuestion(userPrompt))
                .user(userPrompt)
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .chatResponse();

        if (firstResponse == null) {
            log.error("executePhase1: LLM returned null response for chatId={}", chatId);
            sendErrorAndComplete(sseEmitter, "Модель не вернула ответ. Попробуйте ещё раз.");
            return null;
        }

        AssistantMessage assistantMessage = firstResponse.getResult().getOutput();
        if (assistantMessage.getText() == null) {
            log.error("executePhase1: LLM returned null output for chatId={}", chatId);
            sendErrorAndComplete(sseEmitter, "Получен пустой ответ от модели.");
            return null;
        }

        return assistantMessage;
    }

    private void executePhase2WithTool(Long chatId, String userPrompt, AssistantMessage assistantMessage, SseEmitter sseEmitter) {
        if (!mcpClientManager.isAvailable()) {
            // MCP недоступен — модель сгенерировала <tool_call>, но сервер не подключён.
            // Сохраняем сообщение об ошибке как ответ ASSISTANT, чтобы не терять историю.
            log.warn("executePhase2WithTool: tool call requested but MCP client is unavailable");
            String msg = "Инструмент недоступен: MCP-сервер не подключён.";
            entryPersistence.addEntry(chatId, msg, ASSISTANT);
            sendErrorAndComplete(sseEmitter, msg);
            return;
        }

        Optional<McpSchema.CallToolResult> toolResultOpt = mcpClientManager.executeTool(assistantMessage.getText());
        if (toolResultOpt.isEmpty()) {
            log.warn("executePhase2WithTool: tool call failed (parse error or MCP execution error), aborting tool phase");
            sendErrorAndComplete(sseEmitter, "Ошибка при вызове инструмента. Попробуйте позже.");
            return;
        }

        McpSchema.CallToolResult toolCallResult = toolResultOpt.get();
        if (toolCallResult.content() == null || toolCallResult.content().isEmpty()) {
            log.error("executePhase2WithTool: MCP tool returned empty content for chatId={}", chatId);
            sendErrorAndComplete(sseEmitter, "Инструмент не вернул результат.");
            return;
        }

        String toolResponse = CallToolUtil.wrapResponse(toolCallResult.content().getFirst().toString());

        AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();

        sseEmitter.onTimeout(() -> {
            log.warn("executePhase2WithTool: SSE timeout for chatId={}", chatId);
            Disposable sub = subscriptionRef.get();
            if (sub != null && !sub.isDisposed()) sub.dispose();
        });
        sseEmitter.onError(ex -> {
            log.warn("executePhase2WithTool: SSE error for chatId={}", chatId, ex);
            Disposable sub = subscriptionRef.get();
            if (sub != null && !sub.isDisposed()) sub.dispose();
        });

        // toolResolutionClient — без RAG, Expansion и Memory advisors.
        // systemPrompt (персона) зашит в бин через defaultSystem.
        // Передаём полный контекст явно: user → <tool_call> → tool_response.
        // Финальный ответ стримим пользователю, сохраняем только его в БД.
        StringBuilder answer = new StringBuilder();
        Disposable subscription = toolResolutionClient.prompt()
                .messages(List.of(new UserMessage(userPrompt), assistantMessage, new UserMessage(toolResponse)))
                .stream()
                .chatResponse()
                .subscribe(
                        response -> processToken(response, sseEmitter, answer),
                        error -> {
                            Disposable sub = subscriptionRef.get();
                            if (sub != null) sub.dispose();
                            sseEmitter.completeWithError(error);
                        },
                        () -> {
                            // Фаза 1 уже сохранила userPrompt — сохраняем только финальный ответ ASSISTANT
                            entryPersistence.addEntry(chatId, answer.toString(), ASSISTANT);
                            sseEmitter.complete();
                        }
                );
        subscriptionRef.set(subscription);
    }

    private void executePhase2Direct(AssistantMessage assistantMessage, SseEmitter sseEmitter) {
        try {
            sseEmitter.send(assistantMessage);
            sseEmitter.complete();
        } catch (IOException e) {
            log.warn("executePhase2Direct: client disconnected", e);
            sseEmitter.completeWithError(e);
        } catch (IllegalStateException e) {
            // Эмиттер уже завершён (таймаут или разрыв соединения) — ничего не делаем
            log.warn("executePhase2Direct: emitter already completed, client likely disconnected");
        }
    }

    private void processToken(ChatResponse response, SseEmitter emitter, StringBuilder answer) {
        var token = response.getResult().getOutput();
        try {
            emitter.send(token);
            answer.append(token.getText());
        } catch (IOException e) {
            log.warn("processToken: client disconnected, completing emitter", e);
            emitter.completeWithError(e);
        } catch (IllegalStateException e) {
            // Эмиттер уже завершён (таймаут или разрыв соединения) — ничего не делаем
            log.warn("processToken: emitter already completed for chatId, client likely disconnected");
        }
    }

    private void sendErrorAndComplete(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event().data(message));
            emitter.complete();
        } catch (IOException e) {
            log.warn("sendErrorAndComplete: failed to send error message", e);
            emitter.completeWithError(e);
        }
    }
}
