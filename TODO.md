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
11. **Блок 7** — финальный рефактор плагина (CLAUDE.md осталось)

---

## Блок 11 — Async notifications + rule loading

**Цель:** async хуки (digest) могут уведомлять агента о результатах и загружать релевантные правила.

**Проблема:** сейчас async хуки (Stop digest, early digest, final digest) записывают session file, но агент не знает о результатах. `search_tags` из LLM-ответа не используется — раньше `memory_session` загружал правила по topic-тегам, но теперь агент не вызывает `memory_session` при LLM on.

### Задачи

- [ ] Механизм async notification: async хук пишет результат в state DB → следующий синхронный хук (UserPromptSubmit) проверяет и доставляет агенту
- [ ] Lazy rule loading через `search_tags`: после digest найти релевантные правила, доставить агенту через notification
- [ ] Решить что делать с `search_tags` в схеме — убрать для экономии токенов или оставить для rule loading
- [ ] Compact spec sync: `lib/digest.py` COMPACT_SPEC и `skills/save/SKILL.md` описывают одно и то же — при обновлении менять оба

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
- [ ] Clojure-бэкенд: архивировать или удалить из репо?
