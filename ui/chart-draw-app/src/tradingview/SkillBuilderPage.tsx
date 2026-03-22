import React, { useState, useEffect, useRef, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import type { CopilotSkill, AiAssistResponse, OrchestratorConfig, OrchestratorValidateResult } from './copilotTypes';
import {
  getSkills, getSkill, createSkill, updateSkill, deleteSkill,
  seedDemoSkills, aiAssistSkill,
  getOrchestratorConfig, getOrchestratorDefault, saveOrchestratorConfig,
  validateOrchestratorConfig, resetOrchestratorConfig,
} from './copilotApi';

// ─── Constants ────────────────────────────────────────────────────────────────

const CATEGORIES = ['WAVE', 'PATTERN', 'CONFIRMATION', 'OVERRIDE'] as const;

const CATEGORY_ICONS: Record<string, string> = {
  WAVE: '〰', PATTERN: '◆', CONFIRMATION: '✓', OVERRIDE: '⚠',
};

const SECTIONS: { key: keyof CopilotSkill; label: string; hint: string }[] = [
  { key: 'identificationRules',   label: '1. Identification Rules',   hint: 'How to spot this pattern' },
  { key: 'stageDetection',        label: '2. Stage Detection',        hint: 'Current stage within the pattern' },
  { key: 'entryRules',            label: '3. Entry Rules',            hint: 'Entries with SL/TP per stage' },
  { key: 'indicatorRules',        label: '4. Indicator Rules',        hint: 'MACD, RSI, Stochastic per stage' },
  { key: 'invalidationRules',     label: '5. Invalidation Rules',     hint: 'Price action that kills the setup' },
  { key: 'ambiguityQuestions',    label: '6. Ambiguity Questions',    hint: 'Questions to ask when uncertain' },
  { key: 'crossVerificationRules',label: '7. Cross-Verification',     hint: 'Conditions on other timeframes' },
];

const SECTION_HEADERS: { key: keyof CopilotSkill; header: string }[] = [
  { key: 'identificationRules',    header: '1. IDENTIFICATION RULES' },
  { key: 'stageDetection',         header: '2. STAGE DETECTION' },
  { key: 'entryRules',             header: '3. ENTRY RULES PER STAGE' },
  { key: 'indicatorRules',         header: '4. INDICATOR RULES PER STAGE' },
  { key: 'invalidationRules',      header: '5. INVALIDATION RULES' },
  { key: 'ambiguityQuestions',     header: '6. AMBIGUITY QUESTIONS' },
  { key: 'crossVerificationRules', header: '7. CROSS-VERIFICATION RULES' },
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
interface AttachedFile { name: string; content: string }

// ─── Prompt compile / decompile ───────────────────────────────────────────────

function compileSkillPrompt(skill: Partial<CopilotSkill>): string {
  const sec = (header: string, content?: string) =>
    `--- ${header} ---\n${content?.trim() || '(Not yet populated)'}\n\n`;
  return (
    `=== SKILL: ${skill.name || '(unnamed)'} ===\n` +
    `Category: ${skill.category || ''}\n\n` +
    sec('1. IDENTIFICATION RULES',      skill.identificationRules) +
    sec('2. STAGE DETECTION',           skill.stageDetection) +
    sec('3. ENTRY RULES PER STAGE',     skill.entryRules) +
    sec('4. INDICATOR RULES PER STAGE', skill.indicatorRules) +
    sec('5. INVALIDATION RULES',        skill.invalidationRules) +
    sec('6. AMBIGUITY QUESTIONS',       skill.ambiguityQuestions) +
    sec('7. CROSS-VERIFICATION RULES',  skill.crossVerificationRules)
  );
}

/** Parse an edited compiled prompt back into skill fields. */
function decompileSkillPrompt(prompt: string): Partial<CopilotSkill> {
  const result: Partial<CopilotSkill> = {};

  const nameMatch = prompt.match(/=== SKILL: (.+?) ===/);
  if (nameMatch) result.name = nameMatch[1].trim();

  const catMatch = prompt.match(/^Category: (.+)$/m);
  if (catMatch) result.category = catMatch[1].trim() as CopilotSkill['category'];

  for (const { key, header } of SECTION_HEADERS) {
    const escaped = header.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const regex = new RegExp(`--- ${escaped} ---\\n([\\s\\S]*?)(?=\\n--- |$)`);
    const match = prompt.match(regex);
    if (match) {
      const content = match[1].trim();
      (result as Record<string, unknown>)[key as string] = content === '(Not yet populated)' ? '' : content;
    }
  }

  return result;
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
  const [orchConfig, setOrchConfig] = useState<OrchestratorConfig | null>(null);
  const [orchDraft, setOrchDraft] = useState<string>('');
  const [orchMode, setOrchMode] = useState<'view' | 'edit'>('view');
  const [orchSaving, setOrchSaving] = useState(false);
  const [orchValidating, setOrchValidating] = useState(false);
  const [orchValidResult, setOrchValidResult] = useState<OrchestratorValidateResult | null>(null);

  // Skill form
  const [form, setForm] = useState<Partial<CopilotSkill>>(EMPTY_SKILL);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  // Prompt modal
  const [showPrompt, setShowPrompt] = useState(false);
  const [promptDraft, setPromptDraft] = useState('');

  // AI chat
  const [chat, setChat] = useState<ChatMessage[]>([]);
  const [chatInput, setChatInput] = useState('');
  const [chatLoading, setChatLoading] = useState(false);
  const chatBottomRef = useRef<HTMLDivElement>(null);

  // File attachments
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [attachedFiles, setAttachedFiles] = useState<AttachedFile[]>([]);

  // ─── Data loading ───────────────────────────────────────────────────────────

  const loadSkills = useCallback(async () => {
    setLoadingSkills(true);
    try { setSkills(await getSkills()); }
    catch (e) { setError(String(e)); }
    finally { setLoadingSkills(false); }
  }, []);

  useEffect(() => { loadSkills(); }, [loadSkills]);

  useEffect(() => {
    chatBottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [chat]);

  // ─── Prompt modal ───────────────────────────────────────────────────────────

  const openPrompt = () => {
    setPromptDraft(compileSkillPrompt(form));
    setShowPrompt(true);
  };

  const applyPromptToFields = () => {
    const parsed = decompileSkillPrompt(promptDraft);
    setForm(prev => ({ ...prev, ...parsed }));
    if (mode === 'view') setMode('edit');
    setShowPrompt(false);
    setSuccess('Prompt applied — fields updated. Review and save when ready.');
  };

  // ─── File attachment ─────────────────────────────────────────────────────────

  const handleFileAttach = (e: React.ChangeEvent<HTMLInputElement>) => {
    Array.from(e.target.files || []).forEach(file => {
      const reader = new FileReader();
      reader.onload = ev => {
        const content = ev.target?.result as string;
        setAttachedFiles(prev => [...prev, { name: file.name, content }]);
      };
      reader.readAsText(file);
    });
    e.target.value = '';
  };

  const removeFile = (idx: number) => setAttachedFiles(prev => prev.filter((_, i) => i !== idx));

  // ─── Navigation ─────────────────────────────────────────────────────────────

  const selectOrchestrator = async () => {
    setNav({ type: 'orchestrator' });
    setMode('view');
    setOrchMode('view');
    setOrchValidResult(null);
    setError(null);
    if (!orchConfig) {
      try {
        const cfg = await getOrchestratorConfig();
        setOrchConfig(cfg);
        setOrchDraft(cfg.instructions);
      } catch (e) { setError(String(e)); }
    }
  };

  const selectSkill = async (id: number) => {
    setNav({ type: 'skill', id });
    setMode('view');
    setError(null); setSuccess(null);
    setChat([]); setAttachedFiles([]);
    try { setForm(await getSkill(id)); }
    catch (e) { setError(String(e)); }
  };

  const newSkill = () => {
    setNav({ type: 'skill', id: 'new' });
    setMode('edit');
    setForm(EMPTY_SKILL);
    setChat([]); setAttachedFiles([]);
    setError(null); setSuccess(null);
  };

  const toggleCategory = (cat: string) =>
    setCollapsed(prev => { const n = new Set(prev); n.has(cat) ? n.delete(cat) : n.add(cat); return n; });

  // ─── Orchestrator handlers ───────────────────────────────────────────────────

  const startEditOrchestrator = () => { setOrchMode('edit'); setOrchValidResult(null); if (orchConfig) setOrchDraft(orchConfig.instructions); };
  const cancelEditOrchestrator = () => { setOrchMode('view'); setOrchValidResult(null); if (orchConfig) setOrchDraft(orchConfig.instructions); };

  const validateOrchestrator = async () => {
    setOrchValidating(true); setOrchValidResult(null);
    try { setOrchValidResult(await validateOrchestratorConfig(orchDraft)); }
    catch (e) { setError(String(e)); }
    finally { setOrchValidating(false); }
  };

  const saveOrchestrator = async () => {
    setOrchSaving(true); setError(null);
    try {
      const updated = await saveOrchestratorConfig(orchDraft);
      setOrchConfig(updated); setOrchMode('view'); setOrchValidResult(null);
      setSuccess('Orchestrator instructions saved.');
    } catch (e) { setError(String(e)); }
    finally { setOrchSaving(false); }
  };

  const resetOrchestrator = async () => {
    if (!confirm('Reset to default? Your customisation will be deleted.')) return;
    try {
      const updated = await resetOrchestratorConfig();
      setOrchConfig(updated); setOrchDraft(updated.instructions);
      setOrchMode('view'); setOrchValidResult(null);
      setSuccess('Orchestrator reset to default.');
    } catch (e) { setError(String(e)); }
  };

  const loadDefaultOrchestrator = async () => {
    try { const res = await getOrchestratorDefault(); setOrchDraft(res.instructions); setOrchValidResult(null); }
    catch (e) { setError(String(e)); }
  };

  // ─── Skill CRUD ─────────────────────────────────────────────────────────────

  const save = async () => {
    if (nav?.type !== 'skill') return;
    setSaving(true); setError(null); setSuccess(null);
    try {
      if (nav.id === 'new') {
        const created = await createSkill(form);
        setSkills(prev => [...prev, created]);
        setNav({ type: 'skill', id: created.id });
        setForm(created); setSuccess('Skill created.');
      } else {
        const updated = await updateSkill(nav.id, form);
        setSkills(prev => prev.map(s => s.id === updated.id ? updated : s));
        setForm(updated); setSuccess('Skill saved.');
      }
      setMode('view');
    } catch (e) { setError(String(e)); }
    finally { setSaving(false); }
  };

  const remove = async () => {
    if (nav?.type !== 'skill' || typeof nav.id !== 'number') return;
    if (!confirm('Delete this skill?')) return;
    try {
      await deleteSkill(nav.id);
      setSkills(prev => prev.filter(s => s.id !== nav.id));
      setNav(null); setForm(EMPTY_SKILL);
    } catch (e) { setError(String(e)); }
  };

  const seed = async () => {
    try { await seedDemoSkills(); await loadSkills(); setSuccess('Demo skills seeded.'); }
    catch (e) { setError(String(e)); }
  };

  const setField = (key: keyof CopilotSkill, value: string | boolean) =>
    setForm(prev => ({ ...prev, [key]: value }));

  // ─── AI Chat ────────────────────────────────────────────────────────────────

  const sendChat = async () => {
    const msg = chatInput.trim();
    if (!msg || chatLoading) return;
    setChatInput('');

    const fileNote = attachedFiles.length > 0 ? ` 📎 (${attachedFiles.length} file${attachedFiles.length > 1 ? 's' : ''})` : '';
    const fullMsg = attachedFiles.length > 0
      ? `${msg}\n\n--- Attached Files ---\n${attachedFiles.map(f => `[${f.name}]\n${f.content}`).join('\n\n')}`
      : msg;
    setAttachedFiles([]);
    setChat(prev => [...prev, { role: 'user', content: msg + fileNote }]);
    setChatLoading(true);
    try {
      const res: AiAssistResponse = await aiAssistSkill(fullMsg, form);
      setChat(prev => [...prev, {
        role: 'assistant', content: res.reply,
        suggestedFields: res.suggestedFields && Object.keys(res.suggestedFields).length > 0 ? res.suggestedFields : undefined,
      }]);
    } catch (e) {
      setChat(prev => [...prev, { role: 'assistant', content: `Error: ${e}` }]);
    } finally { setChatLoading(false); }
  };

  const applyFields = (fields: Record<string, string>) => {
    setForm(prev => ({ ...prev, ...fields }));
    if (mode === 'view') setMode('edit');
    setSuccess('AI suggestions applied — review and save when ready.');
  };

  // ─── Render ─────────────────────────────────────────────────────────────────

  const skillsById = Object.fromEntries(skills.map(s => [s.id, s]));
  const currentSkill = nav?.type === 'skill' && nav.id !== 'new' ? (skillsById[nav.id as number] ?? null) : null;

  return (
    <div style={{ height: '100vh', background: '#f0f2f5', display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>

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
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontWeight: 700, fontSize: 13, color: '#1a237e' }}>Orchestrator</div>
              <div style={{ fontSize: 10, color: orchConfig?.isCustomized ? '#e65100' : '#888' }}>
                {orchConfig?.isCustomized ? 'customised' : 'Skill selection logic'}
              </div>
            </div>
          </div>

          <div style={{ fontSize: 10, fontWeight: 700, color: '#aaa', padding: '8px 14px 4px', letterSpacing: 1 }}>SKILLS</div>

          <div style={{ flex: 1, overflowY: 'auto' }}>
            {loadingSkills && <div style={{ padding: 12, color: '#aaa', fontSize: 12 }}>Loading…</div>}
            {CATEGORIES.map(cat => {
              const catSkills = skills.filter(s => s.category === cat);
              const isOpen = !collapsed.has(cat);
              return (
                <div key={cat}>
                  <div onClick={() => toggleCategory(cat)} style={{ padding: '6px 14px', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 6, background: '#fafafa', borderBottom: '1px solid #f0f0f0' }}>
                    <span style={{ fontSize: 10, color: '#aaa', width: 10 }}>{isOpen ? '▼' : '▶'}</span>
                    <span style={{ fontSize: 13 }}>{CATEGORY_ICONS[cat]}</span>
                    <span style={{ fontSize: 11, fontWeight: 700, color: '#555' }}>{cat}</span>
                    <span style={{ marginLeft: 'auto', fontSize: 10, color: '#bbb' }}>{catSkills.length}</span>
                  </div>
                  {isOpen && catSkills.map(skill => {
                    const isSelected = nav?.type === 'skill' && nav.id === skill.id;
                    return (
                      <div key={skill.id} onClick={() => selectSkill(skill.id)} style={{ padding: '8px 14px 8px 28px', cursor: 'pointer', background: isSelected ? '#e3f2fd' : 'transparent', borderLeft: isSelected ? '3px solid #1976d2' : '3px solid transparent', borderBottom: '1px solid #f8f8f8' }}>
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

          <div style={{ padding: 10, borderTop: '1px solid #eee' }}>
            <button onClick={newSkill} style={{ width: '100%', padding: '8px 0', background: '#1976d2', color: '#fff', border: 'none', borderRadius: 6, fontWeight: 600, fontSize: 13, cursor: 'pointer' }}>
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

          {/* ── Orchestrator Panel ── */}
          {nav?.type === 'orchestrator' && (
            <div style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
              {/* Toolbar */}
              <div style={{ padding: '10px 16px', borderBottom: '1px solid #e0e0e0', display: 'flex', alignItems: 'center', gap: 10, background: '#fff', flexShrink: 0 }}>
                <span style={{ fontWeight: 700, fontSize: 14, color: '#1a237e' }}>🎯 Orchestrator</span>
                {orchConfig?.isCustomized && (
                  <span style={{ background: '#fff3e0', color: '#e65100', padding: '1px 8px', borderRadius: 10, fontSize: 11, fontWeight: 700 }}>customised</span>
                )}
                <div style={{ marginLeft: 'auto', display: 'flex', gap: 8 }}>
                  {orchMode === 'view' ? (
                    <>
                      <button onClick={startEditOrchestrator} style={toolbarBtn}>✏ Edit</button>
                      {orchConfig?.isCustomized && <button onClick={resetOrchestrator} style={{ ...toolbarBtn, color: '#c62828', borderColor: '#c62828' }}>↺ Reset</button>}
                    </>
                  ) : (
                    <>
                      <button onClick={validateOrchestrator} disabled={orchValidating} style={{ ...toolbarBtn, color: '#6a1b9a', borderColor: '#6a1b9a' }}>{orchValidating ? '⏳ Validating…' : '✔ Validate'}</button>
                      <button onClick={loadDefaultOrchestrator} style={toolbarBtn}>↓ Default</button>
                      <button onClick={saveOrchestrator} disabled={orchSaving || orchValidating} style={primaryBtn}>{orchSaving ? 'Saving…' : '💾 Save'}</button>
                      <button onClick={cancelEditOrchestrator} style={toolbarBtn}>Cancel</button>
                    </>
                  )}
                </div>
              </div>

              {/* Alerts */}
              {(error || success) && (
                <div style={{ padding: '0 16px', paddingTop: 10, flexShrink: 0 }}>
                  {error && <div style={alertStyle('#c62828', '#ffebee')}>{error}</div>}
                  {success && <div style={alertStyle('#388e3c', '#e8f5e9')}>{success}</div>}
                </div>
              )}

              {/* Instructions */}
              <div style={{ flex: 1, overflow: 'hidden', display: 'flex', flexDirection: 'column' }}>
                {orchConfig ? (
                  orchMode === 'view' ? (
                    <div style={{ flex: 1, overflowY: 'auto', padding: '16px 20px', fontFamily: 'monospace', fontSize: 13, lineHeight: 1.7, whiteSpace: 'pre-wrap', background: '#1a1a2e', color: '#e0e0e0' }}>
                      {orchConfig.instructions}
                    </div>
                  ) : (
                    <textarea
                      value={orchDraft}
                      onChange={e => { setOrchDraft(e.target.value); setOrchValidResult(null); }}
                      style={{ flex: 1, fontFamily: 'monospace', fontSize: 13, lineHeight: 1.7, padding: '16px 20px', border: 'none', background: '#1e1e1e', color: '#d4d4d4', resize: 'none', outline: 'none' }}
                    />
                  )
                ) : (
                  <div style={{ padding: 20, color: '#aaa', fontSize: 13 }}>Loading…</div>
                )}
              </div>

              {/* Validation result */}
              {orchValidResult && (
                <div style={{ flexShrink: 0, padding: '10px 16px', borderTop: '1px solid #e0e0e0', background: orchValidResult.valid ? '#e8f5e9' : '#ffebee', borderLeft: `4px solid ${orchValidResult.valid ? '#43a047' : '#c62828'}` }}>
                  <div style={{ fontWeight: 700, fontSize: 13, color: orchValidResult.valid ? '#2e7d32' : '#c62828', marginBottom: 4 }}>
                    {orchValidResult.valid ? '✔ Validation passed' : '✘ Validation failed'}
                  </div>
                  {orchValidResult.issues.map((issue, i) => <div key={i} style={{ fontSize: 12, color: '#c62828' }}>• {issue}</div>)}
                  {orchValidResult.sampleResponse && (
                    <details style={{ marginTop: 6 }}>
                      <summary style={{ fontSize: 12, cursor: 'pointer', color: '#555' }}>AI sample response</summary>
                      <pre style={{ marginTop: 4, fontSize: 11, background: '#f5f5f5', padding: 8, borderRadius: 4, overflowX: 'auto', whiteSpace: 'pre-wrap' }}>{orchValidResult.sampleResponse}</pre>
                    </details>
                  )}
                </div>
              )}
            </div>
          )}

          {/* ── Skill Panel ── */}
          {nav?.type === 'skill' && (
            <div style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>

              {/* Toolbar */}
              <div style={{ padding: '8px 16px', borderBottom: '1px solid #e0e0e0', display: 'flex', alignItems: 'center', gap: 10, background: '#fff', flexShrink: 0 }}>
                {mode === 'view' && currentSkill ? (
                  <>
                    <span style={{ fontWeight: 700, fontSize: 14, color: '#222' }}>{currentSkill.name}</span>
                    <span style={{ background: '#e3f2fd', color: '#1565c0', padding: '1px 8px', borderRadius: 10, fontSize: 11, fontWeight: 700 }}>
                      {CATEGORY_ICONS[currentSkill.category]} {currentSkill.category}
                    </span>
                    {!currentSkill.isActive && <span style={{ background: '#f5f5f5', color: '#aaa', padding: '1px 8px', borderRadius: 10, fontSize: 11 }}>inactive</span>}
                    {currentSkill.isSystemSeed && <span style={{ background: '#e8f5e9', color: '#388e3c', padding: '1px 8px', borderRadius: 10, fontSize: 11 }}>⚡ system</span>}
                    <div style={{ marginLeft: 'auto', display: 'flex', gap: 8 }}>
                      <button onClick={openPrompt} style={{ ...toolbarBtn, color: '#1565c0', borderColor: '#1565c0' }}>📄 Prompt</button>
                      <button onClick={() => setMode('edit')} style={toolbarBtn}>✏ Edit</button>
                      <button onClick={remove} style={{ ...toolbarBtn, color: '#c62828', borderColor: '#c62828' }}>🗑 Delete</button>
                    </div>
                  </>
                ) : (
                  <>
                    <span style={{ fontWeight: 700, fontSize: 14, color: '#222' }}>{nav.id === 'new' ? 'New Skill' : (form.name || 'Editing…')}</span>
                    <div style={{ marginLeft: 'auto', display: 'flex', gap: 8 }}>
                      <button onClick={openPrompt} style={{ ...toolbarBtn, color: '#1565c0', borderColor: '#1565c0' }}>📄 Prompt</button>
                      <button onClick={save} disabled={saving} style={primaryBtn}>{saving ? 'Saving…' : nav.id === 'new' ? '+ Create' : '💾 Save'}</button>
                      {nav.id !== 'new' && <button onClick={() => setMode('view')} style={toolbarBtn}>Cancel</button>}
                    </div>
                  </>
                )}
              </div>

              {/* Two columns: AI Chat | Fields */}
              <div style={{ flex: 1, display: 'flex', overflow: 'hidden' }}>

                {/* ── Left: AI Chat ── */}
                <div style={{ width: '40%', borderRight: '1px solid #e0e0e0', background: '#fff', display: 'flex', flexDirection: 'column', flexShrink: 0 }}>
                  <div style={{ padding: '8px 14px', borderBottom: '1px solid #eee', fontWeight: 700, fontSize: 12, color: '#1a237e', background: '#f5f7ff', flexShrink: 0 }}>
                    🤖 AI Assistant
                  </div>
                  <div style={{ padding: '5px 10px', background: '#fffde7', borderBottom: '1px solid #fff176', fontSize: 11, color: '#795548', flexShrink: 0 }}>
                    Describe what you want. AI will suggest field text. Use 📄 Prompt to edit the full prompt and sync back to fields.
                  </div>

                  {/* Chat history */}
                  <div style={{ flex: 1, overflowY: 'auto', padding: 10, display: 'flex', flexDirection: 'column', gap: 8 }}>
                    {chat.length === 0 && (
                      <div style={{ color: '#bbb', fontSize: 12, textAlign: 'center', paddingTop: 24 }}>
                        Ask AI to help write any field.<br />
                        e.g. "Write identification rules for an ascending triangle"
                      </div>
                    )}
                    {chat.map((msg, i) => (
                      <div key={i} style={{ display: 'flex', flexDirection: 'column', alignItems: msg.role === 'user' ? 'flex-end' : 'flex-start' }}>
                        <div style={{
                          maxWidth: '90%', padding: '7px 10px', borderRadius: 10, fontSize: 12, lineHeight: 1.5,
                          background: msg.role === 'user' ? '#1976d2' : '#37474f', color: '#fff',
                          borderBottomRightRadius: msg.role === 'user' ? 2 : 10,
                          borderBottomLeftRadius: msg.role === 'assistant' ? 2 : 10,
                        }}>
                          {msg.content}
                        </div>
                        {msg.suggestedFields && (
                          <button onClick={() => applyFields(msg.suggestedFields!)} style={{ marginTop: 4, fontSize: 11, padding: '3px 10px', background: '#43a047', color: '#fff', border: 'none', borderRadius: 6, cursor: 'pointer' }}>
                            ✓ Apply {Object.keys(msg.suggestedFields).length} suggestion(s) to fields
                          </button>
                        )}
                      </div>
                    ))}
                    {chatLoading && <div style={{ color: '#aaa', fontSize: 12, fontStyle: 'italic' }}>AI is thinking…</div>}
                    <div ref={chatBottomRef} />
                  </div>

                  {/* Attached files */}
                  {attachedFiles.length > 0 && (
                    <div style={{ padding: '4px 10px', borderTop: '1px solid #f0f0f0', display: 'flex', flexWrap: 'wrap', gap: 4, flexShrink: 0 }}>
                      {attachedFiles.map((f, i) => (
                        <span key={i} style={{ background: '#e3f2fd', color: '#1565c0', borderRadius: 10, padding: '2px 8px', fontSize: 11, display: 'flex', alignItems: 'center', gap: 4 }}>
                          📎 {f.name}
                          <button onClick={() => removeFile(i)} style={{ background: 'none', border: 'none', cursor: 'pointer', color: '#1565c0', padding: 0, fontSize: 13, lineHeight: 1 }}>×</button>
                        </span>
                      ))}
                    </div>
                  )}

                  {/* Chat input */}
                  <div style={{ padding: '8px 10px', borderTop: '1px solid #eee', flexShrink: 0 }}>
                    <textarea
                      rows={4}
                      value={chatInput}
                      onChange={e => setChatInput(e.target.value)}
                      onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendChat(); } }}
                      placeholder="Describe what you want… (Enter to send, Shift+Enter = new line)"
                      disabled={chatLoading}
                      style={{ width: '100%', boxSizing: 'border-box', border: '1px solid #ddd', borderRadius: 6, padding: '6px 8px', fontSize: 12, resize: 'none', fontFamily: 'inherit' }}
                    />
                    <div style={{ display: 'flex', gap: 6, marginTop: 6 }}>
                      <input ref={fileInputRef} type="file" multiple onChange={handleFileAttach} style={{ display: 'none' }} accept=".txt,.md,.csv,.json,.py,.js,.ts,.java,.xml,.yaml,.yml" />
                      <button onClick={() => fileInputRef.current?.click()} style={{ padding: '5px 10px', background: '#f5f5f5', color: '#555', border: '1px solid #ddd', borderRadius: 6, fontSize: 11, cursor: 'pointer' }} title="Attach text files">📎</button>
                      <button onClick={sendChat} disabled={chatLoading || !chatInput.trim()} style={{ flex: 1, padding: '5px 0', background: chatLoading ? '#aaa' : '#1976d2', color: '#fff', border: 'none', borderRadius: 6, fontWeight: 600, fontSize: 12, cursor: chatLoading ? 'default' : 'pointer' }}>
                        {chatLoading ? 'Thinking…' : 'Ask AI ↵'}
                      </button>
                    </div>
                  </div>
                </div>

                {/* ── Right: Skill Fields ── */}
                <div style={{ flex: 1, overflowY: 'auto', padding: '12px 16px', background: '#f8f9fa' }}>
                  {error && <div style={alertStyle('#c62828', '#ffebee')}>{error}</div>}
                  {success && <div style={alertStyle('#388e3c', '#e8f5e9')}>{success}</div>}

                  {/* Metadata */}
                  <div style={{ background: '#fff', borderRadius: 8, padding: '12px 14px', marginBottom: 12, border: '1px solid #e0e0e0' }}>
                    <div style={{ fontWeight: 700, fontSize: 12, color: '#333', marginBottom: 10 }}>Skill Metadata</div>
                    {mode === 'edit' ? (
                      <>
                        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10, marginBottom: 10 }}>
                          <MetaField label="Name *" value={form.name ?? ''} onChange={v => setField('name', v)} />
                          <MetaField label="Skill Key *" value={form.skillKey ?? ''} onChange={v => setField('skillKey', v)} hint="e.g. wave_4" />
                          <MetaField label="Category" value={form.category ?? ''} onChange={v => setField('category', v)} hint="WAVE / PATTERN / CONFIRMATION / OVERRIDE" />
                          <MetaField label="Description" value={form.description ?? ''} onChange={v => setField('description', v)} />
                        </div>
                        <label style={{ fontSize: 12, fontWeight: 600, color: '#555', cursor: 'pointer' }}>
                          <input type="checkbox" checked={form.isActive ?? true} onChange={e => setField('isActive', e.target.checked)} style={{ marginRight: 6 }} />
                          Active (included in orchestration)
                        </label>
                      </>
                    ) : (
                      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', fontSize: 12, color: '#444' }}>
                        <span><strong>Key:</strong> {currentSkill?.skillKey || '—'}</span>
                        <span><strong>Category:</strong> {currentSkill?.category || '—'}</span>
                        <span style={{ color: currentSkill?.isActive ? '#388e3c' : '#aaa' }}>{currentSkill?.isActive ? 'Active' : 'Inactive'}</span>
                        {currentSkill?.description && <span style={{ color: '#777' }}>{currentSkill.description}</span>}
                      </div>
                    )}
                  </div>

                  {/* 7 fields */}
                  {SECTIONS.map(s => (
                    <div key={s.key as string} style={{ background: '#fff', borderRadius: 8, padding: '10px 14px', marginBottom: 10, border: '1px solid #e0e0e0' }}>
                      <div style={{ fontWeight: 700, fontSize: 12, color: '#333', marginBottom: 4 }}>
                        {s.label}
                        <span style={{ fontWeight: 400, color: '#bbb', marginLeft: 6, fontSize: 11 }}>{s.hint}</span>
                      </div>
                      {mode === 'edit' ? (
                        <textarea
                          rows={4}
                          value={String(form[s.key] ?? '')}
                          onChange={e => setField(s.key, e.target.value)}
                          style={{ width: '100%', boxSizing: 'border-box', padding: '6px 8px', border: '1px solid #e0e0e0', borderRadius: 6, fontSize: 12, fontFamily: 'monospace', lineHeight: 1.5, resize: 'vertical' }}
                        />
                      ) : (
                        <div style={{ fontSize: 12, fontFamily: 'monospace', lineHeight: 1.5, color: String(form[s.key] ?? '').trim() ? '#333' : '#ddd', whiteSpace: 'pre-wrap', minHeight: 18 }}>
                          {String(form[s.key] ?? '').trim() || '(not populated)'}
                        </div>
                      )}
                    </div>
                  ))}

                  {mode === 'edit' && (
                    <div style={{ paddingBottom: 16, display: 'flex', gap: 8 }}>
                      <button onClick={save} disabled={saving} style={primaryBtn}>
                        {saving ? 'Saving…' : nav.id === 'new' ? '+ Create Skill' : '💾 Save'}
                      </button>
                      {nav.id !== 'new' && <button onClick={() => setMode('view')} style={toolbarBtn}>Cancel</button>}
                    </div>
                  )}
                </div>

              </div>
            </div>
          )}

        </div>
      </div>

      {/* ── Prompt Modal ── */}
      {showPrompt && (
        <div
          style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.65)', zIndex: 1000, display: 'flex', alignItems: 'center', justifyContent: 'center' }}
          onClick={e => { if (e.target === e.currentTarget) setShowPrompt(false); }}
        >
          <div style={{ width: '72%', height: '82vh', background: '#1a1a2e', borderRadius: 12, display: 'flex', flexDirection: 'column', overflow: 'hidden', boxShadow: '0 20px 60px rgba(0,0,0,0.5)' }}>

            {/* Modal header */}
            <div style={{ padding: '12px 18px', borderBottom: '1px solid #252550', display: 'flex', alignItems: 'center', gap: 12, flexShrink: 0 }}>
              <span style={{ fontWeight: 700, fontSize: 13, color: '#90caf9' }}>📄 COMPILED SKILL PROMPT</span>
              <span style={{ fontSize: 11, color: '#666', flex: 1 }}>Edit directly, then click "Apply to Fields" to sync back to the 7 sections.</span>
              <div style={{ display: 'flex', gap: 8 }}>
                <button
                  onClick={applyPromptToFields}
                  style={{ padding: '6px 16px', background: '#1565c0', color: '#fff', border: 'none', borderRadius: 6, fontWeight: 700, fontSize: 12, cursor: 'pointer' }}
                >
                  ✓ Apply to Fields
                </button>
                <button
                  onClick={() => setShowPrompt(false)}
                  style={{ padding: '6px 12px', background: 'rgba(255,255,255,0.1)', color: '#ccc', border: '1px solid rgba(255,255,255,0.2)', borderRadius: 6, fontSize: 12, cursor: 'pointer' }}
                >
                  Close
                </button>
              </div>
            </div>

            {/* Editable prompt */}
            <textarea
              value={promptDraft}
              onChange={e => setPromptDraft(e.target.value)}
              spellCheck={false}
              style={{ flex: 1, fontFamily: 'monospace', fontSize: 12, lineHeight: 1.7, padding: '14px 18px', border: 'none', background: '#1e1e1e', color: '#d4d4d4', resize: 'none', outline: 'none' }}
            />

            {/* Modal footer hint */}
            <div style={{ padding: '8px 18px', borderTop: '1px solid #252550', fontSize: 11, color: '#555', flexShrink: 0 }}>
              Tip: The format uses <code style={{ color: '#90caf9' }}>--- SECTION NAME ---</code> markers. Editing the text and clicking "Apply to Fields" will parse each section back into its field.
            </div>
          </div>
        </div>
      )}

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
      <input value={value} onChange={e => onChange(e.target.value)} style={{ width: '100%', boxSizing: 'border-box', border: '1px solid #ddd', borderRadius: 6, padding: '5px 8px', fontSize: 12 }} />
    </div>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

function alertStyle(color: string, bg: string): React.CSSProperties {
  return { background: bg, border: `1px solid ${color}`, color, borderRadius: 6, padding: '8px 12px', marginBottom: 10, fontSize: 12 };
}

const primaryBtn: React.CSSProperties = {
  padding: '7px 18px', background: '#1976d2', color: '#fff', border: 'none',
  borderRadius: 6, cursor: 'pointer', fontWeight: 700, fontSize: 13,
};

const toolbarBtn: React.CSSProperties = {
  padding: '5px 12px', background: '#fff', color: '#555', border: '1px solid #ccc',
  borderRadius: 6, cursor: 'pointer', fontWeight: 500, fontSize: 12,
};

const headerBtn: React.CSSProperties = {
  padding: '5px 12px', background: 'rgba(255,255,255,0.15)', color: '#fff',
  border: '1px solid rgba(255,255,255,0.3)', borderRadius: 6, cursor: 'pointer', fontSize: 13,
};
