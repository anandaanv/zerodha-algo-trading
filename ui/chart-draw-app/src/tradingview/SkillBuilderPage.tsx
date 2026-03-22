import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import type { CopilotSkill } from './copilotTypes';
import { getSkills, getSkill, createSkill, updateSkill, deleteSkill, seedDemoSkills, previewSkillPrompt } from './copilotApi';

const EMPTY_SKILL: Partial<CopilotSkill> = {
  skillKey: '',
  name: '',
  description: '',
  category: 'PATTERN',
  identificationRules: '',
  confidenceRules: '',
  entryRules: '',
  invalidationRules: '',
  waveContextRules: '',
  managementRules: '',
  crossVerificationRules: '',
  isActive: true,
};

const SECTIONS: { key: keyof CopilotSkill; label: string; hint: string }[] = [
  { key: 'identificationRules', label: '1. Identification Rules', hint: 'How to spot this pattern on the chart' },
  { key: 'confidenceRules',    label: '2. Confidence Rules',     hint: 'What increases or decreases confidence' },
  { key: 'entryRules',         label: '3. Entry Rules',          hint: 'Anticipatory and confirmation entry conditions' },
  { key: 'invalidationRules',  label: '4. Invalidation Rules',   hint: 'Conditions that invalidate this setup' },
  { key: 'waveContextRules',   label: '5. Wave Context Rules',   hint: 'Elliott Wave context and wave labeling' },
  { key: 'managementRules',    label: '6. Management Rules',     hint: 'Stop loss, take profit, position sizing' },
  { key: 'crossVerificationRules', label: '7. Cross-Verification Rules', hint: 'Multi-timeframe and indicator confirmation' },
];

export default function SkillBuilderPage() {
  const navigate = useNavigate();
  const [skills, setSkills] = useState<CopilotSkill[]>([]);
  const [selected, setSelected] = useState<number | 'new' | null>(null);
  const [form, setForm] = useState<Partial<CopilotSkill>>(EMPTY_SKILL);
  const [preview, setPreview] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  const loadSkills = async () => {
    setLoading(true);
    try {
      const list = await getSkills();
      setSkills(list);
    } catch (e) {
      setError(String(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { loadSkills(); }, []);

  const selectSkill = async (id: number) => {
    setSelected(id);
    setPreview(null);
    setError(null);
    try {
      const skill = await getSkill(id);
      setForm(skill);
    } catch (e) {
      setError(String(e));
    }
  };

  const newSkill = () => {
    setSelected('new');
    setForm(EMPTY_SKILL);
    setPreview(null);
    setError(null);
  };

  const save = async () => {
    setSaving(true);
    setError(null);
    setSuccess(null);
    try {
      if (selected === 'new') {
        const created = await createSkill(form);
        setSkills(prev => [...prev, created]);
        setSelected(created.id);
        setForm(created);
        setSuccess('Skill created.');
      } else if (typeof selected === 'number') {
        const updated = await updateSkill(selected, form);
        setSkills(prev => prev.map(s => s.id === updated.id ? updated : s));
        setForm(updated);
        setSuccess('Skill saved.');
      }
    } catch (e) {
      setError(String(e));
    } finally {
      setSaving(false);
    }
  };

  const remove = async () => {
    if (typeof selected !== 'number') return;
    if (!confirm('Delete this skill?')) return;
    try {
      await deleteSkill(selected);
      setSkills(prev => prev.filter(s => s.id !== selected));
      setSelected(null);
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

  const loadPreview = async () => {
    if (typeof selected !== 'number') return;
    try {
      const res = await previewSkillPrompt(selected);
      setPreview(res.prompt);
    } catch (e) {
      setError(String(e));
    }
  };

  const set = (key: keyof CopilotSkill, value: string | boolean) =>
    setForm(prev => ({ ...prev, [key]: value }));

  return (
    <div style={{ minHeight: '100vh', background: '#f5f5f5', display: 'flex', flexDirection: 'column' }}>
      {/* Header */}
      <div style={{ background: '#1a237e', color: '#fff', padding: '12px 20px', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h2 style={{ margin: 0, fontSize: 18, fontWeight: 700 }}>🧠 Skill Builder</h2>
        <div style={{ display: 'flex', gap: 8 }}>
          <button onClick={seed} style={headerBtn}>+ Seed Demos</button>
          <button onClick={() => navigate('/copilot')} style={headerBtn}>Co-Pilot</button>
          <button onClick={() => navigate('/')} style={headerBtn}>← Dashboard</button>
        </div>
      </div>

      <div style={{ display: 'flex', flex: 1, overflow: 'hidden' }}>
        {/* ── Skill List (sidebar) ── */}
        <div style={{
          width: 240, background: '#fff', borderRight: '1px solid #eee',
          display: 'flex', flexDirection: 'column', overflow: 'auto',
        }}>
          <button
            onClick={newSkill}
            style={{ margin: 10, padding: '8px 12px', background: '#1976d2', color: '#fff', border: 'none', borderRadius: 6, cursor: 'pointer', fontWeight: 600 }}
          >+ New Skill</button>

          {loading && <div style={{ padding: 12, color: '#888', fontSize: 13 }}>Loading…</div>}

          {skills.map(skill => (
            <div
              key={skill.id}
              onClick={() => selectSkill(skill.id)}
              style={{
                padding: '10px 12px',
                cursor: 'pointer',
                borderBottom: '1px solid #f0f0f0',
                background: selected === skill.id ? '#e3f2fd' : 'transparent',
                borderLeft: selected === skill.id ? '3px solid #1976d2' : '3px solid transparent',
              }}
            >
              <div style={{ fontWeight: 600, fontSize: 13, color: '#222' }}>{skill.name}</div>
              <div style={{ fontSize: 11, color: '#888', marginTop: 2 }}>
                {skill.skillKey} · {skill.category}
                {skill.isSystemSeed && <span style={{ marginLeft: 4, color: '#1976d2' }}>⚡</span>}
                {!skill.isActive && <span style={{ marginLeft: 4, color: '#999' }}>off</span>}
              </div>
            </div>
          ))}

          {skills.length === 0 && !loading && (
            <div style={{ padding: 12, color: '#bbb', fontSize: 12, textAlign: 'center' }}>
              No skills yet.<br />Add one or seed demos.
            </div>
          )}
        </div>

        {/* ── Editor ── */}
        <div style={{ flex: 1, overflow: 'auto', padding: 24 }}>
          {selected === null && (
            <div style={{ textAlign: 'center', color: '#bbb', paddingTop: 60, fontSize: 14 }}>
              Select a skill or create a new one.
            </div>
          )}

          {selected !== null && (
            <>
              {/* Status messages */}
              {error && <div style={alertStyle('#c62828', '#ffebee')}>{error}</div>}
              {success && <div style={alertStyle('#388e3c', '#e8f5e9')}>{success}</div>}

              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16, marginBottom: 20 }}>
                <Field label="Name *" value={form.name ?? ''} onChange={v => set('name', v)} />
                <Field label="Skill Key *" value={form.skillKey ?? ''} onChange={v => set('skillKey', v)} hint="Unique ID, e.g. triangle_ascending" />
                <Field label="Category" value={form.category ?? ''} onChange={v => set('category', v)} hint="PATTERN, WAVE, STRUCTURE…" />
                <Field label="Description" value={form.description ?? ''} onChange={v => set('description', v)} />
              </div>

              <div style={{ marginBottom: 12 }}>
                <label style={{ fontSize: 13, fontWeight: 600, color: '#555' }}>
                  <input
                    type="checkbox"
                    checked={form.isActive ?? true}
                    onChange={e => set('isActive', e.target.checked)}
                    style={{ marginRight: 6 }}
                  />
                  Active (included in orchestration)
                </label>
              </div>

              <h3 style={{ fontSize: 15, fontWeight: 700, color: '#333', marginBottom: 14, marginTop: 8 }}>
                Skill Sections
              </h3>

              {SECTIONS.map(s => (
                <div key={s.key} style={{ marginBottom: 18 }}>
                  <label style={{ display: 'block', fontWeight: 700, fontSize: 13, color: '#444', marginBottom: 4 }}>
                    {s.label}
                    <span style={{ fontSize: 11, fontWeight: 400, color: '#999', marginLeft: 8 }}>{s.hint}</span>
                  </label>
                  <textarea
                    rows={5}
                    value={String(form[s.key] ?? '')}
                    onChange={e => set(s.key, e.target.value)}
                    style={{
                      width: '100%', boxSizing: 'border-box', padding: '8px 10px',
                      border: '1px solid #ddd', borderRadius: 6, fontSize: 13,
                      fontFamily: 'monospace', lineHeight: 1.5, resize: 'vertical',
                    }}
                  />
                </div>
              ))}

              {/* Action bar */}
              <div style={{ display: 'flex', gap: 10, marginTop: 8, alignItems: 'center' }}>
                <button onClick={save} disabled={saving} style={primaryBtn}>
                  {saving ? 'Saving…' : selected === 'new' ? '+ Create' : '💾 Save'}
                </button>
                {typeof selected === 'number' && (
                  <>
                    <button onClick={loadPreview} style={secondaryBtn}>👁 Preview Prompt</button>
                    <button onClick={remove} style={{ ...secondaryBtn, color: '#c62828', borderColor: '#c62828' }}>🗑 Delete</button>
                  </>
                )}
              </div>

              {/* Prompt preview */}
              {preview && (
                <div style={{
                  marginTop: 20, background: '#1a1a2e', color: '#e0e0e0',
                  borderRadius: 8, padding: 16, fontSize: 12, fontFamily: 'monospace',
                  lineHeight: 1.6, whiteSpace: 'pre-wrap', maxHeight: 400, overflowY: 'auto',
                }}>
                  <div style={{ fontWeight: 700, marginBottom: 8, color: '#90caf9' }}>Generated Prompt:</div>
                  {preview}
                </div>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  );
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function Field({ label, value, onChange, hint }: { label: string; value: string; onChange: (v: string) => void; hint?: string }) {
  return (
    <div>
      <label style={{ display: 'block', fontWeight: 600, fontSize: 12, color: '#555', marginBottom: 4 }}>
        {label}
        {hint && <span style={{ fontWeight: 400, color: '#999', marginLeft: 6 }}>{hint}</span>}
      </label>
      <input
        value={value}
        onChange={e => onChange(e.target.value)}
        style={{ width: '100%', boxSizing: 'border-box', border: '1px solid #ddd', borderRadius: 6, padding: '6px 10px', fontSize: 13 }}
      />
    </div>
  );
}

function alertStyle(color: string, bg: string): React.CSSProperties {
  return { background: bg, border: `1px solid ${color}`, color, borderRadius: 6, padding: '8px 12px', marginBottom: 14, fontSize: 13 };
}

const primaryBtn: React.CSSProperties = {
  padding: '8px 20px', background: '#1976d2', color: '#fff', border: 'none',
  borderRadius: 6, cursor: 'pointer', fontWeight: 700, fontSize: 14,
};

const secondaryBtn: React.CSSProperties = {
  padding: '8px 16px', background: '#fff', color: '#555', border: '1px solid #ccc',
  borderRadius: 6, cursor: 'pointer', fontWeight: 500, fontSize: 13,
};

const headerBtn: React.CSSProperties = {
  padding: '5px 12px', background: 'rgba(255,255,255,0.15)', color: '#fff',
  border: '1px solid rgba(255,255,255,0.3)', borderRadius: 6, cursor: 'pointer', fontSize: 13,
};
