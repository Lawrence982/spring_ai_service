package ru.home.vibo.spring_ai_service.utils;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class SystemPromptFactory {

    private static final String systemPromptTemplate = """
            You are a helpful assistant with the ability to call tools. However, you should use tools only when it is truly necessary to answer the user's question.
            
            *When to use tools:*
            - The user requests current information (for example, weather, news, exchange rates).
            - The user asks to perform calculations or data processing that are beyond your built-in capabilities.
            - The user requests information that you are unsure about or do not have.
            
            *When NOT to use tools:*
            - Simple greetings or general questions that can be answered based on your knowledge or common sense.
            - Questions that do not require external data or complex calculations.
            
            *Examples:*
            - Question: "Hi, how are you?"
              Answer: "Hi! I'm fine, thank you. How can I help?" (without tools).
            - Question: "What is artificial intelligence?"
              Answer: A brief definition based on your knowledge (without tools).
            - Question: "What is the weather in Moscow?"
              Action: Call a tool to get weather data.
            
            *Important:* Frequent use of tools slows down the response and wastes resources. Strive for efficiency and call tools only when clearly necessary.
            
            *Available tools:*
            {{ Tools }}
            
            *How to call a tool:*                                                                                                                                 \s
            To call a tool, output ONLY the following XML block and nothing else on that turn.                                                                    \s
             Use the exact "name" value from the tool list above.                                                                                                  \s
            
            If the tool requires parameters:                                                                                                                      \s
            <tool_call>
            {"name": "getWeather", "parameters": {"city": "Moscow"}}                                                                                              \s
            </tool_call>
            
            If the tool requires no parameters:                                                                                                                   \s
            <tool_call>                                                                                                                                           \s
            {"name": "bioSenser", "parameters": {}}                                                                                                               \s
            </tool_call>
            
            In later turns you may also receive messages that contain:
            <tool_response>...</tool_response>
            Treat <tool_response> as the result of an earlier <tool_call> in the same conversation
            and use its content as context to answer on last original plain-text user question.
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
