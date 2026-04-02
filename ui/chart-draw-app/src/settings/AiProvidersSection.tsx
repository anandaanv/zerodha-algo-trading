import React, { useEffect, useState } from 'react';
import {
  AiProviderDto, CreateProviderRequest,
  listAiProviders, createAiProvider, updateAiProvider,
  deleteAiProvider, activateAiProvider,
} from './settingsApi';

const OPENAI_MODELS = ['gpt-4.1', 'gpt-4.1-mini', 'gpt-4.1-nano', 'gpt-4o', 'gpt-4o-mini'];

const PRESETS = [
  { label: 'OpenAI', baseUrl: 'https://api.openai.com/v1', model: 'gpt-4.1-mini', local: false },
  { label: 'llama.cpp', baseUrl: 'http://localhost:8080/v1', model: '', local: true },
  { label: 'Ollama', baseUrl: 'http://localhost:11434/v1', model: '', local: true },
  { label: 'LM Studio', baseUrl: 'http://localhost:1234/v1', model: '', local: true },
  { label: 'vLLM', baseUrl: 'http://localhost:8000/v1', model: '', local: true },
];

const emptyForm = (): CreateProviderRequest => ({
  name: '', baseUrl: 'https://api.openai.com/v1', model: 'gpt-4.1-mini',
  apiKey: '', localProvider: false, makeActive: false,
});

export default function AiProvidersSection() {
  const [providers, setProviders] = useState<AiProviderDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [editId, setEditId] = useState<number | null>(null);
  const [form, setForm] = useState<CreateProviderRequest>(emptyForm());
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  useEffect(() => { load(); }, []);

  async function load() {
    setLoading(true);
    try { setProviders(await listAiProviders()); setError(null); }
    catch (e: any) { setError(e.message); }
    finally { setLoading(false); }
  }

  function openAdd() {
    setEditId(null);
    setForm({ ...emptyForm(), makeActive: providers.length === 0 });
    setShowForm(true);
    setFormError(null);
  }

  function openEdit(p: AiProviderDto) {
    setEditId(p.id);
    setForm({ name: p.name, baseUrl: p.baseUrl, model: p.model, apiKey: '', localProvider: p.localProvider, makeActive: false });
    setShowForm(true);
    setFormError(null);
  }

  function applyPreset(preset: typeof PRESETS[0]) {
    setForm(f => ({ ...f, baseUrl: preset.baseUrl, model: preset.model, localProvider: preset.local,
      name: f.name || preset.label }));
  }

  async function handleSave() {
    if (!form.name.trim()) { setFormError('Name is required'); return; }
    if (!form.model.trim()) { setFormError('Model is required'); return; }
    if (!form.localProvider && !form.apiKey?.trim() && editId === null) {
      setFormError('API key is required for non-local providers');
      return;
    }
    setSaving(true); setFormError(null);
    try {
      if (editId !== null) {
        const updated = await updateAiProvider(editId, { name: form.name, baseUrl: form.baseUrl, model: form.model, apiKey: form.apiKey, localProvider: form.localProvider });
        setProviders(ps => ps.map(p => p.id === editId ? updated : p));
      } else {
        const created = await createAiProvider(form);
        setProviders(ps => {
          const updated = ps.map(p => form.makeActive ? { ...p, active: false } : p);
          return [...updated, created];
        });
      }
      setShowForm(false);
    } catch (e: any) { setFormError(e.message); }
    finally { setSaving(false); }
  }

  async function handleActivate(id: number) {
    try {
      await activateAiProvider(id);
      setProviders(ps => ps.map(p => ({ ...p, active: p.id === id })));
    } catch (e: any) { setError(e.message); }
  }

  async function handleDelete(id: number, name: string) {
    if (!confirm(`Delete provider "${name}"?`)) return;
    try {
      await deleteAiProvider(id);
      await load();
    } catch (e: any) { setError(e.message); }
  }

  const s = styles;

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 }}>
        <div>
          <h2 style={s.sectionTitle}>AI Providers</h2>
          <p style={s.sectionDesc}>Configure AI models for Co-Pilot analysis. Only one provider is active at a time.</p>
        </div>
        <button onClick={openAdd} style={s.addBtn}>+ Add Provider</button>
      </div>

      {error && <div style={s.errorBox}>{error}</div>}
      {loading && <p style={{ color: '#888' }}>Loading…</p>}

      {!loading && providers.length === 0 && (
        <div style={s.emptyState}>No providers configured. Add one to enable AI analysis.</div>
      )}

      {providers.map(p => (
        <div key={p.id} style={{ ...s.providerCard, borderLeft: `3px solid ${p.active ? '#4caf50' : '#444'}` }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, flex: 1 }}>
            <div style={{ flex: 1 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <span style={s.providerName}>{p.name}</span>
                {p.active && <span style={s.activeBadge}>ACTIVE</span>}
                {p.localProvider && <span style={s.localBadge}>LOCAL</span>}
              </div>
              <div style={s.providerDetail}>{p.baseUrl} · {p.model}</div>
              {!p.hasApiKey && !p.localProvider && (
                <div style={{ color: '#ff9800', fontSize: 11, marginTop: 2 }}>⚠ No API key set</div>
              )}
            </div>
            <div style={{ display: 'flex', gap: 8 }}>
              {!p.active && (
                <button onClick={() => handleActivate(p.id)} style={s.activateBtn}>Set Active</button>
              )}
              <button onClick={() => openEdit(p)} style={s.editBtn}>Edit</button>
              <button onClick={() => handleDelete(p.id, p.name)} style={s.deleteBtn}>Delete</button>
            </div>
          </div>
        </div>
      ))}

      {showForm && (
        <div style={s.formOverlay} onClick={e => { if (e.target === e.currentTarget) setShowForm(false); }}>
          <div style={s.formCard}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 18 }}>
              <h3 style={{ margin: 0, color: '#90caf9', fontSize: 16 }}>
                {editId !== null ? 'Edit Provider' : 'Add Provider'}
              </h3>
              <button onClick={() => setShowForm(false)} style={s.closeBtn}>×</button>
            </div>

            {/* Presets */}
            <div style={{ marginBottom: 16 }}>
              <label style={s.label}>Quick Presets</label>
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                {PRESETS.map(preset => (
                  <button key={preset.label} onClick={() => applyPreset(preset)} style={s.presetBtn}>
                    {preset.label}
                  </button>
                ))}
              </div>
            </div>

            <div style={{ marginBottom: 14 }}>
              <label style={s.label}>Name *</label>
              <input style={s.input} placeholder="e.g. My GPT-4o, Local Qwen" value={form.name}
                onChange={e => setForm(f => ({ ...f, name: e.target.value }))} />
            </div>

            <div style={{ marginBottom: 14 }}>
              <label style={s.label}>Base URL *</label>
              <input style={s.input} placeholder="https://api.openai.com/v1" value={form.baseUrl}
                onChange={e => setForm(f => ({ ...f, baseUrl: e.target.value }))} />
            </div>

            <div style={{ marginBottom: 14 }}>
              <label style={s.label}>Model *</label>
              {form.localProvider ? (
                <input style={s.input} placeholder="e.g. qwen3-coder-30b, llama3.1:70b" value={form.model}
                  onChange={e => setForm(f => ({ ...f, model: e.target.value }))} />
              ) : (
                <select style={s.input} value={form.model}
                  onChange={e => setForm(f => ({ ...f, model: e.target.value }))}>
                  {OPENAI_MODELS.map(m => <option key={m} value={m}>{m}</option>)}
                  <option value="custom">Custom…</option>
                </select>
              )}
            </div>

            {!form.localProvider && (
              <div style={{ marginBottom: 14 }}>
                <label style={s.label}>API Key {editId !== null ? '(leave blank to keep existing)' : '*'}</label>
                <input style={s.input} type="password" placeholder="sk-..." value={form.apiKey || ''}
                  onChange={e => setForm(f => ({ ...f, apiKey: e.target.value }))} />
              </div>
            )}

            <div style={{ marginBottom: 16, display: 'flex', alignItems: 'center', gap: 10 }}>
              <input type="checkbox" id="localToggle" checked={form.localProvider}
                onChange={e => setForm(f => ({ ...f, localProvider: e.target.checked }))}
                style={{ width: 15, height: 15 }} />
              <label htmlFor="localToggle" style={{ ...s.label, margin: 0, cursor: 'pointer' }}>
                Local provider (uses Chat Completions API, no API key needed)
              </label>
            </div>

            {editId === null && (
              <div style={{ marginBottom: 16, display: 'flex', alignItems: 'center', gap: 10 }}>
                <input type="checkbox" id="makeActive" checked={form.makeActive}
                  onChange={e => setForm(f => ({ ...f, makeActive: e.target.checked }))}
                  style={{ width: 15, height: 15 }} />
                <label htmlFor="makeActive" style={{ ...s.label, margin: 0, cursor: 'pointer' }}>
                  Set as active provider
                </label>
              </div>
            )}

            {formError && <div style={s.errorBox}>{formError}</div>}

            <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 4 }}>
              <button onClick={() => setShowForm(false)} style={s.cancelBtn}>Cancel</button>
              <button onClick={handleSave} disabled={saving} style={s.saveBtn}>
                {saving ? 'Saving…' : 'Save'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

const styles = {
  sectionTitle: { margin: '0 0 4px', color: '#e0e0e0', fontSize: 18, fontWeight: 700 } as React.CSSProperties,
  sectionDesc: { margin: 0, color: '#888', fontSize: 13 } as React.CSSProperties,
  addBtn: { background: '#1565c0', color: '#fff', border: 'none', borderRadius: 6, padding: '8px 18px', cursor: 'pointer', fontWeight: 600, fontSize: 13 } as React.CSSProperties,
  providerCard: { background: '#242424', border: '1px solid #333', borderRadius: 8, padding: '14px 16px', marginBottom: 10, display: 'flex', alignItems: 'center' } as React.CSSProperties,
  providerName: { color: '#e0e0e0', fontWeight: 600, fontSize: 15 } as React.CSSProperties,
  providerDetail: { color: '#888', fontSize: 12, marginTop: 3 } as React.CSSProperties,
  activeBadge: { background: '#2e7d32', color: '#fff', fontSize: 10, fontWeight: 700, padding: '2px 7px', borderRadius: 3 } as React.CSSProperties,
  localBadge: { background: '#4a148c', color: '#fff', fontSize: 10, fontWeight: 700, padding: '2px 7px', borderRadius: 3 } as React.CSSProperties,
  activateBtn: { background: '#1565c0', color: '#fff', border: 'none', borderRadius: 4, padding: '5px 12px', cursor: 'pointer', fontSize: 12 } as React.CSSProperties,
  editBtn: { background: '#333', color: '#ccc', border: '1px solid #555', borderRadius: 4, padding: '5px 12px', cursor: 'pointer', fontSize: 12 } as React.CSSProperties,
  deleteBtn: { background: 'transparent', color: '#ef5350', border: '1px solid #ef5350', borderRadius: 4, padding: '5px 12px', cursor: 'pointer', fontSize: 12 } as React.CSSProperties,
  emptyState: { color: '#555', textAlign: 'center', padding: '32px 0', fontSize: 14 } as React.CSSProperties,
  errorBox: { background: '#4a1515', border: '1px solid #b71c1c', borderRadius: 6, padding: '8px 12px', color: '#ef9a9a', fontSize: 13, marginBottom: 14 } as React.CSSProperties,
  formOverlay: { position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.7)', zIndex: 9999, display: 'flex', alignItems: 'center', justifyContent: 'center' } as React.CSSProperties,
  formCard: { background: '#1e1e1e', border: '1px solid #444', borderRadius: 10, padding: 24, width: 480, maxHeight: '90vh', overflowY: 'auto' } as React.CSSProperties,
  label: { color: '#aaa', fontSize: 12, fontWeight: 600, display: 'block', marginBottom: 5 } as React.CSSProperties,
  input: { width: '100%', boxSizing: 'border-box', background: '#111', border: '1px solid #444', borderRadius: 5, color: '#e0e0e0', padding: '8px 10px', fontSize: 13 } as React.CSSProperties,
  presetBtn: { background: '#2a2a2a', color: '#90caf9', border: '1px solid #444', borderRadius: 4, padding: '4px 10px', cursor: 'pointer', fontSize: 12 } as React.CSSProperties,
  closeBtn: { background: 'none', border: 'none', color: '#888', fontSize: 22, cursor: 'pointer', lineHeight: 1 } as React.CSSProperties,
  cancelBtn: { background: '#2a2a2a', color: '#aaa', border: '1px solid #555', borderRadius: 5, padding: '8px 16px', cursor: 'pointer', fontSize: 13 } as React.CSSProperties,
  saveBtn: { background: '#1565c0', color: '#fff', border: 'none', borderRadius: 5, padding: '8px 20px', cursor: 'pointer', fontWeight: 700, fontSize: 13 } as React.CSSProperties,
};
