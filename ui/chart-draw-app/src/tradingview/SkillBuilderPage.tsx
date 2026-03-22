import React, { useState, useEffect, useRef, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import type { CopilotSkill, AiAssistResponse } from './copilotTypes';
import {
  getSkills, getSkill, createSkill, updateSkill, deleteSkill,
  seedDemoSkills, previewSkillPrompt, orchestratorPrompt, aiAssistSkill,
} from './copilotApi';

// ─── Constants ────────────────────────────────────────────────────────────────

const CATEGORIES = ['WAVE', 'PATTERN', 'CONFIRMATION', 'OVERRIDE'] as const;

const CATEGORY_ICONS: Record<string, string> = {
  WAVE: '〰', PATTERN: '◆', CONFIRMATION: '✓', OVERRIDE: '⚠',
};

const SECTIONS: { key: keyof CopilotSkill; label: string; hint: string }[] = [
  { key: 'identificationRules',   label: '1. Identification Rules',   hint: 'How to spot this pattern on the chart' },
  { key: 'stageDetection',        label: '2. Stage Detection',        hint: 'Current stage within the pattern' },
  { key: 'entryRules',            label: '3. Entry Rules',            hint: 'Anticipatory and confirmation entries with SL/TP' },
  { key: 'indicatorRules',        label: '4. Indicator Rules',        hint: 'What MACD, RSI, Stochastic must show per stage' },
  { key: 'invalidationRules',     label: '5. Invalidation Rules',     hint: 'Price action that kills this setup' },
  { key: 'ambiguityQuestions',    label: '6. Ambiguity Questions',    hint: 'Questions to ask the expert when uncertain' },
  { key: 'crossVerificationRules',label: '7. Cross-Verification',     hint: 'What must be true on other timeframes' },
];

const EMPTY_SKILL: Partial<CopilotSkill> = {
  skillKey: '', name: '', description: '', category: 'PATTERN',
  identificationRules: '', stageDetection: '', entryRules: '',
  indicatorRules: '', invalidationRules: '', ambiguityQuestions: '',
  crossVerificationRules: '', isActive: true,
};

type NavSelection = { type: 'orchestrator' } | { type: 'skill'; id: number | 'new' };
type PageMode = 'view' | 'edit';

interface ChatMessage { role: 'user' | 'assistant'; content: string; suggestedFields?: Record<string, string> }

// ─── Client-side prompt compiler (mirrors Java CopilotSkillService.buildSkillPrompt) ───

function compileSkillPrompt(skill: Partial<CopilotSkill>): string {
  const sec = (header: string, content?: string) =>
    `--- ${header} ---\n${content?.trim() || '(Not yet populated)'}\n\n`;
  return (
    `=== SKILL: ${skill.name || '(unnamed)'} ===\n` +
    `Category: ${skill.category || ''}\n\n` +
    sec('1. IDENTIFICATION RULES',    skill.identificationRules) +
    sec('2. STAGE DETECTION',         skill.stageDetection) +
    sec('3. ENTRY RULES PER STAGE',   skill.entryRules) +
    sec('4. INDICATOR RULES PER STAGE', skill.indicatorRules) +
    sec('5. INVALIDATION RULES',      skill.invalidationRules) +
    sec('6. AMBIGUITY QUESTIONS',     skill.ambiguityQuestions) +
    sec('7. CROSS-VERIFICATION RULES', skill.crossVerificationRules)
  );
}

// ─── Component ────────────────────────────────────────────────────────────────

export default function SkillBuilderPage() {
  const navigate = useNavigate();

  // Nav + mode
  const [nav, setNav] = useState<NavSelection | null>(null);
  const [mode, setMode] = useState<PageMode>('view');

  // Skills data
  const [skills, setSkills] = useState<CopilotSkill[]>([]);
  const [loadingSkills, setLoadingSkills] = useState(false);
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());

  // Orchestrator
  const [orchPrompt, setOrchPrompt] = useState<string | null>(null);

  // Skill form
  const [form, setForm] = useState<Partial<CopilotSkill>>(EMPTY_SKILL);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  // Live preview (client-side, updates as form changes)
  const compiledPreview = compileSkillPrompt(form);

  // AI chat
  const [chat, setChat] = useState<ChatMessage[]>([]);
  const [chatInput, setChatInput] = useState('');
  const [chatLoading, setChatLoading] = useState(false);
  const chatBottomRef = useRef<HTMLDivElement>(null);

  // ─── Data loading ───────────────────────────────────────────────────────────

  const loadSkills = useCallback(async () => {
    setLoadingSkills(true);
    try {
      setSkills(await getSkills());
    } catch (e) {
      setError(String(e));
    } finally {
      setLoadingSkills(false);
    }
  }, []);

  useEffect(() => { loadSkills(); }, [loadSkills]);

  useEffect(() => {
    chatBottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [chat]);

  // ─── Navigation ────────────────────────────────────────────────────────────

  const selectOrchestrator = async () => {
    setNav({ type: 'orchestrator' });
    setMode('view');
    setError(null);
    if (!orchPrompt) {
      try {
        const res = await orchestratorPrompt();
        setOrchPrompt(res.prompt);
      } catch (e) {
        setError(String(e));
      }
    }
  };

  const selectSkill = async (id: number) => {
    setNav({ type: 'skill', id });
    setMode('view');
    setError(null);
    setSuccess(null);
    setChat([]);
    try {
      setForm(await getSkill(id));
    } catch (e) {
      setError(String(e));
    }
  };

  const newSkill = () => {
    setNav({ type: 'skill', id: 'new' });
    setMode('edit');
    setForm(EMPTY_SKILL);
    setChat([]);
    setError(null);
    setSuccess(null);
  };

  const toggleCategory = (cat: string) =>
    setCollapsed(prev => {
      const next = new Set(prev);
      next.has(cat) ? next.delete(cat) : next.add(cat);
      return next;
    });

  // ─── Skill CRUD ─────────────────────────────────────────────────────────────

  const save = async () => {
    if (nav?.type !== 'skill') return;
    setSaving(true);
    setError(null);
    setSuccess(null);
    try {
      if (nav.id === 'new') {
        const created = await createSkill(form);
        setSkills(prev => [...prev, created]);
        setNav({ type: 'skill', id: created.id });
        setForm(created);
        setSuccess('Skill created.');
      } else {
        const updated = await updateSkill(nav.id, form);
        setSkills(prev => prev.map(s => s.id === updated.id ? updated : s));
        setForm(updated);
        setSuccess('Skill saved.');
      }
      setMode('view');
    } catch (e) {
      setError(String(e));
    } finally {
      setSaving(false);
    }
  };

  const remove = async () => {
    if (nav?.type !== 'skill' || typeof nav.id !== 'number') return;
    if (!confirm('Delete this skill?')) return;
    try {
      await deleteSkill(nav.id);
      setSkills(prev => prev.filter(s => s.id !== nav.id));
      setNav(null);
      setForm(EMPTY_SKILL);
    } catch (e) {
      setError(String(e));
    }
  };

  const seed = async () => {
    try {
      await seedDemoSkills();
      await loadSkills();
      setSuccess('Demo skills seeded.');
    } catch (e) {
      setError(String(e));
    }
  };

  const setField = (key: keyof CopilotSkill, value: string | boolean) =>
    setForm(prev => ({ ...prev, [key]: value }));

  // ─── AI Chat ────────────────────────────────────────────────────────────────

  const sendChat = async () => {
    const msg = chatInput.trim();
    if (!msg || chatLoading) return;
    setChatInput('');
    setChat(prev => [...prev, { role: 'user', content: msg }]);
    setChatLoading(true);
    try {
      const res: AiAssistResponse = await aiAssistSkill(msg, form);
      setChat(prev => [...prev, {
        role: 'assistant',
        content: res.reply,
        suggestedFields: res.suggestedFields && Object.keys(res.suggestedFields).length > 0
          ? res.suggestedFields : undefined,
      }]);
    } catch (e) {
      setChat(prev => [...prev, { role: 'assistant', content: `Error: ${e}` }]);
    } finally {
      setChatLoading(false);
    }
  };

  const applyFields = (fields: Record<string, string>) => {
    setForm(prev => ({ ...prev, ...fields }));
    setSuccess('AI suggestions applied to fields.');
  };

  // ─── Render ─────────────────────────────────────────────────────────────────

  const skillsById = Object.fromEntries(skills.map(s => [s.id, s]));
  const currentSkill = nav?.type === 'skill' && nav.id !== 'new'
    ? (skillsById[nav.id] ?? null) : null;

  return (
    <div style={{ minHeight: '100vh', background: '#f0f2f5', display: 'flex', flexDirection: 'column' }}>

      {/* ── Header ── */}
      <div style={{ background: '#1a237e', color: '#fff', padding: '10px 20px', display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexShrink: 0 }}>
        <h2 style={{ margin: 0, fontSize: 17, fontWeight: 700 }}>🧠 Skill System Builder</h2>
        <div style={{ display: 'flex', gap: 8 }}>
          <button onClick={seed} style={headerBtn}>+ Seed Demos</button>
          <button onClick={() => navigate('/copilot')} style={headerBtn}>Co-Pilot</button>
          <button onClick={() => navigate('/')} style={headerBtn}>← Dashboard</button>
        </div>
      </div>

      <div style={{ display: 'flex', flex: 1, overflow: 'hidden' }}>

        {/* ── Left Nav ── */}
        <div style={{ width: 240, background: '#fff', borderRight: '1px solid #e0e0e0', display: 'flex', flexDirection: 'column', overflow: 'hidden', flexShrink: 0 }}>

          {/* Orchestrator */}
          <div
            onClick={selectOrchestrator}
            style={{
              padding: '12px 14px', cursor: 'pointer', borderBottom: '1px solid #eee',
              background: nav?.type === 'orchestrator' ? '#e8eaf6' : 'transparent',
              borderLeft: nav?.type === 'orchestrator' ? '3px solid #3f51b5' : '3px solid transparent',
              display: 'flex', alignItems: 'center', gap: 8,
            }}
          >
            <span style={{ fontSize: 18 }}>🎯</span>
            <div>
              <div style={{ fontWeight: 700, fontSize: 13, color: '#1a237e' }}>Orchestrator</div>
              <div style={{ fontSize: 10, color: '#888' }}>Skill selection logic</div>
            </div>
          </div>

          <div style={{ fontSize: 10, fontWeight: 700, color: '#aaa', padding: '8px 14px 4px', letterSpacing: 1 }}>SKILLS</div>

          {/* Skill list grouped by category */}
          <div style={{ flex: 1, overflowY: 'auto' }}>
            {loadingSkills && <div style={{ padding: 12, color: '#aaa', fontSize: 12 }}>Loading…</div>}

            {CATEGORIES.map(cat => {
              const catSkills = skills.filter(s => s.category === cat);
              const isOpen = !collapsed.has(cat);
              return (
                <div key={cat}>
                  <div
                    onClick={() => toggleCategory(cat)}
                    style={{ padding: '6px 14px', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 6, background: '#fafafa', borderBottom: '1px solid #f0f0f0' }}
                  >
                    <span style={{ fontSize: 10, color: '#aaa', width: 10 }}>{isOpen ? '▼' : '▶'}</span>
                    <span style={{ fontSize: 13 }}>{CATEGORY_ICONS[cat]}</span>
                    <span style={{ fontSize: 11, fontWeight: 700, color: '#555' }}>{cat}</span>
                    <span style={{ marginLeft: 'auto', fontSize: 10, color: '#bbb' }}>{catSkills.length}</span>
                  </div>
                  {isOpen && catSkills.map(skill => {
                    const isSelected = nav?.type === 'skill' && nav.id === skill.id;
                    return (
                      <div
                        key={skill.id}
                        onClick={() => selectSkill(skill.id)}
                        style={{
                          padding: '8px 14px 8px 28px', cursor: 'pointer',
                          background: isSelected ? '#e3f2fd' : 'transparent',
                          borderLeft: isSelected ? '3px solid #1976d2' : '3px solid transparent',
                          borderBottom: '1px solid #f8f8f8',
                        }}
                      >
                        <div style={{ fontWeight: 600, fontSize: 12, color: '#222' }}>{skill.name}</div>
                        <div style={{ fontSize: 10, color: '#aaa', marginTop: 1 }}>
                          {skill.skillKey}
                          {skill.isSystemSeed && <span style={{ marginLeft: 4, color: '#1976d2' }}>⚡</span>}
                          {!skill.isActive && <span style={{ marginLeft: 4, color: '#bbb' }}>off</span>}
                        </div>
                      </div>
                    );
                  })}
                  {isOpen && catSkills.length === 0 && (
                    <div style={{ padding: '6px 28px', fontSize: 11, color: '#ccc', fontStyle: 'italic' }}>empty</div>
                  )}
                </div>
              );
            })}
          </div>

          {/* New skill button */}
          <div style={{ padding: 10, borderTop: '1px solid #eee' }}>
            <button
              onClick={newSkill}
              style={{ width: '100%', padding: '8px 0', background: '#1976d2', color: '#fff', border: 'none', borderRadius: 6, fontWeight: 600, fontSize: 13, cursor: 'pointer' }}
            >
              + New Skill
            </button>
          </div>
        </div>

        {/* ── Main Area ── */}
        <div style={{ flex: 1, overflow: 'hidden', display: 'flex', flexDirection: 'column' }}>

          {/* Empty state */}
          {!nav && (
            <div style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#bbb', fontSize: 14 }}>
              Select the Orchestrator or a Skill from the left panel.
            </div>
          )}

          {/* ── Orchestrator View ── */}
          {nav?.type === 'orchestrator' && (
            <div style={{ flex: 1, overflow: 'auto', padding: 28 }}>
              <div style={{ marginBottom: 6 }}>
                <h2 style={{ margin: 0, fontSize: 20, fontWeight: 700, color: '#1a237e' }}>🎯 Orchestrator</h2>
                <p style={{ margin: '6px 0 0', color: '#777', fontSize: 13 }}>
                  These static rules govern how the system selects which skills to invoke for each investigation.
                  The dynamic skill list and investigation context are appended at runtime.
                </p>
              </div>
              {orchPrompt ? (
                <div style={codeBlock}>
                  <div style={{ fontWeight: 700, color: '#90caf9', marginBottom: 10, fontSize: 12 }}>ORCHESTRATOR INSTRUCTIONS</div>
                  {orchPrompt}
                </div>
              ) : (
                <div style={{ color: '#aaa', fontSize: 13, marginTop: 20 }}>Loading…</div>
              )}
            </div>
          )}

          {/* ── Skill View Mode ── */}
          {nav?.type === 'skill' && mode === 'view' && currentSkill && (
            <div style={{ flex: 1, overflow: 'auto', padding: 28 }}>
              {error && <div style={alertStyle('#c62828', '#ffebee')}>{error}</div>}
              {success && <div style={alertStyle('#388e3c', '#e8f5e9')}>{success}</div>}

              <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: 16 }}>
                <div>
                  <h2 style={{ margin: 0, fontSize: 20, fontWeight: 700, color: '#222' }}>{currentSkill.name}</h2>
                  <div style={{ color: '#777', fontSize: 13, marginTop: 4 }}>{currentSkill.description}</div>
                  <div style={{ marginTop: 8, display: 'flex', gap: 8, alignItems: 'center' }}>
                    <span style={{ background: '#e3f2fd', color: '#1565c0', padding: '2px 10px', borderRadius: 12, fontSize: 11, fontWeight: 700 }}>
                      {CATEGORY_ICONS[currentSkill.category]} {currentSkill.category}
                    </span>
                    <span style={{ fontSize: 11, color: '#888' }}>{currentSkill.skillKey}</span>
                    {!currentSkill.isActive && <span style={{ background: '#f5f5f5', color: '#aaa', padding: '2px 8px', borderRadius: 10, fontSize: 11 }}>inactive</span>}
                    {currentSkill.isSystemSeed && <span style={{ background: '#e8f5e9', color: '#388e3c', padding: '2px 8px', borderRadius: 10, fontSize: 11 }}>⚡ system</span>}
                  </div>
                </div>
                <div style={{ display: 'flex', gap: 8 }}>
                  <button onClick={() => setMode('edit')} style={primaryBtn}>✏ Edit</button>
                  <button onClick={remove} style={{ ...secondaryBtn, color: '#c62828', borderColor: '#c62828' }}>🗑 Delete</button>
                </div>
              </div>

              <div style={codeBlock}>
                <div style={{ fontWeight: 700, color: '#90caf9', marginBottom: 10, fontSize: 12 }}>COMPILED SKILL PROMPT</div>
                {compileSkillPrompt(form)}
              </div>
            </div>
          )}

          {/* ── Skill Edit Mode (3 columns) ── */}
          {nav?.type === 'skill' && mode === 'edit' && (
            <div style={{ flex: 1, display: 'flex', overflow: 'hidden' }}>

              {/* Col 1: AI Chat */}
              <div style={{ width: 280, borderRight: '1px solid #e0e0e0', background: '#fff', display: 'flex', flexDirection: 'column', flexShrink: 0 }}>
                <div style={{ padding: '10px 14px', borderBottom: '1px solid #eee', fontWeight: 700, fontSize: 13, color: '#1a237e', background: '#f5f7ff' }}>
                  🤖 AI Assistant
                </div>
                <div style={{ padding: '8px 10px', background: '#fffde7', borderBottom: '1px solid #fff176', fontSize: 11, color: '#795548' }}>
                  Describe what you want the skill to do. AI will suggest field text.
                </div>

                {/* Chat history */}
                <div style={{ flex: 1, overflowY: 'auto', padding: '10px', display: 'flex', flexDirection: 'column', gap: 8 }}>
                  {chat.length === 0 && (
                    <div style={{ color: '#bbb', fontSize: 12, textAlign: 'center', paddingTop: 20 }}>
                      Ask the AI to help write any field.<br />
                      e.g. "Help me write identification rules for an ascending triangle"
                    </div>
                  )}
                  {chat.map((msg, i) => (
                    <div key={i} style={{ display: 'flex', flexDirection: 'column', alignItems: msg.role === 'user' ? 'flex-end' : 'flex-start' }}>
                      <div style={{
                        maxWidth: '90%', padding: '7px 10px', borderRadius: 10, fontSize: 12, lineHeight: 1.5,
                        background: msg.role === 'user' ? '#1976d2' : '#37474f',
                        color: '#fff',
                        borderBottomRightRadius: msg.role === 'user' ? 2 : 10,
                        borderBottomLeftRadius: msg.role === 'assistant' ? 2 : 10,
                      }}>
                        {msg.content}
                      </div>
                      {msg.suggestedFields && (
                        <button
                          onClick={() => applyFields(msg.suggestedFields!)}
                          style={{ marginTop: 4, fontSize: 11, padding: '3px 10px', background: '#43a047', color: '#fff', border: 'none', borderRadius: 6, cursor: 'pointer' }}
                        >
                          ✓ Apply {Object.keys(msg.suggestedFields).length} suggestion(s)
                        </button>
                      )}
                    </div>
                  ))}
                  {chatLoading && (
                    <div style={{ color: '#aaa', fontSize: 12, fontStyle: 'italic' }}>AI is thinking…</div>
                  )}
                  <div ref={chatBottomRef} />
                </div>

                {/* Chat input */}
                <div style={{ padding: 10, borderTop: '1px solid #eee' }}>
                  <textarea
                    rows={3}
                    value={chatInput}
                    onChange={e => setChatInput(e.target.value)}
                    onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendChat(); } }}
                    placeholder="Describe what you want… (Enter to send)"
                    disabled={chatLoading}
                    style={{ width: '100%', boxSizing: 'border-box', border: '1px solid #ddd', borderRadius: 6, padding: '6px 8px', fontSize: 12, resize: 'none', fontFamily: 'inherit' }}
                  />
                  <button
                    onClick={sendChat}
                    disabled={chatLoading || !chatInput.trim()}
                    style={{ marginTop: 6, width: '100%', padding: '6px 0', background: chatLoading ? '#aaa' : '#1976d2', color: '#fff', border: 'none', borderRadius: 6, fontWeight: 600, fontSize: 12, cursor: chatLoading ? 'default' : 'pointer' }}
                  >
                    {chatLoading ? 'Thinking…' : 'Ask AI ↵'}
                  </button>
                </div>
              </div>

              {/* Col 2: Field Editors */}
              <div style={{ flex: 1, overflowY: 'auto', padding: '16px 20px', background: '#f8f9fa' }}>
                {error && <div style={alertStyle('#c62828', '#ffebee')}>{error}</div>}
                {success && <div style={alertStyle('#388e3c', '#e8f5e9')}>{success}</div>}

                {/* Metadata */}
                <div style={{ background: '#fff', borderRadius: 8, padding: '14px 16px', marginBottom: 16, border: '1px solid #e0e0e0' }}>
                  <div style={{ fontWeight: 700, fontSize: 13, color: '#333', marginBottom: 12 }}>Skill Metadata</div>
                  <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, marginBottom: 12 }}>
                    <MetaField label="Name *" value={form.name ?? ''} onChange={v => setField('name', v)} />
                    <MetaField label="Skill Key *" value={form.skillKey ?? ''} onChange={v => setField('skillKey', v)} hint="e.g. wave_4" />
                    <MetaField label="Category" value={form.category ?? ''} onChange={v => setField('category', v)} hint="WAVE / PATTERN / CONFIRMATION / OVERRIDE" />
                    <MetaField label="Description" value={form.description ?? ''} onChange={v => setField('description', v)} />
                  </div>
                  <label style={{ fontSize: 12, fontWeight: 600, color: '#555', cursor: 'pointer' }}>
                    <input type="checkbox" checked={form.isActive ?? true} onChange={e => setField('isActive', e.target.checked)} style={{ marginRight: 6 }} />
                    Active (included in orchestration)
                  </label>
                </div>

                {/* 7 fields */}
                {SECTIONS.map(s => (
                  <div key={s.key as string} style={{ background: '#fff', borderRadius: 8, padding: '12px 16px', marginBottom: 12, border: '1px solid #e0e0e0' }}>
                    <label style={{ display: 'block', fontWeight: 700, fontSize: 12, color: '#333', marginBottom: 3 }}>
                      {s.label}
                      <span style={{ fontWeight: 400, color: '#aaa', marginLeft: 8 }}>{s.hint}</span>
                    </label>
                    <textarea
                      rows={5}
                      value={String(form[s.key] ?? '')}
                      onChange={e => setField(s.key, e.target.value)}
                      style={{ width: '100%', boxSizing: 'border-box', padding: '7px 9px', border: '1px solid #e0e0e0', borderRadius: 6, fontSize: 12, fontFamily: 'monospace', lineHeight: 1.6, resize: 'vertical' }}
                    />
                  </div>
                ))}

                {/* Action bar */}
                <div style={{ display: 'flex', gap: 10, paddingBottom: 24 }}>
                  <button onClick={save} disabled={saving} style={primaryBtn}>
                    {saving ? 'Saving…' : nav.id === 'new' ? '+ Create Skill' : '💾 Save'}
                  </button>
                  {nav.id !== 'new' && (
                    <button onClick={() => setMode('view')} style={secondaryBtn}>Cancel</button>
                  )}
                </div>
              </div>

              {/* Col 3: Live Preview */}
              <div style={{ width: 320, borderLeft: '1px solid #e0e0e0', display: 'flex', flexDirection: 'column', flexShrink: 0 }}>
                <div style={{ padding: '10px 14px', borderBottom: '1px solid #1a1a2e', fontWeight: 700, fontSize: 13, color: '#90caf9', background: '#1a1a2e' }}>
                  Live Prompt Preview
                </div>
                <div style={{ flex: 1, overflowY: 'auto', ...codeBlock, borderRadius: 0, margin: 0, fontSize: 11, lineHeight: 1.55 }}>
                  {compiledPreview}
                </div>
              </div>

            </div>
          )}

        </div>
      </div>
    </div>
  );
}

// ─── Sub-components ───────────────────────────────────────────────────────────

function MetaField({ label, value, onChange, hint }: { label: string; value: string; onChange: (v: string) => void; hint?: string }) {
  return (
    <div>
      <label style={{ display: 'block', fontSize: 11, fontWeight: 600, color: '#555', marginBottom: 3 }}>
        {label} {hint && <span style={{ fontWeight: 400, color: '#bbb' }}>{hint}</span>}
      </label>
      <input
        value={value}
        onChange={e => onChange(e.target.value)}
        style={{ width: '100%', boxSizing: 'border-box', border: '1px solid #ddd', borderRadius: 6, padding: '5px 8px', fontSize: 12 }}
      />
    </div>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const codeBlock: React.CSSProperties = {
  background: '#1a1a2e', color: '#e0e0e0', borderRadius: 8,
  padding: 16, fontSize: 12, fontFamily: 'monospace',
  lineHeight: 1.6, whiteSpace: 'pre-wrap', overflowY: 'auto',
  flex: 1,
};

function alertStyle(color: string, bg: string): React.CSSProperties {
  return { background: bg, border: `1px solid ${color}`, color, borderRadius: 6, padding: '8px 12px', marginBottom: 12, fontSize: 12 };
}

const primaryBtn: React.CSSProperties = {
  padding: '8px 20px', background: '#1976d2', color: '#fff', border: 'none',
  borderRadius: 6, cursor: 'pointer', fontWeight: 700, fontSize: 13,
};

const secondaryBtn: React.CSSProperties = {
  padding: '8px 16px', background: '#fff', color: '#555', border: '1px solid #ccc',
  borderRadius: 6, cursor: 'pointer', fontWeight: 500, fontSize: 13,
};

const headerBtn: React.CSSProperties = {
  padding: '5px 12px', background: 'rgba(255,255,255,0.15)', color: '#fff',
  border: '1px solid rgba(255,255,255,0.3)', borderRadius: 6, cursor: 'pointer', fontSize: 13,
};
