package ru.home.vibo.spring_ai_service.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.Arrays;

@RequiredArgsConstructor
@Getter
public enum Role {
    USER("user") {
        @Override
        Message getMessage(String message) {
            return new UserMessage(message);
        }
    }, ASSISTANT("assistant") {
        @Override
        Message getMessage(String message) {
            return new AssistantMessage(message);
        }
    }, SYSTEM("system") {
        @Override
        Message getMessage(String message) {
            return new SystemMessage(message);
        }
    };

    private final String role;

    public static Role getRole(String roleName) {
        return Arrays.stream(Role.values()).filter(role -> role.role.equals(roleName)).findFirst().orElseThrow();
    }

    abstract Message getMessage(String prompt);
}
