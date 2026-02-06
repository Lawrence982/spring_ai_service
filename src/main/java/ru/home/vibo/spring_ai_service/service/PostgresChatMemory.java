package ru.home.vibo.spring_ai_service.service;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.home.vibo.spring_ai_service.model.Chat;
import ru.home.vibo.spring_ai_service.model.ChatEntry;
import ru.home.vibo.spring_ai_service.repository.ChatRepository;

import java.util.List;

@Component
public class PostgresChatMemory implements ChatMemory {

    @Autowired
    private ChatRepository chatRepository;

    @Override
    @Transactional
    public void add(String conversationId, List<Message> messages) {
        for (Message message : messages) {
            Chat chat = chatRepository.findById(Long.valueOf(conversationId)).orElseThrow();
            chat.addChatEntry(ChatEntry.toChatEntry(message));
        }
    }

    @Override
    public List<Message> get(String conversationId) {
        Chat chat = chatRepository.findById(Long.valueOf(conversationId)).orElseThrow();
        return chat.getHistory()
                .stream()
                .map(ChatEntry::toMessage)
                .toList();
    }

    @Override
    public void clear(String conversationId) {
        // not implemented
    }
}
