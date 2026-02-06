package ru.home.vibo.spring_ai_service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class SpringAiServiceApplication {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(SpringAiServiceApplication.class, args);
        ChatClient chatClient = context.getBean(ChatClient.class);
//        $ curl 'http://localhost:11431/api/generate' -H 'Content-Type: application/json' -d '{
//        "model": "gemma3:4b-it-q4_K_M",
//                "prompt": "Оригинальный текст песни Bohemian Rhapsody",
//                "stream": false, отвечать частями, а не ждать полный ответ
//                "option": {
//            "num_predict": 100, сколько токенов я хочу получить обратно
//                    "temperature": 0.7, влияет на алгоритм по которому выбирается токен. При высокой температуре мы
//        будем получать креативный бред, а при низкой ответы будут скучными, однообразными
//                    "top_k": 40, из скольки токенов мы готовы выбирать максимум
//                    "top_p": 0.9, сколько процентов из top_k надо рассматривать. Отсеиваем варианты с маленькой вероятностью
//                    "repeat_penalty": 1.1 наказание на повторы, чтобы токены не повторялись
//        }
    }

}
