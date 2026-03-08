package ru.home.vibo.spring_ai_service.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Chat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @OrderBy("createdAt ASC")
    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true, mappedBy = "chat")
    private List<ChatEntry> history = new ArrayList<>();

    public void addChatEntry(ChatEntry entry) {
        entry.setChat(this);
        history.add(entry);
    }
}
