package ru.home.vibo.spring_ai_service.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.Builder;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import ru.home.vibo.spring_ai_service.model.Chat;
import ru.home.vibo.spring_ai_service.model.ChatEntry;
import ru.home.vibo.spring_ai_service.repository.ChatRepository;
import ru.home.vibo.spring_ai_service.utils.CallToolUtil;

import java.util.List;

@Builder
public class PostgresChatMemory implements ChatMemory {

    private static final List<Message> SEED_MESSAGES = List.of(
            new UserMessage("Расскажи о себе"),
            new AssistantMessage("Я Чио — девушка-тануки, искательница приключений и сыщик из мира Голариона! " +
                    "Я путешествую, собирая истории в свой блокнот. " +
                    "Если в нём есть ответ на твой вопрос — обязательно расскажу!")
    );

    private ChatRepository chatMemoryRepository;

    private int maxMessages;

    @Override
    public void add(String conversationId, List<Message> messages) {
        Chat chat = chatMemoryRepository.findByIdWithHistory(Long.valueOf(conversationId))
                .orElseThrow(() -> new EntityNotFoundException("Chat not found with id: " + conversationId));
        for (Message message : messages) {
            if (message instanceof AssistantMessage && CallToolUtil.isToolRequired(message.getText())) {
                continue;
            }
            chat.addChatEntry(ChatEntry.toChatEntry(message));
        }
        chatMemoryRepository.save(chat);
    }

    @Override
    public List<Message> get(String conversationId) {
        Chat chat = chatMemoryRepository.findByIdWithHistory(Long.valueOf(conversationId))
                .orElseThrow(() -> new EntityNotFoundException("Chat not found with id: " + conversationId));
        if (chat.getHistory().isEmpty()) {
            return SEED_MESSAGES;
        }
        long messagesToSkip = Math.max(0, chat.getHistory().size() - maxMessages);
        return chat.getHistory()
                .stream()
                .skip(messagesToSkip)
                .map(ChatEntry::toMessage)
                .toList();
    }

    @Override
    public void clear(String conversationId) {
        // not implemented
    }
}
