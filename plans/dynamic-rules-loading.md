# Dynamic Rules Loading

Правила в памяти — primary source conventions наряду с CLAUDE.md. Проблема: агент не вызывает `memory_search` сам, несмотря на инструкции в промпте. Нужна комбинация усиленного промпта и автоматической подгрузки.

## Два параллельных подхода

Не взаимоисключающие — автоподгрузка как подстраховка, агент о ней не знает.

### A) Промпт + напоминания

Агент сам ищет правила, мы помогаем промптом и напоминаниями.

### B) Автоподгрузка (невидимая)

Хуки ищут и инжектят правила через system-reminder, без участия агента.

---

## 1. CLAUDE.md — усилить промпт про правила

Текущий промпт (`plugins/ai-memory/CLAUDE.md`) говорит "call memory_search before decisions" — слишком абстрактно, агент игнорирует.

**Изменить на:** принцип — правила в памяти это primary source conventions, наряду с CLAUDE.md. Искать когда предстоит выбор, который конвенции могут регулировать. Без конкретных примеров запросов (чтобы не ограничивать), без чеклиста триггеров. Доки `memory_search` достаточно подробные.

## 2. Напоминания в хуках

- **PreToolUse (EnterPlanMode)**: напоминание проверить правила перед планированием
- **PreToolUse (Edit/Write/Bash)**: периодическое напоминание если агент ещё не вызывал `memory_search` в этой сессии (трекать `memory-search-called-{session_id}` через state DB)

Дёшево для контекста (~30 tokens), не блокирует, лучше чем ничего.

## 3. Автоподгрузка (если OpenAI key есть)

### UserPromptSubmit — async prefetch

1. Хук получает `prompt` + `transcript_path`
2. Извлекает контекст: промпт + последний thinking/message из transcript JSONL
3. Вызывает **gpt-4o-mini**: "extract topic tags from available list + generate English search query for finding applicable rules"
   - Input: промпт пользователя + контекст из transcript + список доступных тегов (кэш `explore_tags`)
   - Output: `{"tags": [...], "query": "..."}`
4. Ищет правила: `search_facts(any_tags=tags, tags=["rule"])` + `search_facts(query=query, tags=["rule"])`
5. Combine + dedup + сохраняет в state DB: `rules-prefetch-{session_id}`
6. **Async** — не блокирует агента

### PreToolUse (Edit/Write/Bash) — sync inject

1. Читает `rules-prefetch-{session_id}` из state DB
2. Если есть правила и они ещё не показаны (dedup по `rules-shown-{session_id}`)
3. Выводит в stdout → system-reminder
4. Обновляет dedup set

### PostToolUse (ExitPlanMode) — inject после плана

1. Matcher: `ExitPlanMode`
2. Читает план из transcript (последний assistant message/thinking)
3. Вызывает gpt-4o-mini с планом → tags + query
4. Ищет правила, inject (sync — план готов, execution ещё нет)
5. Dedup с уже показанными

## 4. Инфраструктура

- **OpenAI helper** в `lib/llm.py`: gpt-4o-mini вызов (chat completions), graceful fallback если нет ключа
- **Dedup state**: `rules-shown-{session_id}` — set путей уже показанных правил
- **Tags cache**: `tags-cache-{session_id}` — кэш explore_tags (обновлять раз в сессию, при SessionStart)
- **Graceful fallback**: нет OPENAI_API_KEY → только напоминания (п.2), автоподгрузка отключена
- **Формат inject**: краткий, одна строка на правило (как SessionStart): `- [rule] <first line> [[ref]]`

## 5. Контекст для gpt-4o-mini

В зависимости от хука, доступен разный контекст:

| Источник | UserPromptSubmit | PostToolUse ExitPlanMode |
|----------|-----------------|--------------------------|
| Промпт пользователя | ✅ | — |
| Thinking из transcript | ✅ (если есть) | ✅ (план) |
| Последнее сообщение агента | — | ✅ |
| Compact/summary сессии | ✅ (если есть в state) | ✅ |
| Доступные теги | ✅ (кэш) | ✅ (кэш) |

## 6. Замер и тюнинг

- Benchmark gpt-4o-mini call из хука (ожидание: 300-500ms)
- Подбор threshold для semantic search score (отсекать нерелевантные)
- Мониторинг: сколько правил реально инжектится за сессию

## На будущее (не сейчас)

- **Agent hook** (type: "agent", haiku) как fallback без OpenAI key — субагент с доступом к Read/Grep ищет правила напрямую в файлах
- **Prompt hook** (type: "prompt", haiku) для семантических решений
- **PostPlan контекст**: если Claude Code добавит хук на завершение планирования — использовать
