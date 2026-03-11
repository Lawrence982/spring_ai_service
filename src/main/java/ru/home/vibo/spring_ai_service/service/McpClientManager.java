package ru.home.vibo.spring_ai_service.service;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.home.vibo.spring_ai_service.utils.CallToolUtil;
import ru.home.vibo.spring_ai_service.utils.SystemPromptFactory;

import java.net.http.HttpTimeoutException;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class McpClientManager {

    private static final Logger log = LoggerFactory.getLogger(McpClientManager.class);

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
