package ru.home.vibo.spring_ai_service.utils;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class SystemPromptFactory {

    private static final String systemPromptTemplate = """
            Тебе доступны инструменты. Используй их только когда это действительно необходимо.

            *Когда использовать инструменты:*
            - Пользователь запрашивает актуальные данные (погода, новости, курсы валют).
            - Задача требует вычислений или обработки данных, недоступных тебе напрямую.
            - Ты не знаешь ответа и инструмент может его предоставить.

            *Когда НЕ использовать инструменты:*
            - Простые вопросы, на которые можно ответить из своих знаний.
            - Вопросы, не требующие внешних данных.

            *Доступные инструменты:*
            {{ Tools }}

            *Формат вызова инструмента:*
            <tool_call>
            {"name": "имя_инструмента", "parameters": {"параметр1": "значение1"}}
            </tool_call>

            Если в следующих сообщениях встретится блок <tool_response>...</tool_response> — это результат предыдущего вызова инструмента. Используй его содержимое для ответа на последний вопрос пользователя.
            """;

    public static String withTools(McpSchema.ListToolsResult toolsResult) {
        String toolsInfo = toolsResult.tools().stream()
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
