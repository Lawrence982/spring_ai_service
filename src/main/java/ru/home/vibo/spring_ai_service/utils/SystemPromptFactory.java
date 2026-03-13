package ru.home.vibo.spring_ai_service.utils;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class SystemPromptFactory {

    private static final String TOOLS_PROMPT_PREFIX = """
            *Доступные инструменты:*
            """;

    private static final String TOOLS_PROMPT_SUFFIX = """

            *Вызов инструмента:*
            Когда нужен инструмент — ответь согласно формату ниже (включая теги <tool_call> и </tool_call>):

            *Формат:*
            <tool_call>
            {"name": "tool_name", "parameters": {"param1": "value1", "param2": "value2"}}
            </tool_call>

            Теги обязательны. Ни слова до. Ни слова после. НЕ выдумывай результат — жди ответа системы.
            ЗАПРЕЩЕНО: говорить о намерении вызвать инструмент, предлагать его использование, объяснять решение,
            писать что-либо ДО тегов <tool_call>. Если инструмент нужен — первое слово ответа: <tool_call>.

            Когда придут данные инструмента — используй их для ответа на вопрос.
            """;

    public static String withTools(McpSchema.ListToolsResult toolsResult) {
        return withTools(toolsResult.tools());
    }

    public static String withTools(List<McpSchema.Tool> tools) {
        String toolsInfo = tools.stream()
                .map(SystemPromptFactory::formatTool)
                .collect(Collectors.joining("\n"));

        return TOOLS_PROMPT_PREFIX + toolsInfo + TOOLS_PROMPT_SUFFIX;
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
