package ru.home.vibo.spring_ai_service.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.home.vibo.spring_ai_service.model.Chat;

import java.util.Optional;

public interface ChatRepository extends JpaRepository<Chat, Long> {

    @Query("SELECT c FROM Chat c LEFT JOIN FETCH c.history WHERE c.id = :id")
    Optional<Chat> findByIdWithHistory(Long id);

}
