# Spring AI Service

Spring Boot веб-приложение для AI-чата с использованием локальной LLM через Ollama.

Подробная документация по архитектуре — в [CLAUDE.md](CLAUDE.md).

## Ollama API — пример запроса

Прямой вызов Ollama API (порт 11431 в Docker Compose):

```bash
curl 'http://localhost:11431/api/generate' \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "qwen2.5:14b-instruct-q4_K_M",
    "prompt": "Оригинальный текст песни Bohemian Rhapsody",
    "stream": false,
    "options": {
      "num_predict": 100,
      "temperature": 0.7,
      "top_k": 40,
      "top_p": 0.9,
      "repeat_penalty": 1.1
    }
  }'
```

### Параметры

| Параметр | Описание |
|----------|----------|
| `stream` | `false` — ждать полный ответ, `true` — получать частями |
| `num_predict` | Максимальное количество токенов в ответе |
| `temperature` | Креативность ответов. Высокая (>1.0) — креативный бред, низкая (<0.3) — скучно, однообразно |
| `top_k` | Максимальное количество токенов-кандидатов при каждом шаге генерации |
| `top_p` | Какую долю вероятностной массы из `top_k` рассматривать (отсекает маловероятные варианты) |
| `repeat_penalty` | Штраф за повторяющиеся токены (>1.0 уменьшает повторы) |
