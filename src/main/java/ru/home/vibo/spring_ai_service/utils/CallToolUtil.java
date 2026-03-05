package ru.home.vibo.spring_ai_service.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CallToolUtil {

    private static final Logger log = LoggerFactory.getLogger(CallToolUtil.class);

    private final static ObjectMapper mapper = new ObjectMapper();

    private static final Pattern TOOL_CALL_PATTERN =
            Pattern.compile("<tool_call>\\s*(\\{.*?})\\s*</tool_call>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    public static boolean isToolRequired(String modelAnswer) {
        return TOOL_CALL_PATTERN.matcher(modelAnswer).find();
    }

    public static String wrapResponse(String toolResult) {
        return String.format("<tool_response>%n%s%n</tool_response>", toolResult);
    }

    public static Optional<McpSchema.CallToolRequest> getRequiredTool(String modelAnswer, Set<String> allowedTools) {
        Matcher matcher = TOOL_CALL_PATTERN.matcher(modelAnswer);
        if (!matcher.find()) {
            log.warn("getRequiredTool: <tool_call> pattern not found in model answer");
            return Optional.empty();
        }

        String toolCallRequestJson = matcher.group(1).trim();
        JsonNode tool;
        try {
            tool = mapper.readTree(toolCallRequestJson);
        } catch (JsonProcessingException e) {
            log.warn("getRequiredTool: failed to parse tool call JSON: {}", toolCallRequestJson, e);
            return Optional.empty();
        }

        String toolName = tool.path("name").asText();
        if (toolName.isBlank()) {
            log.warn("getRequiredTool: tool name is blank in parsed JSON");
            return Optional.empty();
        }

        if (!allowedTools.contains(toolName)) {
            log.warn("getRequiredTool: tool '{}' is not in allowed whitelist {}", toolName, allowedTools);
            return Optional.empty();
        }

        JsonNode parameters = tool.path("parameters");
        return Optional.of(
                McpSchema.CallToolRequest.builder()
                        .name(toolName)
                        .arguments(mapper.convertValue(parameters, Map.class))
                        .build()
        );
    }

}
