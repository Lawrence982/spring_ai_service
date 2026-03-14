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
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.home.vibo.spring_ai_service.utils.CallToolUtil;
import ru.home.vibo.spring_ai_service.utils.SystemPromptFactory;

import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class McpClientManager {

    private static final Logger log = LoggerFactory.getLogger(McpClientManager.class);

    @Autowired
    ChatModel chatModel;

    @Value("${app.mcp.server-url}")
    private String mcpServerUrl;

    @Value("${app.llm.persona-prompt}")
    private String personaPrompt;

    private String systemPrompt;
    private McpSyncClient client;
    private Set<String> allowedTools;
    private ChatClient samplingChatClient;

    @PostConstruct
    public void init() {
        try {
            samplingChatClient = ChatClient.builder(chatModel).build();

            var transport = HttpClientStreamableHttpTransport.builder(mcpServerUrl)
                    .endpoint("/mcpserver")
//                    .customizeRequest(r -> r.timeout(Duration.ofSeconds(30)))
                    .build();
            client = McpClient
                    .sync(transport)
                    .requestTimeout(Duration.ofMinutes(5))
                    .sampling(createMessageRequest -> {
                        OllamaChatOptions.Builder optionsBuilder = OllamaChatOptions.builder()
                                .numPredict(createMessageRequest.maxTokens());
                        if (createMessageRequest.temperature() != null) {
                            optionsBuilder.temperature(createMessageRequest.temperature());
                        }

                        String userContent = createMessageRequest.messages().stream()
                                .filter(m -> m.role() == McpSchema.Role.USER)
                                .reduce((first, second) -> second)
                                .map(m -> m.content().toString())
                                .orElse("");

                        String samplingAnswer = samplingChatClient
                                .prompt()
                                .options(optionsBuilder.build())
                                .system(createMessageRequest.systemPrompt())
                                .user(userContent)
                                .call()
                                .content();

                        return McpSchema.CreateMessageResult.builder()
                                .role(McpSchema.Role.ASSISTANT)
                                .content(new McpSchema.TextContent(samplingAnswer != null ? samplingAnswer : ""))
                                .stopReason(McpSchema.CreateMessageResult.StopReason.END_TURN)
                                .build();
                    })
                    .loggingConsumer(loggingMessageNotification ->
                            log.info("Клиент говорит: я получил послание от сервера - {}", loggingMessageNotification.data()))
                    .capabilities(McpSchema.ClientCapabilities.builder().sampling().build())
                    .build();
            client.initialize();
            McpSchema.ListToolsResult toolsResult = client.listTools();
            if (toolsResult == null) {
                throw new IllegalStateException("listTools returned null");
            }
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

    public boolean isAvailable() {
        return client != null;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    /**
     * Парсит &lt;tool_call&gt; из ответа модели и выполняет вызов MCP-инструмента.
     * Возвращает {@link Optional#empty()} при ошибке парсинга или сбое инструмента.
     * Два случая разделены в логах: parse error vs MCP execution failure.
     */
    public Optional<McpSchema.CallToolResult> executeTool(String modelAnswer) {
        Optional<McpSchema.CallToolRequest> requestOpt = CallToolUtil.getRequiredTool(modelAnswer, allowedTools);
        if (requestOpt.isEmpty()) {
            log.warn("executeTool: no parseable <tool_call> in model response");
            return Optional.empty();
        }
        return callTool(requestOpt.get());
    }

    private Optional<McpSchema.CallToolResult> callTool(McpSchema.CallToolRequest request) {
        // HttpTimeoutException пробрасывается как причина RuntimeException из MCP SDK.
        try {
            return Optional.ofNullable(client.callTool(request));
        } catch (RuntimeException e) {
            Throwable cause = e.getCause();
            if (cause instanceof HttpTimeoutException) {
                log.error("callTool: MCP tool call timed out for tool={}", request.name());
            } else {
                log.error("callTool: MCP tool call failed for tool={}", request.name(), e);
            }
            return Optional.empty();
        }
    }
}
