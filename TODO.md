# TODO — ai-memory Redesign

## Концепция

Система памяти для AI-агентов с двумя задачами:
1. **Кэш сессий** — основная польза. Автоматически сохранять контекст между `/clear` и рестартами.
2. **Ленивая загрузка правил** — загружать релевантные правила/предпочтения по мере необходимости, а не всегда (в отличие от CLAUDE.md).

**Принцип:** локальная система без обязательного сервера. OpenAI/векторизация — опциональны.

---

## Блок 1 — Локальное хранилище + Python MCP сервер

**Цель:** плагин работает без Clojure-сервера. Python MCP stdio сервер, файловая система как хранилище.

### Решения (закрытые)

- Clojure-бэкенд убирается полностью (не fallback)
- MCP транспорт: stdio (`python3 mcp/server.py`)
- Хранилище: `~/.claude/ai-memory/` (переопределяется через `AI_MEMORY_DIR`)
- Синхронизация: iCloud (target), git (опционально)

### Структура хранилища

```
~/.claude/ai-memory/
  universal/
    fix-bugs-test-first.md    # tags: [universal, testing, workflow]
    write-docstrings.md       # tags: [universal, conventions]
  languages/
    clojure/
      use-kaocha.md           # tags: [clojure, testing]
      prefer-transducers.md   # tags: [clojure, performance]
    python/
      ...
  projects/
    ai-memory/
      rules/
        use-integrant.md      # tags: [project/ai-memory, architecture]
      sessions/
        2026-03-11 Local storage arch.md
  sessions/                   # сессии без привязки к проекту
    2026-03-11 General brainstorm.md
```

Теги из пути (автоматически):
- `universal/` → `[universal]`
- `languages/clojure/` → `[clojure]`
- `projects/ai-memory/rules/` → `[project/ai-memory, rule]`
- `projects/ai-memory/sessions/` → `[project/ai-memory, session]`
- `sessions/` → `[session]`

Front-matter добавляет топик-теги: `[testing]`, `[performance]`, `[git]` и т.д.

### Формат facts файла (file-per-fact, решено)

```markdown
---
tags: [testing, workflow]
type: preference
date: 2026-03-11
---

fix bugs test-first: write regression test, verify fails (red), fix, verify passes (green). Always in this order.
```

- Имя файла = саммари факта (`fix-bugs-test-first.md`)
- Теги: path-derived + front-matter `tags:` (union)
- `type`: preference | rule | critical-rule
- Obsidian: нативные теги из front-matter, filename search, граф сессий через `continues:`

### Формат session файла (решено)

```markdown
---
id: 0d9efbcd-...
date: 2026-03-11
project: ai-memory
title: Local storage architecture
type: planning
tags: [architecture]
continues: [[2026-03-10 Previous session]]
---

## Summary

1-2 предложения: задача + ключевые решения.

## Compact

Status: in-progress
Next: implement storage layer

Полный compact для resumption агентом.

## Content

Полный детальный контент сессии.
```

- Один файл на сессию (не чанки — перезаписывается целиком)
- Имя файла: `{date} {title}.md`

### MCP инструменты (решено)

| Инструмент | Назначение |
|---|---|
| `memory_session` | upsert сессии (.md файл) |
| `memory_remember` | сохранить правило/факт в .md |
| `memory_search` | поиск по тегам + опц. текст (бывший memory_get_facts) |
| `memory_explore_tags` | обзор тегов и их иерархии |
| `memory_resolve_tags` | нормализация тегов (поиск существующих по приближённым) |
| ~~memory_read_blob~~ | агент читает файл напрямую по пути |
| ~~memory_reinforce~~ | убран (нет весов) |
| ~~memory_project~~ | убран |

### Задачи

- [x] `plugins/ai-memory/mcp/tags.py` — парсинг front-matter + derivation тегов из пути
- [x] `plugins/ai-memory/mcp/storage.py` — файловые операции (search, upsert session, remember)
- [x] `plugins/ai-memory/mcp/server.py` — MCP stdio JSON-RPC сервер
- [x] Обновить `plugins/ai-memory/.mcp.json` → stdio транспорт
- [x] Bump plugin version (0.1.27)

---

## Блок 2 — Сессии: обновить скиллы и хуки ✅

**Цель:** `/save` и `/load` скиллы работают с новым .md форматом.

- [x] Обновить `/save` скилл — записывать новый .md формат (Summary + Compact + Content)
- [x] Обновить `/load` скилл — читать .md напрямую (без memory_read_blob)
  - Traverse `continues:` chain через front-matter вместо API
- [x] Обновить `session-start.py` hook — читать сессии из файлов, не из HTTP API
- [x] Обновить `session-sync.py` — no-op (синхронизация через memory_session MCP)
- [x] Убрать поле `type` из формата файлов и хранилища (только тег `rule` из пути)
- [x] Убрать health check и `memory_project` из `session-reminder.py`
- [x] Конфиг: `AI_MEMORY_SESSIONS_DIR` — путь для сессий (можно указать Obsidian vault)

---

## Блок 3 — Факты и правила: только явно ✅

- [x] Убрать `memory-nudge.bb` (убран из hooks.json)
- [x] Убрать из `CLAUDE.md` инструкции "save when you observe"
- [x] `memory-scribe` агент только для явного `/remember`

---

## Блок 4 — Ленивая загрузка правил ✅

- [x] `memory_session` возвращает релевантные правила по topic-тегам сессии (с дедупликацией по session_id)
- [x] `session-end.py` очищает dedup-кэш при завершении сессии (`hooks.json` matcher → `""`)
- [x] `CLAUDE.md` плагина: конкретные триггеры когда агент должен вызывать `memory_search`
- [x] Block 5: 4o-mini автосохранение + LLM-классификация топика

---

## Блок 5 — Опциональный OpenAI

### Без AI (базовый режим)

- [x] Поиск сессий: по дате, проекту, тегам из front-matter
- [x] Поиск правил: по тегам/папкам
- [x] `memory_resolve_tags`: fuzzy/substring match без векторов

### С OpenAI (опционально)

- [x] Семантический поиск сессий и правил по содержанию (`content_store.search()`)
- [x] `memory_resolve_tags`: embedding-based tag normalization (`tag_store.find_similar()`)
- [x] Vector store инфраструктура: `lib/vector_store/` (ABC + JSON backend + Qdrant backend)
- [x] Content vectorization: MD5 freshness check, batch embedding, единая "content" коллекция
- [x] Конфиг: `OPENAI_API_KEY` (embedding backend auto-detects)
- [x] `Stop` hook + 4o-mini: автосохранение compact без участия основного агента
- [x] `UserPromptSubmit` hook + 4o-mini: классификация топика → загрузка правил

---

## Блок 6 — Перевод хуков с Babashka на Python 3 ✅

- [x] Переписать все хуки на Python 3 (stdlib only)
- [x] Обновить `hooks.json`
- [x] HTTP через `urllib.request`

---

## Блок 7 — Рефактор плагина (финальная сборка)

- [x] Обновить `hooks.json` — все хуки Python, триггеры актуальны
- [x] `/remember` скилл — явное сохранение правила через `memory_remember` MCP
- [x] Убрать legacy Clojure-зависимости из всех скриптов
- [ ] Обновить `CLAUDE.md` плагина (документировать новые MCP инструменты, query vs tags, /save /load /remember)

---

## Блок 8 — Shared lib: вынести общий код в `lib/` ✅

- [x] Создать `lib/__init__.py` с `get_plugin_root() -> Path`
- [x] Переместить `mcp/storage.py` → `lib/storage.py`
- [x] Переместить `mcp/tags.py` → `lib/tags.py`
- [x] Обновить все импорты (`mcp/server.py`, `hooks/scripts/*.py`, `skills/load/load-chain.py`)
- [x] `lib/vector_store/` — VectorStore ABC, JSON/Qdrant backends, tag_store, content_store
- [x] `lib/embedding.py` — OpenAI text-embedding-3-small batch API
- [ ] При росте: добавить `lib/protocols.py` с `typing.Protocol` интерфейсами для подмены реализации

---

## Блок 9 — SQLite как локальный индекс ✅

**Цель:** единая SQLite БД как локальный кэш/индекс поверх файловой системы. Ноль внешних зависимостей (sqlite3 в stdlib). Файлы остаются source of truth.

**Расположение:** `~/Library/Caches/ai-memory/index.db` (macOS) / `~/.cache/ai-memory/index.db` (Linux). Кэш-директория, не синхронизируется iCloud. Вся БД восстановима из файлов (кроме векторов — re-embedding платный, но дешёвый). Для экономии на embeddings при нескольких девайсах — подключить внешний Qdrant (`QDRANT_URL`).

### Что заменяет

| Было | Стало |
|------|-------|
| `explore_tags()` — rglob + чтение всех .md | `SELECT tag, count FROM file_tags GROUP BY tag` |
| `resolve_tags()` — rglob для сбора all_tags | `SELECT DISTINCT tag FROM file_tags` |
| `_filescan_search()` — rglob + фильтр | `SELECT rel_path FROM file_tags WHERE tag IN (...)` |
| `json_store.py` — полная загрузка JSON на каждый read/write | SQLite BLOB vectors, частичное чтение |
| `~/.claude/hooks/state/*.json` — россыпь файлов | таблица `state` с атомарными записями (WAL) |

### Синхронизация (filesystem → SQLite)

- **Write-path** (remember, upsert_session): обновить индекс синхронно сразу после записи файла
- **Read-path** (explore_tags, search_facts): если данные в SQLite есть — использовать; иначе fallback на filescan
- **Session-start reindex**: reconciliation при старте сессии (~50ms на 500 файлов):
  - Файл на диске, нет в БД → insert
  - Файл на диске, mtime изменился → re-read, update
  - Запись в БД, файла нет → delete
- **БД удалена/отсутствует**: CREATE TABLE IF NOT EXISTS → полный reindex
- **force reindex**: игнорировать mtime, пересчитать всё (для edge cases)

### Задачи

- [x] `lib/db.py` — SQLite wrapper: init, migrate (user_version pragma), connection management
- [x] `lib/db.py` — reindex: filesystem ↔ SQLite reconciliation (mtime-based)
- [x] Заменить `explore_tags()` на SQL query с fallback
- [x] Заменить `_filescan_search()` на SQL query с fallback
- [x] Заменить `resolve_tags()` all_tags scan на SQL query
- [x] `lib/vector_store/sqlite_store.py` — VectorStore impl (BLOB vectors, brute-force cosine)
- [x] Перенести hook state из JSON файлов в таблицу `state`
- [x] Вызов reindex в session-start hook
- [x] Обновить remember/upsert_session — синхронное обновление индекса после записи файла

---

## Блок 10 — Wikilinks: ссылки между сущностями

**Цель:** `[[wikilink]]` как первоклассные ссылки между фактами, правилами и сессиями. Obsidian-совместимый формат.

### Формат

`[[stem]]` — имя файла без `.md`. Примеры:
- Факт: `[[fix-bugs-test-first]]`
- Сессия: `[[2026-03-12 wikilinks support design.d3123a88]]`

### Задачи

- [x] `memory_remember` возвращает `Saved → [[stem]]` вместо `ok`
- [x] `memory_search` — каждый результат содержит поле `ref: "[[stem]]"`
- [x] `memory_session` — правила в lazy-load содержат `[[stem]]` ссылки
- [x] Stop hook (`session-sync.py`) — извлекает `[[refs]]` из tool_result блоков ai-memory, добавляет `## References` секцию в messages.md
- [ ] Промпты: инструкция агенту использовать `[[wikilink]]` при cross-references в фактах/правилах
- [ ] Продумать поведение `memory_search` — может создавать слишком много связей в графе
- [ ] SQLite link index: парсить `[[...]]` из всех файлов → таблица `links(from_path, to_stem)` для навигации и "related facts"

---

## Порядок реализации

1. ~~**Блок 3**~~ ✅
2. ~~**Блок 6**~~ ✅
3. ~~**Блок 1**~~ ✅
4. ~~**Блок 2**~~ ✅
5. ~~**Блок 4**~~ ✅
6. ~~**Блок 8**~~ ✅
7. ~~**Блок 5**~~ ✅ — опциональный AI (векторы ✅, LLM auto-digest ✅)
8. ~~**Блок 9**~~ ✅ — SQLite локальный индекс
9. **Блок 10** — wikilinks (MCP ответы ✅, Stop hook ✅, промпты и граф — остались)
10. **Блок 11** — async notifications + rule loading
11. ~~**Блок 12**~~ ✅ — fact extraction from user messages in LLM digest
12. **Блок 13** — cross-agent support (Codex CLI)
13. **Блок 14** — tool call pattern learning
14. **Блок 7** — финальный рефактор плагина (CLAUDE.md осталось)

---

## Блок 11 — Async notifications + rule loading

**Цель:** async хуки (digest) могут уведомлять агента о результатах и загружать релевантные правила.

**Проблема:** сейчас async хуки (Stop digest, early digest, final digest) записывают session file, но агент не знает о результатах. `search_tags` из LLM-ответа не используется — раньше `memory_session` загружал правила по topic-тегам, но теперь агент не вызывает `memory_session` при LLM on.

### Задачи

- [ ] Очередь нотификаций (FIFO): async хуки пишут в SQLite очередь → любой синхронный хук (PreToolUse, UserPromptSubmit и т.д.) вычитывает и доставляет агенту через stdout
- [ ] Lazy rule loading через `search_tags`: после digest найти релевантные правила, доставить агенту через notification
- [ ] Решить что делать с `search_tags` в схеме — убрать для экономии токенов или оставить для rule loading
- [ ] Compact spec sync: `lib/digest.py` COMPACT_SPEC и `skills/save/SKILL.md` описывают одно и то же — при обновлении менять оба

---

## Блок 13 — Cross-agent support: Codex CLI

**Цель:** запустить ai-memory из Codex CLI с минимальными изменениями. Codex поддерживает хуки (экспериментально, feature-flag `features.codex_hooks`), протокол почти идентичен Claude Code.

### Контекст — что совместимо

Codex hooks передают stdin JSON с теми же полями что Claude Code: `session_id`, `transcript_path`, `cwd`, `hook_event_name`, `model`. Exit code 2 = блок, конфиг в `~/.codex/hooks.json` + `<repo>/.codex/hooks.json` (JSON формат). Сессии в `~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl`.

**Маппинг событий:**

| Claude Code | Codex | Действие |
|---|---|---|
| `SessionStart` (startup/resume) | `SessionStart` ✅ | 1:1, но `source` не включает `clear`/`compact` |
| `UserPromptSubmit` | `UserPromptSubmit` ✅ (v0.116.0+) | 1:1 |
| `PreToolUse` | `PreToolUse` ⚠️ | **только Bash**, MCP-вызовы не перехватываются |
| `PostToolUse` | `PostToolUse` ⚠️ | только Bash |
| `Stop` | `Stop` ✅ | 1:1 |
| `SessionEnd` | ❌ отсутствует | финальный digest переехать в `Stop` |
| `PreCompact` / `source==clear` | ❌ отсутствует (нет концепции) | auto-chain сессии недоступен |
| `SubagentStop`, `Notification` | ❌ отсутствует | не критично |

### Что уже портируемо без изменений

- ✅ **MCP-сервер** (`mcp/server.py`) — чистый JSON-RPC stdio, Codex тоже MCP-клиент
- ✅ **Storage** (`lib/storage.py`) — `AI_MEMORY_DIR` env var (default `~/.ai-memory/`), git-based project derivation
- ✅ **SQLite cache** (`~/Library/Caches/ai-memory/`) — платформно-нейтрально
- ✅ **Формат session/fact файлов** — Markdown + frontmatter
- ✅ **Hook-скрипты логически** — читают те же поля из stdin JSON
- ✅ **Agent detection** (`lib/__init__.py:detect_agent()`) — определяет claude/codex по transcript_path, model, или явному полю

### Потери функциональности (приемлемые)

- **Нет auto-chain на `/clear`** — у Codex нет clear-концепта. Mitigation: ручной `/load`.
- **Нет гарантированного финального digest** — `SessionEnd` отсутствует. Mitigation: per-turn digest в `Stop` покрывает 95%.
- **Нет auto-approve MCP tools через PreToolUse** — Codex не перехватывает MCP. Mitigation: whitelist в Codex конфиге.

### Формат transcript (важно)

**Codex rollout JSONL несовместим с Claude Code JSONL структурно** — нужен отдельный парсер. Файлы: `~/.codex/sessions/YYYY/MM/DD/rollout-<ts>-<uuid>.jsonl`.

Ключевые отличия формата:
- Envelope: `{timestamp, type, payload}` с 4 типами: `session_meta` (1-я строка), `turn_context` (per turn, здесь `model`), `response_item` (канонический поток), `event_msg` (телеметрия + дубликат текста — игнорировать)
- Tool calls — **отдельные top-level items** (не nested content blocks), парные по `call_id`
- `arguments` и `output` у function_call — **JSON-encoded strings** (нужен двойной decode)
- Два семейства tool-вызовов: `function_call` (builtin, arguments=JSON) и `custom_tool_call` (input=raw string)
- Нет `parentUuid` → линейный поток, без DAG-обхода
- Reasoning — отдельный `response_item` с `encrypted_content` blob
- User/assistant текст дублируется в `response_item.message` и `event_msg.user_message` — использовать только `response_item`

**Оценка:** новый `lib/codex_session_loader.py` ~100-150 строк, выдаёт тот же промежуточный формат (turns с user/assistant/tool) что ест digest pipeline. Архитектурных изменений в storage/digest/формате .md не требуется.

### Фаза 1 — MVP (минимальная склейка)

- [x] Переименовать дефолт `AI_MEMORY_DIR`: `~/.claude/ai-memory/` → `~/.ai-memory/` (fallback на старый путь для миграции существующих установок)
- [x] Убрать `${CLAUDE_SESSION_ID}` из `skills/save/SKILL.md:54-56` — использовать session_id из контекста/stdin payload
- [x] Создать `plugins/ai-memory/.codex/hooks.json` — зеркало `hooks.json` с маппингом:
  - `SessionStart` (startup|resume) → `session-start.py` (ветка `source==clear` станет мёртвой, это ок)
  - `UserPromptSubmit` → `session-reminder.py` + `session-early-digest.py`
  - `Stop` → `session-sync.py` + `session-final-digest.py` (компенсирует отсутствие `SessionEnd`)
  - PreToolUse auto-approve для MCP — **не включать** (Codex не перехватывает MCP-вызовы)
- [x] Проверить что MCP сервер запускается из Codex через `.codex/config.toml` (или аналог `.mcp.json`)
- [ ] Smoke-тест: MCP-операции (`memory_search`, `memory_remember`) работают из Codex без транскрипта

### Фаза 1.5 — Codex transcript parser (блокирующее для session-sync)

Без этого в Codex не будут работать: session-sync (Stop hook), LLM digest, auto-save транскрипта, fact extraction. То есть MCP-tools будут работать, а автоматическая память — нет.

- [x] `lib/codex_session_loader.py` — парсер rollout JSONL (~100-150 строк)
  - Парсить `session_meta` → session_id, cwd, git, cli_version
  - Парсить первый `turn_context` → model
  - Для `response_item`: обрабатывать `message`/`reasoning`/`function_call`/`function_call_output`/`custom_tool_call`/`custom_tool_call_output`
  - Индексировать tool calls по `call_id` для pairing
  - Двойной `json.loads` для `arguments` и `output`
  - Игнорировать `event_msg` и `turn_context` (кроме первого)
- [x] Определить формат-агностичный intermediate representation в `lib/transcript.py` — общий для Claude и Codex парсеров (normalization to Claude Code entry format)
- [x] Рефакторинг `session-sync.py`: выделить парсинг в pluggable loader (Claude vs Codex), downstream логика (digest, transcript render, fact extraction) принимает intermediate representation
- [x] Определить какой парсер использовать: по пути файла (`~/.codex/sessions/`), filename (`rollout-*`), или первой строке (`session_meta`) → реализовано в `lib/transcript.py:detect_format()`
- [ ] Smoke-тест full round-trip: работа в Codex → Stop hook → digest → .md файл в `~/.ai-memory/`

### Фаза 2 — Полный паритет

- [ ] Codex-адаптация `/load` skill — заменить `AskUserQuestion` (Claude-specific) на textual prompt или Codex-аналог
- [ ] Добавить `agent` поле во frontmatter сессий (`claude-code` / `codex`) для cross-agent трекинга
- [ ] Документировать установку в Codex в README (feature-flag, пути конфигов)
- [ ] Проверить что session-final-digest корректно вызывается из `Stop` hook (вместо `SessionEnd`) в Codex — возможно нужен флаг "последний Stop"

### Фаза 3 — Опционально

- [ ] Graceful handling ветвления `source == clear|compact` в `session-start.py` — вынести в отдельную функцию, документировать что это Claude-only
- [ ] Research: есть ли в Codex Notification-подобный механизм для доставки async уведомлений (связано с Блок 11)

---

## Блок 12 — Fact extraction from user messages ✅

**Цель:** LLM-digest извлекает атомарные факты из пользовательских сообщений с оценкой важности (0-100). Сохраняются в `## Facts` секции session файла, доступны через `/load`.

### Решения

- Факты из user messages only (assistant — контекст)
- Importance 0-100 (непрерывная шкала)
- Compact без изменений
- Дедупликация: последние ~15 фактов передаются в LLM промпт
- `/load`: все факты если мало, top by importance если много, хронологический порядок

### Задачи

- [x] `Fact` dataclass + `SessionDigestWithFacts`/`SessionDigestLightWithFacts` (parallel lists для structured output)
- [x] `DigestState.facts` — аккумуляция фактов между digest вызовами
- [x] `FACTS_SPEC` — инструкции для LLM по извлечению фактов
- [x] `build_digest_prompt()` — previous facts context для дедупликации
- [x] `compute_digest()` — zip parallel lists → `Fact`, merge с state
- [x] `serialize_state()`/`deserialize_state()` — facts в JSON
- [x] `storage.upsert_session()` — `facts` параметр, `## Facts` секция, preserve existing facts
- [x] `session_loader.py` — загрузка фактов, отображение в `format_for_load()`
- [x] `session-sync.py` — передача фактов из DigestState в upsert_session
- [x] `session-final-digest.py` — аналогично

---

## Блок 14 — Tool call pattern learning

**Цель:** анализировать tool calls пользователя и запоминать паттерны поведения. Например, если пользователь постоянно отклоняет определённую команду — сохранить это как правило или предпочтение.

### Идея

- Отслеживать `PreToolUse` / `PostToolUse` хуки — фиксировать какие tool calls пользователь approve/deny
- Если пользователь N раз подряд отклоняет один и тот же тип tool call — сохранить как правило (через `memory_remember`) или добавить в session context
- Возможные паттерны для отслеживания:
  - Repeatedly denied tools (e.g. "user always denies `git push`")
  - Часто используемые tool sequences (workflow patterns)
  - Tool calls которые пользователь всегда редактирует перед approve

### Задачи

- [ ] Определить какие события доступны (exit code 2 = deny в PreToolUse)
- [ ] Счётчик deny per tool type в SQLite state таблице
- [ ] Порог для автоматического сохранения правила (e.g. 3+ deny подряд за сессию)
- [ ] Формат правила: `"User prefers not to use {tool} for {pattern}"` → `memory_remember`
- [ ] Альтернатива: уведомление агенту через notification queue (Блок 11) вместо автосохранения

---

## Открытые вопросы

Закрытые:
- [x] **Формат правил:** file-per-fact; базовое разбиение universal/languages/projects, топики в тегах
- [x] **Формат сессий:** single .md, front-matter + Summary/Compact/Content
- [x] **MCP транспорт:** Python stdio сервер
- [x] **Clojure бэкенд:** убирается полностью
- [x] **memory_reinforce:** убран (нет весов в новой системе)
- [x] **Синхронизация:** iCloud target, git опционально
- [x] **Чанки:** убраны, сессия перезаписывается целиком

Открытые:
- [x] Stop hook + 4o-mini: что передавать в качестве контекста? → LLM-friendly transcript (text + tool summaries)
- [x] SQLite индекс: → Блок 9, всегда включён (stdlib, нулевой overhead)
- [x] Индекс сессий (session_id → filename) — решено через `{date} {title}.{sid8}.md` формат + glob O(1)
- [x] Индекс тегов (tag → [file paths]) — → Блок 9, таблица file_tags с индексом
