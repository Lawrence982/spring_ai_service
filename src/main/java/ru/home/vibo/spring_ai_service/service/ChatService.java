package ru.home.vibo.spring_ai_service.service;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import ru.home.vibo.spring_ai_service.model.Chat;
import ru.home.vibo.spring_ai_service.model.ChatEntry;
import ru.home.vibo.spring_ai_service.model.Role;
import ru.home.vibo.spring_ai_service.repository.ChatRepository;

import jakarta.persistence.EntityNotFoundException;
import ru.home.vibo.spring_ai_service.utils.CallToolUtil;
import ru.home.vibo.spring_ai_service.utils.SystemPromptFactory;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import reactor.core.Disposable;

import static ru.home.vibo.spring_ai_service.model.Role.ASSISTANT;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    @Autowired
    private ChatService proxyService;

    @Autowired
    private ChatRepository chatRepository;

    @Autowired
    private ChatClient chatClient;

    @Autowired
    @Qualifier("toolResolutionClient")
    private ChatClient toolResolutionClient;

    @Value("${app.mcp.server-url}")
    private String mcpServerUrl;

    @Value("${app.llm.persona-prompt}")
    private String personaPrompt;

    private String systemPrompt;
    private McpSyncClient client;
    private Set<String> allowedTools;

    @PostConstruct
    public void init() {
        try {
            var transport = HttpClientStreamableHttpTransport.builder(mcpServerUrl)
                    .endpoint("/mcpserver")
//                    .customizeRequest(r -> r.timeout(Duration.ofSeconds(30)))
                    .build();
            client = McpClient.sync(transport).build();
            client.initialize();
            McpSchema.ListToolsResult toolsResult = client.listTools();
            allowedTools = toolsResult.tools().stream()
                    .map(McpSchema.Tool::name)
                    .collect(Collectors.toUnmodifiableSet());
            systemPrompt = personaPrompt + "\n\n" + SystemPromptFactory.withTools(toolsResult);
            log.info("init: MCP initialized, {} tools available: {}", allowedTools.size(), allowedTools);
        } catch (Exception e) {
            log.warn("init: MCP unavailable at {}, running without tools. Cause: {}", mcpServerUrl, e.getMessage());
            client = null;
            allowedTools = Set.of();
            systemPrompt = personaPrompt;
        }
        // Переопределяем chatClient с полным системным промптом (персона + MCP-инструменты если доступны)
        this.chatClient = this.chatClient.mutate().defaultSystem(systemPrompt).build();
    }

    @PreDestroy
    public void cleanup() {
        if (client == null) {
            return;
        }
        log.info("cleanup: closing McpSyncClient");
        boolean graceful = client.closeGracefully();
        if (!graceful) {
            log.warn("cleanup: closeGracefully returned false, falling back to close()");
            client.close();
        }
    }

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
                .user(prompt)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .content();
    }

    @Transactional
    public void addChatEntry(Long chatId, String prompt, Role role) {
        Chat chat = chatRepository.findById(chatId)
                .orElseThrow(() -> new EntityNotFoundException("Chat not found with id: " + chatId));
        chat.addChatEntry(ChatEntry.builder().content(prompt).role(role).build());
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
        // MessageChatMemoryAdvisor загружает историю диалога и сохраняет этот обмен в БД.
        // systemPrompt зашит в chatClient через mutate() в @PostConstruct.
        ChatResponse firstResponse = chatClient
                .prompt()
                .user(userPrompt)
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .chatResponse();

        if (firstResponse == null) {
            log.error("doStreamInteraction: LLM returned null response for chatId={}", chatId);
            sendErrorAndComplete(sseEmitter, "Модель не вернула ответ. Попробуйте ещё раз.");
            return;
        }

        AssistantMessage assistantMessage = firstResponse.getResult().getOutput();
        if (assistantMessage.getText() == null) {
            log.error("doStreamInteraction: LLM returned null output for chatId={}", chatId);
            sendErrorAndComplete(sseEmitter, "Получен пустой ответ от модели.");
            return;
        }

        if (CallToolUtil.isToolRequired(assistantMessage.getText())) {
            // Фаза 2a: тул нужен.

            if (client == null) {
                // MCP недоступен — модель сгенерировала <tool_call>, но сервер не подключён.
                // Сохраняем сообщение об ошибке как ответ ASSISTANT, чтобы не терять историю.
                log.warn("doStreamInteraction: tool call requested but MCP client is unavailable");
                String msg = "Инструмент недоступен: MCP-сервер не подключён.";
                proxyService.addChatEntry(chatId, msg, ASSISTANT);
                sendErrorAndComplete(sseEmitter, msg);
                return;
            }

            Optional<McpSchema.CallToolRequest> callToolRequestOpt =
                    CallToolUtil.getRequiredTool(assistantMessage.getText(), allowedTools);

            if (callToolRequestOpt.isEmpty()) {
                log.warn("doStreamInteraction: invalid or unparseable tool call, aborting tool phase");
                sendErrorAndComplete(sseEmitter, "Не удалось обработать запрос к инструменту. Попробуйте переформулировать вопрос.");
                return;
            }

            McpSchema.CallToolResult toolCallResult = callTool(callToolRequestOpt.get());
            if (toolCallResult == null || toolCallResult.content() == null || toolCallResult.content().isEmpty()) {
                log.error("doStreamInteraction: MCP tool returned empty content for chatId={}", chatId);
                sendErrorAndComplete(sseEmitter, "Инструмент не вернул результат.");
                return;
            }
            String toolResponse = CallToolUtil.wrapResponse(toolCallResult.content().getFirst().toString());

            AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();

            sseEmitter.onTimeout(() -> {
                log.warn("doStreamInteraction: SSE timeout for chatId={}", chatId);
                Disposable sub = subscriptionRef.get();
                if (sub != null && !sub.isDisposed()) sub.dispose();
            });
            sseEmitter.onError(ex -> {
                log.warn("doStreamInteraction: SSE error for chatId={}", chatId, ex);
                Disposable sub = subscriptionRef.get();
                if (sub != null && !sub.isDisposed()) sub.dispose();
            });

            // toolResolutionClient — без RAG, Expansion и Memory advisors.
            // systemPrompt (персона) зашит в бин через defaultSystem.
            // Передаём полный контекст явно: user → <tool_call> → tool_response.
            // Финальный ответ стримим пользователю, сохраняем только его в БД.
            StringBuffer answer = new StringBuffer();
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
                                proxyService.addChatEntry(chatId, answer.toString(), ASSISTANT);
                                sseEmitter.complete();
                            }
                    );
            subscriptionRef.set(subscription);
        } else {
            // Фаза 2b: тул не нужен.
            // MessageChatMemoryAdvisor из фазы 1 уже сохранил пару user/assistant в БД.
            processToken(firstResponse, sseEmitter, new StringBuffer());
            sseEmitter.complete();
        }
    }

    private void processToken(ChatResponse response, SseEmitter emitter, StringBuffer answer) {
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

    private McpSchema.CallToolResult callTool(McpSchema.CallToolRequest request) {
        // Таймаут 30s настроен на уровне транспорта через customizeRequest().
        // HttpTimeoutException пробрасывается как причина RuntimeException из MCP SDK.
        try {
            return client.callTool(request);
        } catch (RuntimeException e) {
            Throwable cause = e.getCause();
            if (cause instanceof HttpTimeoutException) {
                log.error("callTool: MCP tool call timed out after 30s for tool={}", request.name());
            } else {
                log.error("callTool: MCP tool call failed for tool={}", request.name(), e);
            }
            return null;
        }
    }
}
