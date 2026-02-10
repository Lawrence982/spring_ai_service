package ru.home.vibo.spring_ai_service.service;

import lombok.SneakyThrows;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import ru.home.vibo.spring_ai_service.model.Chat;
import ru.home.vibo.spring_ai_service.model.ChatEntry;
import ru.home.vibo.spring_ai_service.model.Role;
import ru.home.vibo.spring_ai_service.repository.ChatRepository;

import jakarta.persistence.EntityNotFoundException;

import java.util.List;

import static ru.home.vibo.spring_ai_service.model.Role.ASSISTANT;
import static ru.home.vibo.spring_ai_service.model.Role.USER;

@Service
public class ChatService {

    @Autowired
    private ChatRepository chatRepository;

    @Autowired
    private ChatClient chatClient;

    @Autowired
    private ChatService proxyService;

    public List<Chat> getAllChats() {
        return chatRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    public Chat getChat(Long chatId) {
        return chatRepository.findById(chatId)
                .orElseThrow(() -> new EntityNotFoundException("Chat not found with id: " + chatId));
    }

    public Chat createNewChat(String title) {
        Chat chat = Chat.builder().title(title).build();
        return chatRepository.save(chat);
    }

    public void deleteChat(Long chatId) {
        chatRepository.deleteById(chatId);
    }

    @Transactional
    public void proceedInteraction(Long chatId, String prompt) {
        proxyService.addChatEntry(chatId, prompt, USER);
        String answer = chatClient.prompt().user(prompt).call().content();
        proxyService.addChatEntry(chatId, answer, ASSISTANT);
    }

    @Transactional
    public void addChatEntry(Long chatId, String prompt, Role role) {
        Chat chat = chatRepository.findById(chatId)
                .orElseThrow(() -> new EntityNotFoundException("Chat not found with id: " + chatId));
        chat.addChatEntry(ChatEntry.builder().content(prompt).role(role).build());
    }

    public SseEmitter proceedInteractionWithStreaming(Long chatId, String userPrompt) {
        StringBuilder answer = new StringBuilder();
        SseEmitter sseEmitter = new SseEmitter(0L);

        chatClient
                .prompt(userPrompt)
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, chatId))
                .stream()
                .chatResponse()
                .subscribe(response -> processToken(response, sseEmitter, answer),
                        sseEmitter::completeWithError,
                        sseEmitter::complete);
        return sseEmitter;
    }

    @SneakyThrows
    private void processToken(ChatResponse response, SseEmitter emitter, StringBuilder answer) {
        var token = response.getResult().getOutput();
        emitter.send(token);
        answer.append(token.getText());
    }
}
