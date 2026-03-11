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

## Блок 2 — Сессии: обновить скиллы и хуки

**Цель:** `/save` и `/load` скиллы работают с новым .md форматом.

- [ ] Обновить `/save` скилл — записывать новый .md формат (Summary + Compact + Content)
- [ ] Обновить `/load` скилл — читать .md напрямую (без memory_read_blob)
  - Traverse `continues:` chain через front-matter вместо API
- [ ] Обновить `session-start.py` hook — читать сессии из файлов, не из HTTP API
- [ ] Обновить `session-end.py` и `session-sync.py` — аналогично
- [ ] Конфиг: `AI_MEMORY_SESSIONS_DIR` — путь для сессий (можно указать Obsidian vault)

---

## Блок 3 — Факты и правила: только явно ✅

- [x] Убрать `memory-nudge.bb` (убран из hooks.json)
- [x] Убрать из `CLAUDE.md` инструкции "save when you observe"
- [x] `memory-scribe` агент только для явного `/remember`

---

## Блок 4 — Ленивая загрузка правил

**Цель:** загружать только релевантные правила в зависимости от задачи.

- [ ] `UserPromptSubmit` hook: анализировать промпт по ключевым словам → подгружать правила по тегам
- [ ] В `CLAUDE.md` плагина: инструкция агенту запрашивать правила через `memory_search` перед новым топиком
- [ ] Ленивая загрузка = чтение нужного файла/папки по тегу (тривиально при файловой структуре)

---

## Блок 5 — Опциональный OpenAI

### Без AI (базовый режим)

- [ ] Поиск сессий: по дате, проекту, тегам из front-matter
- [ ] Поиск правил: по тегам/папкам
- [ ] `memory_resolve_tags`: fuzzy/substring match без векторов

### С OpenAI (опционально)

- [ ] Семантический поиск сессий и правил по содержанию
- [ ] `memory_resolve_tags`: embedding-based tag normalization
- [ ] `Stop` hook + 4o-mini: автосохранение compact без участия основного агента
- [ ] `UserPromptSubmit` hook + 4o-mini: классификация топика → загрузка правил
- [ ] Конфиг: `OPENAI_API_KEY` + `AI_MEMORY_LLM=openai`

---

## Блок 6 — Перевод хуков с Babashka на Python 3 ✅

- [x] Переписать все хуки на Python 3 (stdlib only)
- [x] Обновить `hooks.json`
- [x] HTTP через `urllib.request`

---

## Блок 7 — Рефактор плагина (финальная сборка)

- [ ] Обновить `CLAUDE.md` плагина (новые инструменты, новые пути)
- [ ] Пересмотреть триггеры хуков
- [ ] `/remember` скилл — явное сохранение правила
- [ ] Убрать legacy Clojure-зависимости из всех скриптов

---

## Порядок реализации

1. ~~**Блок 3**~~ ✅
2. ~~**Блок 6**~~ ✅
3. ~~**Блок 1**~~ ✅
4. **Блок 2** ← сейчас (обновить скиллы и хуки под новый формат)
5. **Блок 4** — ленивая загрузка правил
6. **Блок 5** — опциональный AI
7. **Блок 7** — финальный рефактор плагина

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
- [ ] Stop hook + 4o-mini: что передавать в качестве контекста?
- [ ] SQLite индекс: когда включать (>500 файлов? >1000?)
- [ ] Clojure-бэкенд: архивировать или удалить из репо?
