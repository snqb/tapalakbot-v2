# Tracking Feature — Plan

## What

Users create **tracking filters** — saved searches that run periodically and send Telegram notifications when new matching items appear on Lalafo.kg.

## User Flow

```
/user: /track iPhone 13 до 30000
/bot:  ✅ Отслеживаю: «iPhone 13 до 30000»
       Уведомлю когда появятся новые объявления.
       Управление: /tracks, /untrack

... hours later, new iPhone 13 listing appears on Lalafo ...

/bot:  🔔 Новое по фильтру «iPhone 13 до 30000»:
       iPhone 13 128GB — 28 000 сом
       🔗 https://lalafo.kg/...

/user: /tracks
/bot:  📋 Ваши фильтры:
       1. iPhone 13 до 30000 (5 новых)
       2. MacBook Air (0 новых)

/user: /untrack 1
/bot:  🗑️ Фильтр «iPhone 13 до 30000» удалён.
```

## Data Model

```sql
-- Saved search filters
CREATE TABLE user_tracks (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id TEXT NOT NULL,          -- "tg-123456"
  title TEXT NOT NULL,            -- "iPhone 13 до 30000" (human-readable)
  queries TEXT NOT NULL,          -- EDN: ["iPhone 13" "айфон 13"]
  price_max INTEGER,              -- max price filter
  price_min INTEGER,              -- min price filter
  city_id INTEGER DEFAULT 103184, -- Bishkek
  enabled INTEGER DEFAULT 1,
  created_at TEXT DEFAULT (datetime('now')),
  last_checked_at TEXT,
  notify_count INTEGER DEFAULT 0  -- how many notifications sent
);

-- Track which items we've already notified about
CREATE TABLE track_seen_items (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  track_id INTEGER NOT NULL REFERENCES user_tracks(id) ON DELETE CASCADE,
  item_id INTEGER NOT NULL,       -- Lalafo item ID
  notified_at TEXT DEFAULT (datetime('now')),
  UNIQUE(track_id, item_id)
);
```

## Telegram Commands

| Command | Description |
|---------|-------------|
| `/track <query>` | Create tracking filter. Queries Lalafo, generates search terms via keyword split. |
| `/tracks` | List user's active filters with counts |
| `/untrack <id>` | Remove a filter |
| `/untrack all` | Remove all filters |

## Background Checker

- Runs every **2 hours** (configurable)
- For each active filter:
  1. Run Lalafo search with filter's queries + price constraints
  2. Filter results against `track_seen_items` — find items not yet notified
  3. If new items exist → send Telegram message → record in `track_seen_items`
- **Rate limit**: max 3 notifications per filter per check cycle (avoid spam)
- **LLM-free**: uses direct Lalafo API search (no LLM calls for checking)

## Files to Create/Modify

| File | Action | Purpose |
|------|--------|---------|
| `monitor/store.clj` | **modify** | Add `user_tracks` + `track_seen_items` tables, CRUD fns |
| `monitor/tracker.clj` | **create** | Background checker: scan filters, find new items, notify |
| `bot.clj` | **modify** | Add `/track`, `/tracks`, `/untrack` commands |
| `server.clj` | **modify** | Start tracker thread on boot |

## Implementation Order

1. `store.clj` — DB tables + CRUD functions
2. `tracker.clj` — background checker with Telegram notification
3. `bot.clj` — command handlers
4. `server.clj` — start tracker on boot
5. Deploy + test
