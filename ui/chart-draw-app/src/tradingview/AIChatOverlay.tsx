import React, { useState, useEffect, useRef, useCallback } from 'react';
import {
  technicalChatAnalysis, multiChartChatAnalysis,
  ChatMode, MultiChartChatResponse,
} from './analysisApi';
import { WorkspaceTab } from './workspaceTypes';

interface Props {
  open: boolean;
  onToggle: () => void;
  symbol: string;
  timeframe: string;
  getChartState?: () => string;
  tabs?: WorkspaceTab[];
  activeTabId?: string;
}

interface ChatMessage {
  role: 'user' | 'ai';
  content: string;
  timestamp: number;
}

const INITIAL_W = 540;
const INITIAL_H = 560;
const GEOMETRY_KEY = 'ai_chat_overlay_geometry';
const FONT_SIZE_KEY = 'ai_chat_font_size';

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
  if (s && FONT_SIZE_ORDER.includes(s as FontSizeLevel)) return s as FontSizeLevel;
  return 'small';
}
function saveFontSize(f: FontSizeLevel) {
  try { localStorage.setItem(FONT_SIZE_KEY, f); } catch { /* ignore */ }
}

function loadGeometry() {
  try {
    const s = localStorage.getItem(GEOMETRY_KEY);
    if (s) return JSON.parse(s) as { x: number; y: number; w: number; h: number };
  } catch { /* ignore */ }
  return null;
}

function saveGeometry(g: { x: number; y: number; w: number; h: number }) {
  try { localStorage.setItem(GEOMETRY_KEY, JSON.stringify(g)); } catch { /* ignore */ }
}

export default function AIChatOverlay({ open, onToggle, symbol, timeframe, getChartState, tabs, activeTabId }: Props) {
  const [chatHistory, setChatHistory] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState('');
  const [mode, setMode] = useState<ChatMode>('VALIDATE');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [collapsed, setCollapsed] = useState(false);
  const [fontSizeLevel, setFontSizeLevel] = useState<FontSizeLevel>(loadFontSize);

  const cycleFont = () => {
    setFontSizeLevel(prev => {
      const next = FONT_SIZE_ORDER[(FONT_SIZE_ORDER.indexOf(prev) + 1) % FONT_SIZE_ORDER.length];
      saveFontSize(next);
      return next;
    });
  };

  const fs = FONT_SIZES[fontSizeLevel];

  // Position + size — restored from localStorage
  const savedGeo = loadGeometry();
  const [pos, setPos] = useState({
    x: savedGeo?.x ?? 24,
    y: savedGeo?.y ?? window.innerHeight - INITIAL_H - 24,
  });
  const [size, setSize] = useState({
    w: savedGeo?.w ?? INITIAL_W,
    h: savedGeo?.h ?? INITIAL_H,
  });

  const panelRef = useRef<HTMLDivElement>(null);
  const dragRef = useRef<{ startX: number; startY: number; startPosX: number; startPosY: number } | null>(null);

  const chatBottomRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const wasHiddenDuringLoad = useRef(false);

  // ─── Scroll to bottom on new messages ───────────────────────────────────
  useEffect(() => {
    if (!collapsed) chatBottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [chatHistory, loading]);

  // ─── Auto-reopen when AI responds while hidden ───────────────────────────
  useEffect(() => {
    if (loading && (!open || collapsed)) wasHiddenDuringLoad.current = true;
    if (!loading && wasHiddenDuringLoad.current) {
      wasHiddenDuringLoad.current = false;
      if (!open) onToggle();
      setCollapsed(false);
    }
  }, [loading]);

  useEffect(() => {
    if (open && !collapsed) setTimeout(() => inputRef.current?.focus(), 60);
  }, [open, collapsed]);

  // ─── Dragging ────────────────────────────────────────────────────────────
  const onMouseDownHeader = useCallback((e: React.MouseEvent) => {
    // Don't drag when clicking buttons
    if ((e.target as HTMLElement).tagName === 'BUTTON') return;
    dragRef.current = { startX: e.clientX, startY: e.clientY, startPosX: pos.x, startPosY: pos.y };
    e.preventDefault();
  }, [pos]);

  useEffect(() => {
    const onMove = (e: MouseEvent) => {
      if (!dragRef.current) return;
      const dx = e.clientX - dragRef.current.startX;
      const dy = e.clientY - dragRef.current.startY;
      setPos(prev => {
        const next = {
          x: Math.max(0, Math.min(window.innerWidth - 100, dragRef.current!.startPosX + dx)),
          y: Math.max(0, Math.min(window.innerHeight - 40, dragRef.current!.startPosY + dy)),
        };
        return next;
      });
    };
    const onUp = () => {
      if (dragRef.current && panelRef.current) {
        const rect = panelRef.current.getBoundingClientRect();
        setPos(prev => { saveGeometry({ x: prev.x, y: prev.y, w: rect.width, h: rect.height }); return prev; });
      }
      dragRef.current = null;
    };
    document.addEventListener('mousemove', onMove);
    document.addEventListener('mouseup', onUp);
    return () => {
      document.removeEventListener('mousemove', onMove);
      document.removeEventListener('mouseup', onUp);
    };
  }, []);

  // ─── ResizeObserver — persist size when user drags resize handle ─────────
  useEffect(() => {
    if (!panelRef.current) return;
    const obs = new ResizeObserver(entries => {
      for (const entry of entries) {
        const { width, height } = entry.contentRect;
        const w = Math.round(width);
        const h = Math.round(height);
        setSize({ w, h });
        setPos(prev => { saveGeometry({ x: prev.x, y: prev.y, w, h }); return prev; });
      }
    });
    obs.observe(panelRef.current);
    return () => obs.disconnect();
  }, [open]); // re-attach when panel mounts/unmounts

  // ─── Send message ────────────────────────────────────────────────────────
  const send = async (multiChart = false) => {
    if (!getChartState) { setError('Chart not ready'); return; }
    const userMessage = mode === 'VALIDATE' ? input.trim() : undefined;
    if (mode === 'VALIDATE' && !userMessage) return;

    const display = multiChart
      ? (mode === 'REASON' ? `Reason all ${tabs?.length} charts` : `Multi: ${userMessage}`)
      : (mode === 'REASON' ? 'Reason my drawings' : userMessage!);

    setChatHistory(prev => [...prev, { role: 'user', content: display, timestamp: Date.now() }]);
    if (userMessage) setInput('');
    setLoading(true);
    setError(null);

    try {
      const chartStateJson = getChartState();
      const p = tryParse(chartStateJson);
      let aiMessage: string;

      if (multiChart && tabs && tabs.length >= 2) {
        const charts = tabs.map(tab => ({
          label: tab.label, symbol: tab.symbol, timeframe: tab.timeframe,
          chartStateJson: tab.id === activeTabId ? chartStateJson : (tab.drawingsState || '{}'),
          visibleFrom: tab.id === activeTabId ? p?.visibleFrom : tab.visibleFrom,
          visibleTo: tab.id === activeTabId ? p?.visibleTo : tab.visibleTo,
        }));
        const res: MultiChartChatResponse = await multiChartChatAnalysis({ charts, userMessage, mode });
        aiMessage = res.message;
      } else {
        const res = await technicalChatAnalysis({
          symbol, timeframe, chartStateJson, userMessage, mode,
          visibleFrom: p?.visibleFrom, visibleTo: p?.visibleTo,
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

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter' && !loading) send(false);
    if (e.key === 'Escape') onToggle();
  };

  const hasMultiCharts = tabs && tabs.length >= 2;

  if (!open) return null;

  return (
    <div
      ref={panelRef}
      style={{
        position: 'fixed',
        left: pos.x,
        top: pos.y,
        width: size.w,
        maxWidth: 'calc(100vw - 48px)',
        maxHeight: 'calc(100vh - 48px)',
        minWidth: 280,
        minHeight: 42,
        height: collapsed ? 'auto' : size.h,
        resize: collapsed ? 'none' : 'both',
        overflow: 'hidden',
        display: 'flex',
        flexDirection: 'column',
        borderRadius: 12,
        zIndex: 9998,
        // Very transparent — chart fully visible through it
        background: 'rgba(255, 255, 255, 0.12)',
        backdropFilter: 'blur(6px)',
        WebkitBackdropFilter: 'blur(6px)',
        border: '1px solid rgba(255, 255, 255, 0.25)',
        boxShadow: '0 2px 12px rgba(0,0,0,0.08)',
      }}
    >
      {/* Header — drag handle */}
      <div
        style={{
          display: 'flex', justifyContent: 'space-between', alignItems: 'center',
          padding: '6px 10px', borderBottom: collapsed ? 'none' : '1px solid rgba(0,0,0,0.08)',
          cursor: 'move', flexShrink: 0, userSelect: 'none',
          background: 'rgba(255,255,255,0.18)',
        }}
        onMouseDown={onMouseDownHeader}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <span style={{ fontSize: 12, fontWeight: 700, color: '#333' }}>AI</span>
          <span style={{ fontSize: 11, color: '#999' }}>{symbol} · {timeframe}</span>
          {loading && <span style={{ width: 7, height: 7, borderRadius: '50%', background: '#1976d2', display: 'inline-block' }} />}
          {!loading && chatHistory.length > 0 && (
            <span style={{ background: 'rgba(25,118,210,0.12)', color: '#1565c0', borderRadius: 10, fontSize: 10, fontWeight: 700, padding: '1px 5px' }}>
              {chatHistory.length}
            </span>
          )}
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
          <button style={btnStyle} onClick={() => setMode('REASON')}
            title="Reason mode"
            className={mode === 'REASON' ? 'active' : ''}>
            <span style={{ color: mode === 'REASON' ? '#1976d2' : '#999', fontWeight: mode === 'REASON' ? 700 : 400, fontSize: 11 }}>Reason</span>
          </button>
          <button style={btnStyle} onClick={() => setMode('VALIDATE')} title="Validate mode">
            <span style={{ color: mode === 'VALIDATE' ? '#1976d2' : '#999', fontWeight: mode === 'VALIDATE' ? 700 : 400, fontSize: 11 }}>Validate</span>
          </button>
          <button style={{ ...btnStyle, fontSize: 10, color: '#aaa', fontWeight: 700, minWidth: 18 }} onClick={cycleFont} title={`Font size: ${fontSizeLevel} (click to cycle)`}>
            {fs.label}
          </button>
          <button style={{ ...btnStyle, fontSize: 11, color: '#aaa' }} onClick={() => setCollapsed(c => !c)} title="Collapse / Expand">
            {collapsed ? '▲' : '▼'}
          </button>
          <button style={{ ...btnStyle, fontSize: 16, color: '#bbb' }} onClick={onToggle} title="Hide (backtick)">×</button>
        </div>
      </div>

      {!collapsed && (
        <>
          {/* Messages */}
          <div style={{
            flex: 1, overflowY: 'auto', padding: '8px 10px',
            display: 'flex', flexDirection: 'column', gap: 7, minHeight: 0,
          }}>
            {chatHistory.length === 0 && (
              <div style={{ color: '#aaa', fontSize: fs.msg, textAlign: 'center', padding: '24px 8px', lineHeight: 1.6 }}>
                {mode === 'REASON'
                  ? 'Draw objects on the chart, then click Send.'
                  : 'Type a claim to validate against your drawings.'}
              </div>
            )}
            {chatHistory.map((msg, i) => (
              <div key={i} style={msg.role === 'user' ? { ...userBubble, fontSize: fs.msg } : { ...aiBubble, fontSize: fs.msg }}>
                {msg.role === 'ai' ? <MarkdownText text={msg.content} /> : msg.content}
              </div>
            ))}
            {loading && (
              <div style={aiBubble}>
                <span style={{ color: '#bbb', letterSpacing: 3 }}>· · ·</span>
              </div>
            )}
            <div ref={chatBottomRef} />
          </div>

          {error && (
            <div style={{ margin: '0 10px 6px', padding: '6px 10px', background: 'rgba(255,152,0,0.15)', border: '1px solid rgba(255,152,0,0.3)', borderRadius: 8, color: '#e65100', fontSize: 12, flexShrink: 0 }}>
              {error}
            </div>
          )}

          {/* Input row */}
          <div style={{ display: 'flex', gap: 6, padding: '7px 10px', borderTop: '1px solid rgba(255,255,255,0.2)', flexShrink: 0, alignItems: 'center', background: 'rgba(255,255,255,0.15)' }}>
            {mode === 'VALIDATE' ? (
              <input
                ref={inputRef}
                type="text"
                placeholder="Type claim... (Enter to send)"
                value={input}
                onChange={e => setInput(e.target.value)}
                onKeyDown={handleKeyDown}
                disabled={loading}
                style={{
                  flex: 1, minWidth: 0,
                  background: 'rgba(255,255,255,0.6)',
                  border: '1px solid rgba(0,0,0,0.12)',
                  borderRadius: 8, padding: '8px 12px',
                  fontSize: fs.input, color: '#222', outline: 'none',
                }}
              />
            ) : (
              <span style={{ flex: 1, color: '#bbb', fontSize: fs.input, fontStyle: 'italic' }}>
                Sends drawing context to AI for reasoning.
              </span>
            )}
            <button
              onClick={() => send(false)}
              disabled={loading || (mode === 'VALIDATE' && !input.trim())}
              style={{
                ...actionBtn, background: '#1976d2',
                opacity: loading || (mode === 'VALIDATE' && !input.trim()) ? 0.4 : 1,
              }}
            >Send</button>
            {hasMultiCharts && (
              <button
                onClick={() => send(true)}
                disabled={loading || (mode === 'VALIDATE' && !input.trim())}
                title={`All ${tabs!.length} charts`}
                style={{
                  ...actionBtn, background: '#7b1fa2',
                  opacity: loading || (mode === 'VALIDATE' && !input.trim()) ? 0.4 : 1,
                }}
              >All {tabs!.length}</button>
            )}
          </div>
        </>
      )}
    </div>
  );
}

function tryParse(json: string): any {
  try { return JSON.parse(json); } catch { return null; }
}

// Lightweight markdown renderer: **bold**, *italic*, `code`, line breaks
function MarkdownText({ text }: { text: string }) {
  const lines = text.split('\n');
  return (
    <>
      {lines.map((line, i) => {
        const parts = parseLine(line);
        return (
          <span key={i}>
            {parts}
            {i < lines.length - 1 && <br />}
          </span>
        );
      })}
    </>
  );
}

function parseLine(line: string): React.ReactNode[] {
  // Matches **bold**, *italic*, `code`
  const regex = /(\*\*(.+?)\*\*|\*(.+?)\*|`(.+?)`)/g;
  const parts: React.ReactNode[] = [];
  let last = 0;
  let match;
  while ((match = regex.exec(line)) !== null) {
    if (match.index > last) parts.push(line.slice(last, match.index));
    if (match[0].startsWith('**')) {
      parts.push(<strong key={match.index}>{match[2]}</strong>);
    } else if (match[0].startsWith('*')) {
      parts.push(<em key={match.index}>{match[3]}</em>);
    } else {
      parts.push(<code key={match.index} style={{ background: 'rgba(0,0,0,0.08)', borderRadius: 3, padding: '0 3px', fontSize: '11px' }}>{match[4]}</code>);
    }
    last = match.index + match[0].length;
  }
  if (last < line.length) parts.push(line.slice(last));
  return parts;
}

const btnStyle: React.CSSProperties = {
  background: 'none', border: 'none', cursor: 'pointer', padding: '2px 5px', lineHeight: 1,
};

const actionBtn: React.CSSProperties = {
  padding: '6px 12px', border: 'none', borderRadius: 8,
  color: 'white', fontSize: 12, fontWeight: 600, cursor: 'pointer', flexShrink: 0,
};

const userBubble: React.CSSProperties = {
  alignSelf: 'flex-end',
  background: 'rgba(25, 118, 210, 0.75)',
  color: 'white',
  padding: '6px 10px', borderRadius: '12px 12px 3px 12px',
  maxWidth: '85%', fontSize: 12, lineHeight: 1.5, whiteSpace: 'pre-wrap',
};

const aiBubble: React.CSSProperties = {
  alignSelf: 'flex-start',
  background: 'rgba(255, 255, 255, 0.82)',
  border: '1px solid rgba(0,0,0,0.06)',
  color: '#111',
  padding: '6px 10px', borderRadius: '12px 12px 12px 3px',
  maxWidth: '92%', fontSize: 12, lineHeight: 1.6, whiteSpace: 'pre-wrap',
};
