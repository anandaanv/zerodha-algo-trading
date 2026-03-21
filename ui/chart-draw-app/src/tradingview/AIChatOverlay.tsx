import React, { useState, useEffect, useLayoutEffect, useRef } from 'react';
import {
  technicalChatAnalysis, multiChartChatAnalysis,
  ChatMode, MultiChartChatResponse,
} from './analysisApi';
import { WorkspaceTab } from './workspaceTypes';
import {
  loadCustomPrompts, getLastUsedPromptId, setLastUsedPromptId,
} from './promptTypes';

interface Props {
  open: boolean;
  onToggle: () => void;
  symbol: string;
  timeframe: string;
  getChartState?: () => string;
  tabs?: WorkspaceTab[];
  activeTabId?: string;
  onOpenPromptBuilder?: () => void;
}

interface ChatMessage {
  role: 'user' | 'ai';
  content: string;
  timestamp: number;
}

// ─── Persistence ──────────────────────────────────────────────────────────────

const FONT_SIZE_KEY = 'ai_chat_font_size';
const PANEL_H_KEY  = 'ai_chat_panel_h';
const PANEL_W_KEY  = 'ai_chat_panel_w';

type FontSizeLevel = 'tiny' | 'small' | 'medium' | 'large';
const FONT_SIZES: Record<FontSizeLevel, { msg: number; input: number; label: string }> = {
  tiny:   { msg: 11, input: 12, label: 'T' },
  small:  { msg: 13, input: 14, label: 'S' },
  medium: { msg: 15, input: 16, label: 'M' },
  large:  { msg: 18, input: 18, label: 'L' },
};
const FONT_SIZE_ORDER: FontSizeLevel[] = ['tiny', 'small', 'medium', 'large'];

function loadFontSize(): FontSizeLevel {
  const s = localStorage.getItem(FONT_SIZE_KEY);
  return (s && FONT_SIZE_ORDER.includes(s as FontSizeLevel)) ? s as FontSizeLevel : 'small';
}
function loadPanelH() { return Number(localStorage.getItem(PANEL_H_KEY)) || 420; }
function loadPanelW() { return Number(localStorage.getItem(PANEL_W_KEY)) || 460; }

// ─── Component ────────────────────────────────────────────────────────────────

export default function AIChatOverlay({ open, onToggle, symbol, timeframe, getChartState, tabs, activeTabId, onOpenPromptBuilder }: Props) {
  const [chatHistory, setChatHistory] = useState<ChatMessage[]>([]);
  const [input, setInput]   = useState('');
  const [mode, setMode]     = useState<ChatMode>('VALIDATE');
  const [loading, setLoading] = useState(false);
  const [error, setError]   = useState<string | null>(null);
  const [customPromptId, setCustomPromptId] = useState<string>(() => getLastUsedPromptId() ?? '');

  const [fontSizeLevel, setFontSizeLevel] = useState<FontSizeLevel>(loadFontSize);
  const [panelH, setPanelH] = useState(loadPanelH);
  const [panelW, setPanelW] = useState(loadPanelW);

  const chatBottomRef = useRef<HTMLDivElement>(null);
  const inputRef      = useRef<HTMLTextAreaElement>(null);
  const panelRef      = useRef<HTMLDivElement>(null);
  const wasHiddenRef  = useRef(false);

  const fs = FONT_SIZES[fontSizeLevel];

  const cycleFont = () => {
    setFontSizeLevel(prev => {
      const next = FONT_SIZE_ORDER[(FONT_SIZE_ORDER.indexOf(prev) + 1) % FONT_SIZE_ORDER.length];
      try { localStorage.setItem(FONT_SIZE_KEY, next); } catch { /* ignore */ }
      return next;
    });
  };

  // ─── Auto-scroll ──────────────────────────────────────────────────────────
  useEffect(() => {
    chatBottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [chatHistory, loading]);

  // ─── Auto-reopen when AI responds while panel hidden ──────────────────────
  useEffect(() => {
    if (loading && !open) wasHiddenRef.current = true;
    if (!loading && wasHiddenRef.current) {
      wasHiddenRef.current = false;
      if (!open) onToggle();
    }
  }, [loading]);

  // ─── Focus input when panel opens ─────────────────────────────────────────
  useEffect(() => {
    if (open) setTimeout(() => inputRef.current?.focus(), 60);
  }, [open]);

  // ─── Set initial size directly on DOM — bypasses React style re-renders ───
  useLayoutEffect(() => {
    if (panelRef.current) {
      panelRef.current.style.width  = loadPanelW() + 'px';
      panelRef.current.style.height = loadPanelH() + 'px';
    }
  }, []); // once on mount only

  // ─── ResizeObserver — persist panel size ──────────────────────────────────
  useEffect(() => {
    if (!panelRef.current) return;
    const obs = new ResizeObserver(entries => {
      const { width, height } = entries[0].contentRect;
      const w = Math.round(width);
      const h = Math.round(height);
      if (w > 50 && h > 50) {  // ignore spurious near-zero values when hidden
        setPanelW(w); setPanelH(h);
        try { localStorage.setItem(PANEL_W_KEY, String(w)); localStorage.setItem(PANEL_H_KEY, String(h)); } catch { /* ignore */ }
      }
    });
    obs.observe(panelRef.current);
    return () => obs.disconnect();
  }, []);  // mount once — panel div never unmounts now

  // ─── Send ─────────────────────────────────────────────────────────────────
  const send = async (multiChart = false) => {
    if (!getChartState) { setError('Chart not ready'); return; }
    const userMessage = mode === 'VALIDATE' ? input.trim() : undefined;
    if (mode === 'VALIDATE' && !userMessage) return;

    const display = multiChart
      ? (mode === 'REASON' ? `Reason all ${tabs?.length} charts` : `Multi: ${userMessage}`)
      : (mode === 'REASON' ? 'Reason my drawings' : userMessage!);

    setChatHistory(prev => [...prev, { role: 'user', content: display, timestamp: Date.now() }]);
    if (userMessage) setInput('');
    setLoading(true); setError(null);

    try {
      const chartStateJson = getChartState();
      const p = tryParse(chartStateJson);
      let aiMessage: string;

      if (multiChart && tabs && tabs.length >= 2) {
        const charts = tabs.map(tab => ({
          label: tab.label, symbol: tab.symbol, timeframe: tab.timeframe,
          chartStateJson: tab.id === activeTabId ? chartStateJson : (tab.drawingsState || '{}'),
          visibleFrom: tab.id === activeTabId ? p?.visibleFrom : tab.visibleFrom,
          visibleTo:   tab.id === activeTabId ? p?.visibleTo   : tab.visibleTo,
        }));
        const res: MultiChartChatResponse = await multiChartChatAnalysis({ charts, userMessage, mode });
        aiMessage = res.message;
      } else {
        const prompts = loadCustomPrompts();
        const selectedPrompt = customPromptId ? prompts.find(pr => pr.id === customPromptId) : undefined;
        // Stale ID (prompt was deleted) — clear it so the dropdown shows Default
        if (customPromptId && !selectedPrompt) {
          setCustomPromptId('');
          setLastUsedPromptId(null);
        }
        const res = await technicalChatAnalysis({
          symbol, timeframe, chartStateJson, userMessage, mode,
          visibleFrom: p?.visibleFrom, visibleTo: p?.visibleTo,
          // Pass undefined (not null/empty) so JSON.stringify omits it → backend uses default logic
          customSystemPrompt: selectedPrompt?.promptText || undefined,
        });
        aiMessage = res.message;
      }

      setChatHistory(prev => [...prev, { role: 'ai', content: aiMessage, timestamp: Date.now() }]);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Request failed');
    } finally {
      setLoading(false);
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !loading) { e.preventDefault(); send(false); }
    if (e.key === 'Escape') onToggle();
  };

  const hasMultiCharts = tabs && tabs.length >= 2;

  return (
    <div style={{
      position: 'fixed',
      bottom: 16,
      left: 16,
      width: 'max-content',
      minWidth: 320,
      maxWidth: 'calc(100vw - 32px)',
      zIndex: 9998,
      display: 'flex',
      flexDirection: 'column',
      alignItems: 'stretch',
    }}>

      {/* ── History panel — always mounted, hidden when closed so resize is preserved ── */}
      {/* width/height set once via useLayoutEffect — React never re-sets them */}
      <div
        ref={panelRef}
        style={{
          minWidth: 320,
          minHeight: 200,
          resize: 'both',
          overflow: 'hidden',
          display: open ? 'flex' : 'none',
          flexDirection: 'column',
          borderRadius: '12px 12px 0 0',
            background: 'rgba(15,17,26,0.55)',
            backdropFilter: 'blur(14px)',
            WebkitBackdropFilter: 'blur(14px)',
            border: '1px solid rgba(255,255,255,0.12)',
            borderBottom: 'none',
            boxShadow: '0 -4px 24px rgba(0,0,0,0.3)',
          }}
        >
          {/* Header */}
          <div style={{
            display: 'flex', justifyContent: 'space-between', alignItems: 'center',
            padding: '7px 12px', borderBottom: '1px solid rgba(0,0,0,0.07)',
            background: 'rgba(255,255,255,0.08)', flexShrink: 0, userSelect: 'none',
            borderRadius: '12px 12px 0 0',
          }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <span style={{ fontSize: 12, fontWeight: 700, color: 'rgba(255,255,255,0.9)' }}>AI</span>
              <span style={{ fontSize: 11, color: 'rgba(255,255,255,0.45)' }}>{symbol} · {timeframe}</span>
              {loading && <span style={{ width: 7, height: 7, borderRadius: '50%', background: '#1976d2', display: 'inline-block', animation: 'pulse 1s infinite' }} />}
              {!loading && chatHistory.length > 0 && (
                <span style={{ background: 'rgba(100,181,246,0.2)', color: '#90caf9', borderRadius: 10, fontSize: 10, fontWeight: 700, padding: '1px 6px' }}>
                  {chatHistory.length}
                </span>
              )}
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 2 }}>
              <button style={btnStyle} onClick={() => setMode('REASON')} title="Reason mode">
                <span style={{ color: mode === 'REASON' ? '#90caf9' : 'rgba(255,255,255,0.35)', fontWeight: mode === 'REASON' ? 700 : 400, fontSize: 11 }}>Reason</span>
              </button>
              <button style={btnStyle} onClick={() => setMode('VALIDATE')} title="Validate mode">
                <span style={{ color: mode === 'VALIDATE' ? '#90caf9' : 'rgba(255,255,255,0.35)', fontWeight: mode === 'VALIDATE' ? 700 : 400, fontSize: 11 }}>Validate</span>
              </button>
              <button style={{ ...btnStyle, fontSize: 10, color: 'rgba(255,255,255,0.4)', fontWeight: 700, minWidth: 18 }} onClick={cycleFont} title={`Font: ${fontSizeLevel}`}>
                {fs.label}
              </button>
              <button style={{ ...btnStyle, fontSize: 15, color: 'rgba(255,255,255,0.35)', paddingLeft: 8 }} onClick={onToggle} title="Close (Esc / F4)">×</button>
            </div>
          </div>

          {/* Messages */}
          <div style={{ flex: 1, overflowY: 'auto', padding: '10px 12px', display: 'flex', flexDirection: 'column', gap: 8, minHeight: 0 }}>
            {chatHistory.length === 0 && (
              <div style={{ color: 'rgba(255,255,255,0.25)', fontSize: fs.msg, textAlign: 'center', padding: '32px 8px', lineHeight: 1.7 }}>
                {mode === 'REASON' ? 'Draw on the chart, then hit Send.' : 'Ask anything about this chart.'}
              </div>
            )}
            {chatHistory.map((msg, i) => (
              <div key={i} style={msg.role === 'user'
                ? { ...userBubble, fontSize: fs.msg }
                : { ...aiBubble, fontSize: fs.msg }
              }>
                {msg.role === 'ai' ? <MarkdownText text={msg.content} /> : msg.content}
              </div>
            ))}
            {loading && (
              <div style={aiBubble}><span style={{ color: '#ccc', letterSpacing: 4 }}>· · ·</span></div>
            )}
            {error && (
              <div style={{ padding: '6px 10px', background: 'rgba(255,152,0,0.12)', border: '1px solid rgba(255,152,0,0.3)', borderRadius: 8, color: '#e65100', fontSize: 12 }}>
                {error}
              </div>
            )}
            <div ref={chatBottomRef} />
          </div>
      </div>

      {/* ── Always-visible input bar ── */}
      <div style={{
        display: 'flex', alignItems: 'center', gap: 6,
        padding: '8px 12px',
        background: 'rgba(15,17,26,0.82)',
        backdropFilter: 'blur(14px)',
        WebkitBackdropFilter: 'blur(14px)',
        border: '1px solid rgba(255,255,255,0.15)',
        borderRadius: open ? '0 0 14px 14px' : 14,
        boxShadow: '0 2px 20px rgba(0,0,0,0.3)',
        transition: 'border-radius 0.15s',
        minWidth: 280,
      }}>
        {loading && (
          <span style={{ width: 6, height: 6, borderRadius: '50%', background: '#1976d2', flexShrink: 0 }} />
        )}
        {/* Prompt selector */}
        <select
          value={customPromptId}
          onChange={e => {
            setCustomPromptId(e.target.value);
            setLastUsedPromptId(e.target.value || null);
          }}
          style={{
            background: 'rgba(255,255,255,0.08)',
            border: '1px solid rgba(255,255,255,0.18)',
            borderRadius: 6,
            color: 'rgba(255,255,255,0.75)',
            fontSize: fs.input - 2,
            padding: '3px 4px',
            flexShrink: 0,
            maxWidth: 100,
            cursor: 'pointer',
          }}
        >
          <option value="">Default</option>
          {loadCustomPrompts().map(p => (
            <option key={p.id} value={p.id}>{p.name}</option>
          ))}
        </select>
        {/* Link to Prompt Builder */}
        {onOpenPromptBuilder && (
          <button
            onClick={onOpenPromptBuilder}
            title="Manage custom prompts"
            style={{ ...btnStyle, fontSize: 12, color: 'rgba(255,255,255,0.4)', flexShrink: 0 }}
          >⚙</button>
        )}
        {mode === 'VALIDATE' ? (
          <textarea
            ref={inputRef}
            rows={2}
            placeholder="What's on your mind?"
            value={input}
            onChange={e => setInput(e.target.value)}
            onFocus={() => { if (!open) onToggle(); }}
            onKeyDown={handleKeyDown}
            disabled={loading}
            style={{
              flex: 1, minWidth: 0, background: 'transparent', border: 'none', outline: 'none',
              resize: 'none', fontFamily: 'inherit',
              fontSize: fs.input,
              color: 'rgba(255,255,255,0.9)',
              padding: '2px 0',
              lineHeight: 1.5,
            }}
          />
        ) : (
          <span
            style={{ flex: 1, fontSize: fs.input, color: open ? '#aaa' : 'rgba(255,255,255,0.5)', fontStyle: 'italic', cursor: 'default' }}
            onClick={() => { if (!open) onToggle(); }}
          >
            Reason about my drawings…
          </span>
        )}
        <button
          onClick={() => send(false)}
          disabled={loading || (mode === 'VALIDATE' && !input.trim())}
          style={{
            ...actionBtn,
            background: '#1976d2',
            opacity: loading || (mode === 'VALIDATE' && !input.trim()) ? 0.35 : 1,
            fontSize: fs.input - 1,
          }}
        >Send</button>
        {hasMultiCharts && (
          <button
            onClick={() => send(true)}
            disabled={loading || (mode === 'VALIDATE' && !input.trim())}
            title={`All ${tabs!.length} charts`}
            style={{
              ...actionBtn, background: '#7b1fa2',
              opacity: loading || (mode === 'VALIDATE' && !input.trim()) ? 0.35 : 1,
              fontSize: fs.input - 1,
            }}
          >All {tabs!.length}</button>
        )}
      </div>
    </div>
  );
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function tryParse(json: string): any {
  try { return JSON.parse(json); } catch { return null; }
}

function MarkdownText({ text }: { text: string }) {
  const lines = text.split('\n');
  return (
    <>
      {lines.map((line, i) => (
        <span key={i}>
          {parseLine(line)}
          {i < lines.length - 1 && <br />}
        </span>
      ))}
    </>
  );
}

function parseLine(line: string): React.ReactNode[] {
  const regex = /(\*\*(.+?)\*\*|\*(.+?)\*|`(.+?)`)/g;
  const parts: React.ReactNode[] = [];
  let last = 0, match;
  while ((match = regex.exec(line)) !== null) {
    if (match.index > last) parts.push(line.slice(last, match.index));
    if (match[0].startsWith('**'))     parts.push(<strong key={match.index}>{match[2]}</strong>);
    else if (match[0].startsWith('*')) parts.push(<em key={match.index}>{match[3]}</em>);
    else                               parts.push(<code key={match.index} style={{ background: 'rgba(0,0,0,0.07)', borderRadius: 3, padding: '0 3px', fontSize: '0.9em' }}>{match[4]}</code>);
    last = match.index + match[0].length;
  }
  if (last < line.length) parts.push(line.slice(last));
  return parts;
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const btnStyle: React.CSSProperties = {
  background: 'none', border: 'none', cursor: 'pointer', padding: '2px 5px', lineHeight: 1,
};

const actionBtn: React.CSSProperties = {
  padding: '6px 14px', border: 'none', borderRadius: 8,
  color: 'white', fontWeight: 600, cursor: 'pointer', flexShrink: 0,
};

const userBubble: React.CSSProperties = {
  alignSelf: 'flex-end',
  background: 'rgba(25,118,210,0.8)', color: 'white',
  padding: '7px 12px', borderRadius: '14px 14px 3px 14px',
  maxWidth: '85%', lineHeight: 1.5, whiteSpace: 'pre-wrap',
};

const aiBubble: React.CSSProperties = {
  alignSelf: 'flex-start',
  background: 'white', border: '1px solid rgba(0,0,0,0.07)', color: '#111',
  padding: '7px 12px', borderRadius: '14px 14px 14px 3px',
  maxWidth: '92%', lineHeight: 1.65, whiteSpace: 'pre-wrap',
};
