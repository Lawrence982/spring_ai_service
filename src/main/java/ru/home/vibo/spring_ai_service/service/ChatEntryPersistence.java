package ru.home.vibo.spring_ai_service.service;

import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.home.vibo.spring_ai_service.model.Chat;
import ru.home.vibo.spring_ai_service.model.ChatEntry;
import ru.home.vibo.spring_ai_service.model.Role;
import ru.home.vibo.spring_ai_service.repository.ChatRepository;

@Service
public class ChatEntryPersistence {

    @Autowired
    private ChatRepository chatRepository;

    @Transactional
    public void addEntry(Long chatId, String content, Role role) {
        Chat chat = chatRepository.findById(chatId)
                .orElseThrow(() -> new EntityNotFoundException("Chat not found: " + chatId));
        chat.addChatEntry(ChatEntry.builder().content(content).role(role).build());
    }
}
