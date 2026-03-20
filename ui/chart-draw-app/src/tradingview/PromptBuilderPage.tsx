import React, { useState, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { buildPrompt, ConversationMessage } from './promptsApi';

import {
  CustomPrompt,
  loadCustomPrompts, saveCustomPrompts,
} from './promptTypes';

interface ConvEntry {
  role: 'user' | 'assistant';
  content: string;
}

interface Props {
  onClose?: () => void;  // provided when opened as overlay; absent when opened as route
}

export default function PromptBuilderPage({ onClose }: Props = {}) {
  const navigate = useNavigate();

  const handleClose = () => {
    if (onClose) onClose();
    else navigate('/chart');
  };

  // Conversation panel
  const [conv, setConv] = useState<ConvEntry[]>([]);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Built prompt panel
  const [builtPrompt, setBuiltPrompt] = useState('');
  const [promptName, setPromptName] = useState('');
  const [saveSuccess, setSaveSuccess] = useState(false);

  // Saved prompts list
  const [savedPrompts, setSavedPrompts] = useState<CustomPrompt[]>(() => loadCustomPrompts());

  const convBottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    convBottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [conv, loading]);

  const send = async () => {
    const msg = input.trim();
    if (!msg || loading) return;

    const newConv: ConvEntry[] = [...conv, { role: 'user', content: msg }];
    setConv(newConv);
    setInput('');
    setLoading(true);
    setError(null);

    try {
      const history: ConversationMessage[] = newConv.slice(0, -1).map(c => ({
        role: c.role,
        content: c.content,
      }));

      const res = await buildPrompt(history, msg);

      setConv(prev => [...prev, { role: 'assistant', content: res.message }]);
      if (res.extractedPrompt) {
        setBuiltPrompt(res.extractedPrompt);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Request failed');
    } finally {
      setLoading(false);
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !loading) { e.preventDefault(); send(); }
  };

  const savePrompt = () => {
    const name = promptName.trim();
    const text = builtPrompt.trim();
    if (!name) { setError('Please enter a name for the prompt.'); return; }
    if (!text) { setError('The prompt text is empty.'); return; }

    const newPrompt: CustomPrompt = {
      id: crypto.randomUUID(),
      name,
      promptText: text,
      createdAt: Date.now(),
    };
    const updated = [...savedPrompts, newPrompt];
    saveCustomPrompts(updated);
    setSavedPrompts(updated);
    setPromptName('');
    setError(null);
    setSaveSuccess(true);
    setTimeout(() => setSaveSuccess(false), 2500);
  };

  const deletePrompt = (id: string) => {
    const updated = savedPrompts.filter(p => p.id !== id);
    saveCustomPrompts(updated);
    setSavedPrompts(updated);
  };

  return (
    <div style={{
      display: 'flex', flexDirection: 'column', height: '100vh',
      background: '#0f1120', color: 'rgba(255,255,255,0.9)',
      fontFamily: 'inherit',
    }}>
      {/* Header */}
      <div style={{
        display: 'flex', alignItems: 'center', gap: 16,
        padding: '10px 20px',
        background: 'rgba(255,255,255,0.05)',
        borderBottom: '1px solid rgba(255,255,255,0.1)',
        flexShrink: 0,
      }}>
        <button
          onClick={handleClose}
          style={{
            background: 'none', border: '1px solid rgba(255,255,255,0.2)',
            color: 'rgba(255,255,255,0.7)', borderRadius: 6,
            padding: '4px 10px', cursor: 'pointer', fontSize: 13,
          }}
        >← Back to Chart</button>
        <span style={{ fontSize: 16, fontWeight: 700 }}>Prompt Builder</span>
        <span style={{ fontSize: 12, color: 'rgba(255,255,255,0.4)' }}>
          Describe the analysis focus you want, and I'll craft a system prompt for you.
        </span>
      </div>

      {/* 3-panel body */}
      <div style={{ display: 'flex', flex: 1, minHeight: 0, gap: 0 }}>

        {/* Panel 1: Conversation */}
        <div style={{
          flex: 1, display: 'flex', flexDirection: 'column',
          borderRight: '1px solid rgba(255,255,255,0.08)',
          minWidth: 0,
        }}>
          <div style={{ padding: '8px 14px', fontSize: 11, fontWeight: 700, color: 'rgba(255,255,255,0.35)', borderBottom: '1px solid rgba(255,255,255,0.06)', flexShrink: 0 }}>
            CONVERSATION
          </div>

          {/* Messages */}
          <div style={{ flex: 1, overflowY: 'auto', padding: '12px 14px', display: 'flex', flexDirection: 'column', gap: 10, minHeight: 0 }}>
            {conv.length === 0 && (
              <div style={{ color: 'rgba(255,255,255,0.25)', fontSize: 13, textAlign: 'center', padding: '40px 8px', lineHeight: 1.7 }}>
                Describe the analysis style or methodology you want.<br />e.g. "Focus on breakouts above key levels" or "I use Elliott Wave"
              </div>
            )}
            {conv.map((msg, i) => (
              <div key={i} style={msg.role === 'user' ? userBubble : aiBubble}>
                {msg.content}
              </div>
            ))}
            {loading && (
              <div style={aiBubble}><span style={{ color: '#ccc', letterSpacing: 4 }}>· · ·</span></div>
            )}
            {error && (
              <div style={{ padding: '6px 10px', background: 'rgba(255,152,0,0.12)', border: '1px solid rgba(255,152,0,0.3)', borderRadius: 8, color: '#ffb74d', fontSize: 12 }}>
                {error}
              </div>
            )}
            <div ref={convBottomRef} />
          </div>

          {/* Input */}
          <div style={{ padding: '10px 14px', borderTop: '1px solid rgba(255,255,255,0.08)', display: 'flex', gap: 8, flexShrink: 0 }}>
            <textarea
              rows={3}
              placeholder="Describe your analysis approach..."
              value={input}
              onChange={e => setInput(e.target.value)}
              onKeyDown={handleKeyDown}
              disabled={loading}
              style={{
                flex: 1, background: 'rgba(255,255,255,0.06)', border: '1px solid rgba(255,255,255,0.15)',
                borderRadius: 8, color: 'rgba(255,255,255,0.9)', padding: '8px 10px',
                resize: 'none', fontFamily: 'inherit', fontSize: 13, outline: 'none',
              }}
            />
            <button
              onClick={send}
              disabled={loading || !input.trim()}
              style={{
                ...actionBtn, background: '#1976d2', alignSelf: 'flex-end',
                opacity: loading || !input.trim() ? 0.35 : 1,
              }}
            >Send</button>
          </div>
        </div>

        {/* Panel 2: Built Prompt */}
        <div style={{
          width: 380, flexShrink: 0, display: 'flex', flexDirection: 'column',
          borderRight: '1px solid rgba(255,255,255,0.08)',
        }}>
          <div style={{ padding: '8px 14px', fontSize: 11, fontWeight: 700, color: 'rgba(255,255,255,0.35)', borderBottom: '1px solid rgba(255,255,255,0.06)', flexShrink: 0 }}>
            BUILT PROMPT
          </div>

          <div style={{ flex: 1, padding: '12px 14px', display: 'flex', flexDirection: 'column', gap: 10, minHeight: 0 }}>
            <textarea
              placeholder="The system prompt will appear here as you chat. You can edit it directly."
              value={builtPrompt}
              onChange={e => setBuiltPrompt(e.target.value)}
              style={{
                flex: 1, background: 'rgba(255,255,255,0.06)', border: '1px solid rgba(255,255,255,0.12)',
                borderRadius: 8, color: 'rgba(255,255,255,0.88)', padding: '10px 12px',
                resize: 'none', fontFamily: 'monospace', fontSize: 12, outline: 'none',
                lineHeight: 1.6,
              }}
            />
            <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
              <input
                type="text"
                placeholder="Prompt name, e.g. Breakout Focus"
                value={promptName}
                onChange={e => setPromptName(e.target.value)}
                onKeyDown={e => { if (e.key === 'Enter') savePrompt(); }}
                style={{
                  flex: 1, background: 'rgba(255,255,255,0.06)', border: '1px solid rgba(255,255,255,0.15)',
                  borderRadius: 6, color: 'rgba(255,255,255,0.9)', padding: '6px 10px',
                  fontFamily: 'inherit', fontSize: 13, outline: 'none',
                }}
              />
              <button
                onClick={savePrompt}
                disabled={!builtPrompt.trim() || !promptName.trim()}
                style={{
                  ...actionBtn, background: saveSuccess ? '#1b5e20' : '#2e7d32', flexShrink: 0,
                  opacity: !builtPrompt.trim() || !promptName.trim() ? 0.35 : 1,
                  transition: 'background 0.2s',
                }}
              >{saveSuccess ? 'Saved ✓' : 'Save'}</button>
            </div>
          </div>
        </div>

        {/* Panel 3: Saved Prompts */}
        <div style={{ width: 260, flexShrink: 0, display: 'flex', flexDirection: 'column' }}>
          <div style={{ padding: '8px 14px', fontSize: 11, fontWeight: 700, color: 'rgba(255,255,255,0.35)', borderBottom: '1px solid rgba(255,255,255,0.06)', flexShrink: 0 }}>
            SAVED PROMPTS
          </div>
          <div style={{ flex: 1, overflowY: 'auto', padding: '10px 14px', display: 'flex', flexDirection: 'column', gap: 8, minHeight: 0 }}>
            {savedPrompts.length === 0 && (
              <div style={{ color: 'rgba(255,255,255,0.25)', fontSize: 12, textAlign: 'center', padding: '20px 8px' }}>
                No saved prompts yet.
              </div>
            )}
            {savedPrompts.map(p => (
              <div key={p.id} style={{
                background: 'rgba(255,255,255,0.06)', border: '1px solid rgba(255,255,255,0.1)',
                borderRadius: 8, padding: '8px 10px',
              }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                  <span style={{ fontSize: 13, fontWeight: 600, color: 'rgba(255,255,255,0.85)' }}>{p.name}</span>
                  <button
                    onClick={() => deletePrompt(p.id)}
                    title="Delete"
                    style={{ background: 'none', border: 'none', color: 'rgba(255,100,100,0.6)', cursor: 'pointer', fontSize: 14, padding: '0 2px' }}
                  >×</button>
                </div>
                <div style={{ fontSize: 11, color: 'rgba(255,255,255,0.35)', marginTop: 3 }}>
                  {new Date(p.createdAt).toLocaleDateString()}
                </div>
                <button
                  onClick={() => { setBuiltPrompt(p.promptText); setPromptName(p.name); }}
                  style={{ marginTop: 6, fontSize: 11, color: '#90caf9', background: 'none', border: 'none', cursor: 'pointer', padding: 0 }}
                >Edit</button>
              </div>
            ))}
          </div>
        </div>

      </div>
    </div>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const actionBtn: React.CSSProperties = {
  padding: '7px 16px', border: 'none', borderRadius: 8,
  color: 'white', fontWeight: 600, cursor: 'pointer', flexShrink: 0, fontSize: 13,
};

const userBubble: React.CSSProperties = {
  alignSelf: 'flex-end',
  background: 'rgba(25,118,210,0.8)', color: 'white',
  padding: '7px 12px', borderRadius: '14px 14px 3px 14px',
  maxWidth: '85%', lineHeight: 1.5, whiteSpace: 'pre-wrap', fontSize: 13,
};

const aiBubble: React.CSSProperties = {
  alignSelf: 'flex-start',
  background: 'rgba(255,255,255,0.93)', border: '1px solid rgba(0,0,0,0.07)', color: '#111',
  padding: '7px 12px', borderRadius: '14px 14px 14px 3px',
  maxWidth: '92%', lineHeight: 1.65, whiteSpace: 'pre-wrap', fontSize: 13,
};
