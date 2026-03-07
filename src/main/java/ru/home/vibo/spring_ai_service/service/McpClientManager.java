package ru.home.vibo.spring_ai_service.service;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.home.vibo.spring_ai_service.utils.CallToolUtil;
import ru.home.vibo.spring_ai_service.utils.SystemPromptFactory;

import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class McpClientManager {

    private static final Logger log = LoggerFactory.getLogger(McpClientManager.class);

    private static final double TOOL_SIMILARITY_THRESHOLD = 0.365;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Value("${app.mcp.server-url}")
    private String mcpServerUrl;

    @Value("${app.llm.persona-prompt}")
    private String personaPrompt;

    private String systemPrompt;
    private McpSyncClient client;
    private Set<String> allowedTools;
    private List<McpSchema.Tool> toolsList = List.of();
    private List<float[]> toolEmbeddings = List.of();

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
            toolsList = toolsResult.tools();
            toolEmbeddings = toolsList.stream()
                    .map(t -> embeddingModel.embed(buildToolText(t)))
                    .toList();
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
     * Возвращает системный промпт с инструментами, релевантными вопросу.
     * Если ни один инструмент не достигает порога сходства — возвращает только персону без блока инструментов.
     */
    public String getSystemPromptForQuestion(String question) {
        if (toolsList.isEmpty()) {
            return personaPrompt;
        }
        float[] questionEmbedding = embeddingModel.embed(question);
        List<McpSchema.Tool> relevantTools = new ArrayList<>();
        for (int i = 0; i < toolsList.size(); i++) {
            double similarity = cosineSimilarity(questionEmbedding, toolEmbeddings.get(i));
            log.debug("getSystemPromptForQuestion: tool={} similarity={}", toolsList.get(i).name(), String.format("%.3f", similarity));
            if (similarity >= TOOL_SIMILARITY_THRESHOLD) {
                relevantTools.add(toolsList.get(i));
            }
        }
        if (relevantTools.isEmpty()) {
            log.debug("getSystemPromptForQuestion: no relevant tools, omitting tool block");
            return personaPrompt;
        }
        log.debug("getSystemPromptForQuestion: {} relevant tool(s): {}",
                relevantTools.size(), relevantTools.stream().map(McpSchema.Tool::name).toList());
        return personaPrompt + "\n\n" + SystemPromptFactory.withTools(relevantTools);
    }

    private static String buildToolText(McpSchema.Tool tool) {
        StringBuilder sb = new StringBuilder();
        if (tool.title() != null) sb.append(tool.title()).append(" ");
        if (tool.description() != null) sb.append(tool.description());
        return sb.toString().strip();
    }

    private static double cosineSimilarity(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return (normA == 0 || normB == 0) ? 0 : dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * Парсит &lt;tool_call&gt; из ответа модели и выполняет вызов MCP-инструмента.
     * Возвращает {@link Optional#empty()} при ошибке парсинга или сбое инструмента.
     */
    public Optional<McpSchema.CallToolResult> executeTool(String modelAnswer) {
        Optional<McpSchema.CallToolRequest> requestOpt = CallToolUtil.getRequiredTool(modelAnswer, allowedTools);
        return requestOpt.map(this::callTool);
    }

    private McpSchema.CallToolResult callTool(McpSchema.CallToolRequest request) {
        // HttpTimeoutException пробрасывается как причина RuntimeException из MCP SDK.
        try {
            return client.callTool(request);
        } catch (RuntimeException e) {
            Throwable cause = e.getCause();
            if (cause instanceof HttpTimeoutException) {
                log.error("callTool: MCP tool call timed out for tool={}", request.name());
            } else {
                log.error("callTool: MCP tool call failed for tool={}", request.name(), e);
            }
            return null;
        }
    }
}
