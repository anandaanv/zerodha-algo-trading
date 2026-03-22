# Trading Co-Pilot — Implementation Plan
**Branch:** `feature/trading-copilot`
**Source spec:** `spec/Claude/trading_copilot_requirements.docx`
**Date:** 2026-03-22

---

## System Purpose
An expert co-pilot — not an automated trading system. Works alongside an experienced trader. Human is always final authority. The system informs, validates, flags, questions, tracks, and learns. It never overrides, never fills gaps with assumptions, never proceeds silently past a problem.

---

## Four Foundational Rules
1. **Wave count must be confirmed before proceeding** — Mode A (expert labels chart), Mode B (system proposes, expert confirms/rejects). System stops and asks if unconfirmed. Never assumes.
2. **Expert is final authority** — System flags anomalies and requires override acknowledgment, but proceeds with expert decision and logs it.
3. **Agreement gate before dashboard** — Both system and expert must agree before a trade enters the dashboard. Override trades are tracked separately.
4. **Confidence is layer-based, not averaged** — One failing layer vetoes the entire setup regardless of other strengths.

---

## Architecture Decisions (from planning discussions)

### AI Provider
- Use existing OpenAI integration (already in backend)
- User connects their own OpenAI API key via the UI (OAuth-style settings page)
- API key stored encrypted in DB per user — NOT in `application.properties`

### Orchestrator — Multi-Turn Loop
- Orchestrator is itself an AI call that decides which skills to invoke and in what sequence
- Flow: App → Orchestrator (with investigation context) → Orchestrator returns skill request → App loads skill → App calls AI with skill + context → Returns FINDING/NEEDS_DATA/NEEDS_EXPERT/etc. → App updates investigation → Loop continues
- **Cycle prevention**: Investigation object tracks which skills have already been invoked with their results. Every orchestrator call receives this history. App refuses to re-invoke a skill already present in results. Natural termination when all needed skills are exhausted.
- No arbitrary turn limit.

### Skills
- Stored in DB per user (Phase 1: single-user CRUD only)
- Phase 2: public/fork sharing system
- Each skill has 7 standard components (see spec Section 8.2)
- Phase 1 seeds: Triangle Skill (partial) + Wave 4 Skill (partial)

### Market Structure Service
- Built on top of existing `ZigZagService.java`
- Extracts maximum detail without sending raw OHLC to AI:
  - Trend direction: HH/HL sequences (uptrend), LH/LL sequences (downtrend)
  - Structure breaks: BOS (Break of Structure), CHoCH (Change of Character)
  - Swing labels with price + timestamp
  - Momentum context (MACD direction) at each swing point
- This data replaces raw OHLCV in AI prompts

### Investigation Scope
- Persisted in DB (not session-only)
- Scoped per: `ChartLayout` + `User`
- Time-based validity: configurable per layout (default 1 hour)
- On new investigation: pending system-proposed drawings cleared and redrawn
- Previous investigation passed as context to new orchestrator call at the start

### Drawings
- System-proposed drawings injected into TradingView chart in **yellow**
- Clicking a system drawing shows a callout: **Confirm** / **Dismiss**
- Confirmed → color changes (no longer pending)
- Pending drawings from previous investigation auto-cleared on new investigation start
- Wave labels and text annotations are part of the drawing JSON — no separate mechanism

### Chat Window
- Single window (existing AIChatOverlay)
- System notifications visually distinct (different background/border) but in same panel
- Action buttons (Approve/Dismiss trade) rendered inline in chat messages — not a separate panel

### Frontend Routes
- `/copilot` — Copilot Dashboard (Hypothesis Board + Active Trades + Override Trades), reuses existing chart components
- `/skills` — Skill Builder, reuses existing Prompt Builder UI adapted for 7-component skill structure

### Candle-Close Monitoring
- Triggered from backend (not frontend)
- Phase 1: polling/scheduled check
- Future Phase: WebSocket push after investigation

### Same layout + different users = different investigations
### Different layouts = different investigations (even same symbol)

---

## Data Model

### New DB Tables

#### `user_openai_credentials`
| Column | Type | Notes |
|--------|------|-------|
| id | BIGINT PK | |
| user_id | BIGINT FK | references users table |
| api_key_encrypted | VARCHAR | AES encrypted |
| created_at | TIMESTAMP | |
| updated_at | TIMESTAMP | |

#### `copilot_skill`
| Column | Type | Notes |
|--------|------|-------|
| id | BIGINT PK | |
| user_id | BIGINT FK | |
| name | VARCHAR | e.g. "Triangle Skill" |
| category | VARCHAR | WAVE / PATTERN / CONFIRMATION / OVERRIDE |
| identification_rules | TEXT | Component 1 |
| stage_detection | TEXT | Component 2 |
| entry_rules | TEXT | Component 3 |
| indicator_rules | TEXT | Component 4 |
| invalidation_rules | TEXT | Component 5 |
| ambiguity_questions | TEXT | Component 6 |
| cross_verification_rules | TEXT | Component 7 |
| is_active | BOOLEAN | |
| created_at | TIMESTAMP | |
| updated_at | TIMESTAMP | |

#### `copilot_investigation`
| Column | Type | Notes |
|--------|------|-------|
| id | BIGINT PK | |
| layout_id | BIGINT FK | references chart_layout |
| user_id | BIGINT FK | |
| symbol | VARCHAR | |
| timeframes_active | VARCHAR | JSON array |
| zigzag_data | TEXT | JSON per timeframe |
| market_structure_data | TEXT | JSON per timeframe |
| drawings | TEXT | JSON per timeframe |
| wave_count_confirmed | BOOLEAN | |
| wave_count_source | VARCHAR | EXPERT_LABELED / SYSTEM_PROPOSED |
| invoked_skills | TEXT | JSON array — cycle detection |
| skill_results | TEXT | JSON map skillName→result |
| anomaly_flags | TEXT | JSON array |
| expert_overrides | TEXT | JSON array |
| validity_minutes | INT | default 60 |
| expires_at | TIMESTAMP | |
| status | VARCHAR | ACTIVE / EXPIRED / SUPERSEDED |
| created_at | TIMESTAMP | |
| updated_at | TIMESTAMP | |

#### `copilot_hypothesis`
| Column | Type | Notes |
|--------|------|-------|
| id | BIGINT PK | |
| investigation_id | BIGINT FK | |
| symbol | VARCHAR | |
| label | VARCHAR | e.g. "Wave 4 Triangle" |
| description | TEXT | |
| wave_context | VARCHAR | wave_1 … wave_5, wave_a, wave_b, wave_c |
| pattern | VARCHAR | triangle, double_bottom, etc. |
| stage | VARCHAR | current stage within pattern |
| state | VARCHAR | WATCHING / BUILDING / CONFIRMED / TRADE_ACTIVE / INVALIDATED / EXPIRED |
| confidence_layers | TEXT | JSON layer→status |
| anticipatory_trade | TEXT | JSON {entry_zone, sl, tp, conditions_needed} |
| confirmation_trade | TEXT | JSON {entry_zone, sl, tp, conditions_needed} |
| invalidation_conditions | TEXT | JSON array |
| created_at | TIMESTAMP | |
| updated_at | TIMESTAMP | |

#### `copilot_hypothesis_relationship`
| Column | Type | Notes |
|--------|------|-------|
| id | BIGINT PK | |
| hypothesis_id | BIGINT FK | |
| related_hypothesis_id | BIGINT FK | |
| relationship_type | VARCHAR | CONFLICTING / SEQUENTIAL / INDEPENDENT / REINFORCING |

#### `copilot_active_trade`
| Column | Type | Notes |
|--------|------|-------|
| id | BIGINT PK | |
| hypothesis_id | BIGINT FK | |
| symbol | VARCHAR | |
| entry_type | VARCHAR | ANTICIPATORY / CONFIRMATION |
| entry_price | DECIMAL | |
| sl | DECIMAL | |
| tp | DECIMAL | |
| size | DECIMAL | |
| state | VARCHAR | ENTERED / MONITORING / CLOSED |
| origin | VARCHAR | SYSTEM_SUGGESTED / EXPERT_SUGGESTED |
| is_override_trade | BOOLEAN | |
| override_reason | TEXT | |
| system_objection | TEXT | |
| close_price | DECIMAL | |
| outcome | VARCHAR | WIN / LOSS / BREAKEVEN / OPEN |
| close_notes | TEXT | |
| opened_at | TIMESTAMP | |
| closed_at | TIMESTAMP | |

#### `copilot_anomaly_flag`
| Column | Type | Notes |
|--------|------|-------|
| id | BIGINT PK | |
| investigation_id | BIGINT FK | |
| hypothesis_id | BIGINT FK nullable | |
| flag_text | TEXT | |
| level | VARCHAR | INFO / WARNING / CRITICAL |
| acknowledged | BOOLEAN | default false |
| acknowledged_at | TIMESTAMP | |
| action_taken | VARCHAR | ACCEPT / OVERRIDE / DISMISS |
| expert_notes | TEXT | |
| created_at | TIMESTAMP | |

---

## Six AI Response Types

| Type | Meaning | App Action |
|------|---------|------------|
| NEEDS_DATA | AI requires more data | Fetch specified data, call AI again |
| NEEDS_EXPERT | Decision point requiring human | Surface question in chat, wait for response |
| FINDING | Pattern/hypothesis identified | Update investigation + dashboard |
| ENTRY_SIGNAL | Entry conditions met | Notify expert, await approval |
| MONITORING | Not yet — conditions pending | Schedule next check |
| INVALIDATED | Hypothesis invalidation triggered | Close linked trade, review alternates, notify |

---

## Orchestration Flow (per investigation)

```
Layout Open
    │
    ▼
Assemble Tier 1 data (Daily ZigZag + Market Structure + Drawings)
Create Investigation object
    │
    ▼
Call Orchestrator AI (with investigation + skill inventory)
    │
    ├─► Returns: "invoke Triangle Skill"
    │       App loads Triangle Skill content
    │       App calls AI with skill + investigation
    │       Returns: NEEDS_DATA (need hourly ZigZag)
    │       App fetches Tier 2 data, calls AI again
    │       Returns: FINDING (hypothesis created)
    │
    ├─► Returns: "invoke Wave 4 Skill"
    │       Cross-verification run
    │       Returns: NEEDS_EXPERT (wave count unclear)
    │       Question surfaced in chat
    │       Expert responds → investigation updated
    │
    └─► Returns: MONITORING
            Scheduled checks begin on candle close
            On ENTRY_SIGNAL → notify expert via chat
```

---

## Confidence Layers

| Layer | Evaluates |
|-------|-----------|
| 1 — Elliott Wave Context | Wave count confirmed? Pattern fits wave? |
| 2 — Pattern Location | At meaningful structural level? |
| 3 — Fibonacci Confluence | Completing at key Fib level? |
| 4 — Pattern Validity | Well-formed by its own structural rules? |
| 5 — Cross-Timeframe | Other TF indicators support thesis? |
| 6 — Lower TF Entry Trigger | Entry conditions met on trading/entry TF? |

---

## 3-Tier Data Strategy

| Tier | Contents | When Fetched |
|------|----------|--------------|
| Tier 1 — Context | Weekly + Daily ZigZag, Market Structure, Drawings, Wave labels | Always at layout open |
| Tier 2 — Pattern | Hourly ZigZag, last 50 hourly candles | When pattern identified, needs sub-structure |
| Tier 3 — Entry | 15min/5min indicators (RSI, Stochastic, MACD), last 20 candles | When hypothesis confirmed, monitoring entry |

---

## Build Order (Phases)

### Phase 1 — Data Foundation
- DB schema migration (all tables above)
- OpenAI credentials: encrypted storage per user, settings UI
- Market Structure Service (extends ZigZagService): HH/HL/LH/LL detection, BOS/CHoCH
- Investigation entity + repository + time-scoped validity logic

### Phase 2 — AI Bridge
- Six AI response type handler + routing
- Skill entity + CRUD (7-component structure)
- Orchestrator service: multi-turn loop, cycle detection via invoked_skills tracking
- Seed Triangle Skill + Wave 4 Skill with partial content

### Phase 3 — Analysis Logic
- Hypothesis state machine (WATCHING → BUILDING → CONFIRMED → TRADE_ACTIVE → INVALIDATED/EXPIRED)
- Hypothesis relationship evaluator (CONFLICTING / SEQUENTIAL / INDEPENDENT / REINFORCING)
- Trade entity + dual entry parameter calculator (anticipatory + confirmation)
- Entry monitoring scheduler (interface stubbed, backend-triggered on candle close)
- Anomaly flagging system (requires explicit expert acknowledgment)
- Override trade tracking (separate entity fields + section on dashboard)

### Phase 4 — API Layer
All REST endpoints:
- `POST /api/analysis/trigger`
- `POST /api/analysis/respond`
- `POST /api/analysis/confirm-wave`
- `GET /api/hypotheses/:symbol`
- `POST /api/hypotheses/:id/confirm`
- `POST /api/hypotheses/:id/dismiss`
- `GET /api/hypotheses/board`
- `GET /api/trades/dashboard`
- `POST /api/trades/:id/open`
- `POST /api/trades/:id/close`
- `POST /api/trades/:id/override`
- `POST /api/monitor/candle-close`
- `GET /api/monitor/alerts`
- `POST /api/monitor/alerts/:id/acknowledge`

### Phase 5 — Frontend
- OpenAI API key settings modal (in user settings)
- System-proposed drawings: yellow injection into TradingView, click → callout with Confirm/Dismiss
- `/copilot` route: Copilot Dashboard (Hypothesis Board + Active Trades + Override Trades)
- `/skills` route: Skill Builder (7-component form, reuses Prompt Builder UI)
- Chat: inline action buttons for approve/disapprove, system notifications visually distinct in same window

---

## Phase 2 Deferred (Not Phase 1)
- Skill sharing / public skills / fork system
- Learning analytics dashboard (data captured, no UI)
- Background scanning scheduler (interface stubbed only)
- Position sizing recommendations
- Direct broker integration
- WebSocket push for investigation results

---

## Key Design Principles (from spec Section 16.2)
- Skills are containers first. Never hardcode trading rules into application logic.
- Investigation object is the only context. Always pass full investigation to every AI call.
- AI declares, app acts. AI returns structured JSON. App does everything else.
- Hypothesis board is never cleared on trade entry.
- Every flag requires acknowledgment. System never silently proceeds past an anomaly.
- Override trades are first-class entities with their own tracking and dashboard section.
- Phase 1 architecture must support future background scanning without structural changes.
