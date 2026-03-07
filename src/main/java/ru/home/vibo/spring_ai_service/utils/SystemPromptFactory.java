package ru.home.vibo.spring_ai_service.utils;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class SystemPromptFactory {

    private static final String systemPromptTemplate = """
            ИНСТРУМЕНТЫ ДОСТУПНЫ ТОЛЬКО ЕСЛИ ТЫ НЕ СМОГ ОТВЕТИТЬ САМОСТОЯТЕЛЬНО.

            Правило одного действия: в одном ответе — ЛИБО текст, ЛИБО вызов инструмента. Никогда оба вместе.

            Вызывай инструмент ТОЛЬКО при одновременном выполнении ВСЕХ условий:
            - Блок ---НАЧАЛО КОНТЕКСТА--- пуст или не содержит ответа на вопрос
            - Ты не знаешь ответа из своих знаний
            - Пользователь явно запрашивает данные, которые инструмент может предоставить

            *Доступные инструменты:*
            {{ Tools }}

            *Формат вызова инструмента — единственное содержимое ответа:*
            <tool_call>
            {"name": "имя_инструмента", "parameters": {"параметр1": "значение1"}}
            </tool_call>

            Если в сообщении есть блок <tool_response>...</tool_response> — это результат вызова инструмента. Используй его для ответа на вопрос пользователя.
            """;

    public static String withTools(McpSchema.ListToolsResult toolsResult) {
        return withTools(toolsResult.tools());
    }

    public static String withTools(List<McpSchema.Tool> tools) {
        String toolsInfo = tools.stream()
                .map(SystemPromptFactory::formatTool)
                .collect(Collectors.joining("\n"));

        return systemPromptTemplate.replace("{{ Tools }}", toolsInfo);
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
