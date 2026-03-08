package ru.home.vibo.spring_ai_service.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.home.vibo.spring_ai_service.model.ChatEntry;

public interface ChatEntryRepository extends JpaRepository<ChatEntry, Long> {
}
