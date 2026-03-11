package ru.home.vibo.spring_ai_service.utils;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class SystemPromptFactory {

    private static final String systemPromptTemplate = """
            *Доступные инструменты:*
            {{ Tools }}

            *Вызов инструмента:*
            Когда нужен инструмент — ответь РОВНО ЭТИМ текстом (включая теги <tool_call> и </tool_call>):

            {{ Example }}

            Теги обязательны. Ни слова до. Ни слова после. НЕ выдумывай результат — жди ответа системы.

            Когда придут данные инструмента — используй их для ответа на вопрос.
            """;

    public static String withTools(McpSchema.ListToolsResult toolsResult) {
        return withTools(toolsResult.tools());
    }

    public static String withTools(List<McpSchema.Tool> tools) {
        String toolsInfo = tools.stream()
                .map(SystemPromptFactory::formatTool)
                .collect(Collectors.joining("\n"));

        String example = tools.isEmpty() ? DEFAULT_EXAMPLE : generateExample(tools.get(0));

        return systemPromptTemplate
                .replace("{{ Tools }}", toolsInfo)
                .replace("{{ Example }}", example);
    }

    private static final String DEFAULT_EXAMPLE =
            "<tool_call>\n{\"name\": \"toolName\", \"parameters\": {}}\n</tool_call>";

    private static String generateExample(McpSchema.Tool tool) {
        McpSchema.JsonSchema schema = tool.inputSchema();
        if (schema == null || schema.properties() == null || schema.properties().isEmpty()) {
            return "<tool_call>\n{\"name\": \"" + tool.name() + "\", \"parameters\": {}}\n</tool_call>";
        }
        StringBuilder params = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Object> entry : schema.properties().entrySet()) {
            if (!first) params.append(", ");
            String sampleValue = resolveSampleValue(entry.getValue());
            params.append("\"").append(entry.getKey()).append("\": ").append(sampleValue);
            first = false;
        }
        return "<tool_call>\n{\"name\": \"" + tool.name() + "\", \"parameters\": {" + params + "}}\n</tool_call>";
    }

    @SuppressWarnings("unchecked")
    private static String resolveSampleValue(Object propSchema) {
        if (!(propSchema instanceof Map<?, ?> map)) return "\"value\"";
        String type = (String) ((Map<String, Object>) map).getOrDefault("type", "string");
        return switch (type) {
            case "integer", "number" -> "7";
            case "boolean" -> "true";
            default -> "\"example\"";
        };
    }

    private static String formatTool(McpSchema.Tool tool) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n- name: ").append(tool.name());

        appendIfPresent(sb, "\n  title: ", tool.title());
        appendIfPresent(sb, "\n  description: ", tool.description());
        appendIfPresent(sb, "\n  inputSchema: ", tool.inputSchema());
        appendIfNotEmpty(sb, "\n  outputSchema: ", tool.outputSchema());
        appendIfPresent(sb, "\n  annotations: ", tool.annotations());
        appendIfNotEmpty(sb, "\n  meta: ", tool.meta());

        sb.append("\n");
        return sb.toString();
    }

    private static void appendIfPresent(StringBuilder sb, String prefix, Object value) {
        Optional.ofNullable(value)
                .map(Object::toString)
                .filter(s -> !s.isBlank())
                .ifPresent(s -> sb.append(prefix).append(s));
    }

    private static void appendIfNotEmpty(StringBuilder sb, String prefix, Map<?, ?> map) {
        Optional.ofNullable(map)
                .filter(m -> !m.isEmpty())
                .ifPresent(m -> sb.append(prefix).append(m));
    }


}
