import React, { useState, useEffect } from 'react';
import { getOpenAiKeyStatus, saveOpenAiKey, deleteOpenAiKey } from './copilotApi';

interface Props {
  onClose: () => void;
}

const OPENAI_MODELS = [
  'gpt-4.1',
  'gpt-4.1-mini',
  'gpt-4.1-nano',
  'gpt-4o',
  'gpt-4o-mini',
];

export default function CopilotSettingsModal({ onClose }: Props) {
  const [hasKey, setHasKey] = useState<boolean | null>(null);
  const [currentModel, setCurrentModel] = useState<string | null>(null);
  const [apiKey, setApiKey] = useState('');
  const [model, setModel] = useState('gpt-4.1-mini');
  const [baseUrl, setBaseUrl] = useState('https://api.openai.com/v1');
  const [localProvider, setLocalProvider] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  useEffect(() => {
    getOpenAiKeyStatus()
      .then(s => {
        setHasKey(s.configured);
        setCurrentModel(s.model ?? null);
        if (s.model) setModel(s.model);
        if (s.baseUrl) setBaseUrl(s.baseUrl);
        if (s.localProvider) setLocalProvider(s.localProvider);
      })
      .catch(() => setHasKey(false));
  }, []);

  // When toggling to local, pre-fill sensible defaults
  const handleLocalToggle = (checked: boolean) => {
    setLocalProvider(checked);
    if (checked && baseUrl === 'https://api.openai.com/v1') {
      setBaseUrl('http://localhost:8080/v1');
      setModel('');
    } else if (!checked) {
      setBaseUrl('https://api.openai.com/v1');
      setModel('gpt-4.1-mini');
    }
  };

  const save = async () => {
    if (!localProvider && !apiKey.trim() && !hasKey) {
      setError('API key is required for OpenAI providers');
      return;
    }
    if (!model.trim()) { setError('Model name is required'); return; }
    setSaving(true); setError(null); setSuccess(null);
    try {
      await saveOpenAiKey(apiKey.trim(), model.trim(), baseUrl.trim(), localProvider);
      setHasKey(true);
      setCurrentModel(model.trim());
      setApiKey('');
      setSuccess(localProvider ? 'Local LLM configured.' : 'API key saved. Analysis will now use your key.');
    } catch (e) {
      setError(String(e));
    } finally {
      setSaving(false);
    }
  };

  const remove = async () => {
    if (!confirm('Remove your AI configuration? The system fallback will be used.')) return;
    try {
      await deleteOpenAiKey();
      setHasKey(false); setCurrentModel(null); setSuccess('Configuration removed.');
    } catch (e) {
      setError(String(e));
    }
  };

  return (
    <div style={{
      position: 'fixed', inset: 0, zIndex: 99999,
      background: 'rgba(0,0,0,0.5)', display: 'flex', alignItems: 'center', justifyContent: 'center',
    }} onClick={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div style={{
        background: '#fff', borderRadius: 12, padding: 28, width: 460,
        boxShadow: '0 20px 60px rgba(0,0,0,0.3)',
      }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 }}>
          <h2 style={{ margin: 0, fontSize: 17, fontWeight: 700, color: '#1a237e' }}>
            AI Provider Settings
          </h2>
          <button onClick={onClose} style={{ background: 'none', border: 'none', fontSize: 20, cursor: 'pointer', color: '#999' }}>×</button>
        </div>

        {/* Current status */}
        <div style={{
          padding: '10px 14px', borderRadius: 8, marginBottom: 20,
          background: hasKey ? '#e8f5e9' : '#fff8e1',
          border: `1px solid ${hasKey ? '#a5d6a7' : '#ffe082'}`,
        }}>
          {hasKey === null && <span style={{ color: '#888', fontSize: 13 }}>Checking…</span>}
          {hasKey === true && (
            <span style={{ fontSize: 13, color: '#2e7d32' }}>
              ✓ {localProvider ? 'Local LLM' : 'Personal API key'} active · Model: <strong>{currentModel ?? model}</strong>
            </span>
          )}
          {hasKey === false && (
            <span style={{ fontSize: 13, color: '#f57f17' }}>
              No personal configuration. Using system fallback.
            </span>
          )}
        </div>

        {error && <div style={{ background: '#ffebee', border: '1px solid #ef9a9a', borderRadius: 6, padding: '8px 12px', marginBottom: 14, color: '#c62828', fontSize: 13 }}>{error}</div>}
        {success && <div style={{ background: '#e8f5e9', border: '1px solid #a5d6a7', borderRadius: 6, padding: '8px 12px', marginBottom: 14, color: '#2e7d32', fontSize: 13 }}>{success}</div>}

        {/* Local provider toggle */}
        <div style={{ marginBottom: 16, display: 'flex', alignItems: 'center', gap: 10 }}>
          <input
            type="checkbox"
            id="localProvider"
            checked={localProvider}
            onChange={e => handleLocalToggle(e.target.checked)}
            style={{ width: 16, height: 16, cursor: 'pointer' }}
          />
          <label htmlFor="localProvider" style={{ fontSize: 13, fontWeight: 600, color: '#333', cursor: 'pointer' }}>
            Local LLM (llama.cpp / Ollama / LM Studio / vLLM)
          </label>
        </div>

        {/* Base URL */}
        <div style={{ marginBottom: 14 }}>
          <label style={labelStyle}>Base URL</label>
          <input
            type="text"
            placeholder={localProvider ? 'http://localhost:8080/v1' : 'https://api.openai.com/v1'}
            value={baseUrl}
            onChange={e => setBaseUrl(e.target.value)}
            style={inputStyle}
          />
          {localProvider && (
            <div style={{ fontSize: 11, color: '#888', marginTop: 4 }}>
              llama.cpp: <code>http://localhost:8080/v1</code> · Ollama: <code>http://localhost:11434/v1</code>
            </div>
          )}
        </div>

        {/* Model */}
        <div style={{ marginBottom: 14 }}>
          <label style={labelStyle}>Model</label>
          {localProvider ? (
            <input
              type="text"
              placeholder="e.g. qwen3-coder-30b, llama3.1:70b"
              value={model}
              onChange={e => setModel(e.target.value)}
              style={inputStyle}
            />
          ) : (
            <select value={model} onChange={e => setModel(e.target.value)} style={{ ...inputStyle, cursor: 'pointer' }}>
              {OPENAI_MODELS.map(m => <option key={m} value={m}>{m}</option>)}
            </select>
          )}
        </div>

        {/* API Key — hidden for local */}
        {!localProvider && (
          <div style={{ marginBottom: 20 }}>
            <label style={labelStyle}>OpenAI API Key</label>
            <input
              type="password"
              placeholder={hasKey ? '••••••••••••••••  (leave blank to keep existing)' : 'sk-...'}
              value={apiKey}
              onChange={e => setApiKey(e.target.value)}
              style={inputStyle}
            />
          </div>
        )}

        {localProvider && (
          <div style={{ marginBottom: 20, padding: '8px 12px', background: '#f3f4f6', borderRadius: 6, fontSize: 12, color: '#555' }}>
            No API key needed for local providers. The system will use the Chat Completions API (<code>/v1/chat/completions</code>).
          </div>
        )}

        <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
          {hasKey && (
            <button onClick={remove} style={{ padding: '8px 16px', background: '#fff', color: '#c62828', border: '1px solid #c62828', borderRadius: 6, cursor: 'pointer', fontSize: 13 }}>
              Remove
            </button>
          )}
          <button onClick={onClose} style={{ padding: '8px 16px', background: '#fff', color: '#666', border: '1px solid #ccc', borderRadius: 6, cursor: 'pointer', fontSize: 13 }}>
            Cancel
          </button>
          <button onClick={save} disabled={saving} style={{ padding: '8px 20px', background: '#1a237e', color: '#fff', border: 'none', borderRadius: 6, cursor: 'pointer', fontWeight: 700, fontSize: 13 }}>
            {saving ? 'Saving…' : 'Save'}
          </button>
        </div>
      </div>
    </div>
  );
}

const labelStyle: React.CSSProperties = { display: 'block', fontSize: 12, fontWeight: 600, color: '#555', marginBottom: 5 };
const inputStyle: React.CSSProperties = { width: '100%', boxSizing: 'border-box', border: '1px solid #ddd', borderRadius: 6, padding: '8px 10px', fontSize: 13 };
