import React, { useState, useEffect } from 'react';
import { useAuth } from '../../context/AuthContext';
import {
  UserKiteConfigDto, CreateKiteConfigRequest, UpdateKiteConfigRequest,
  listKiteConfigs, createKiteConfig, updateKiteConfig,
  deleteKiteConfig, disconnectKiteConfig, getKiteConnectUrl,
  setAutoLoginCredentials, runAutoLogin,
} from './api';

const card: React.CSSProperties = {
  background: '#1a1a2e', border: '1px solid #333', borderRadius: 8,
  padding: 16, marginBottom: 12,
};
const label: React.CSSProperties = { color: '#aaa', fontSize: 12, display: 'block', marginBottom: 4 };
const input: React.CSSProperties = {
  width: '100%', background: '#0d1117', border: '1px solid #444', borderRadius: 4,
  color: '#e0e0e0', padding: '7px 10px', fontSize: 13, boxSizing: 'border-box',
};
const btn = (color: string, disabled = false): React.CSSProperties => ({
  background: disabled ? '#555' : color, color: '#fff', border: 'none', borderRadius: 4,
  padding: '5px 14px', cursor: disabled ? 'not-allowed' : 'pointer', fontSize: 12, fontWeight: 600,
});

interface EditFormState {
  label: string;
  kiteUserId: string;
  apiKey: string;
  apiSecret: string;       // write-only — only sent if non-blank
  password: string;        // write-only
  totpSecret: string;      // write-only
}

const blankEditForm = (cfg: UserKiteConfigDto): EditFormState => ({
  label: cfg.label || '',
  kiteUserId: cfg.kiteUserId || '',
  apiKey: '',              // intentionally blank; user enters only if changing
  apiSecret: '',
  password: '',
  totpSecret: '',
});

export default function KiteConfigPage() {
  const { user } = useAuth();
  const [configs, setConfigs] = useState<UserKiteConfigDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [form, setForm] = useState<CreateKiteConfigRequest>({
    platformUserId: 0, label: '', apiKey: '', apiSecret: '', kiteUserId: '',
  });
  const [saving, setSaving] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [editForm, setEditForm] = useState<EditFormState | null>(null);
  const [editSaving, setEditSaving] = useState(false);
  const [autoLoginBusy, setAutoLoginBusy] = useState<Set<number>>(new Set());
  const [toast, setToast] = useState<{msg: string; kind: 'ok' | 'err'} | null>(null);

  useEffect(() => { load(); }, []);

  function showToast(msg: string, kind: 'ok' | 'err' = 'ok') {
    setToast({ msg, kind });
    setTimeout(() => setToast(null), 5000);
  }

  async function load() {
    setLoading(true);
    try {
      setConfigs(await listKiteConfigs());
    } catch (e: any) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }

  async function handleCreate(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      await createKiteConfig(form);
      setForm({ platformUserId: 0, label: '', apiKey: '', apiSecret: '', kiteUserId: '' });
      setShowForm(false);
      await load();
    } catch (e: any) {
      setError(e.message);
    } finally {
      setSaving(false);
    }
  }

  async function handleDelete(id: number) {
    if (!confirm('Delete this Kite config?')) return;
    try {
      await deleteKiteConfig(id);
      await load();
    } catch (e: any) {
      setError(e.message);
    }
  }

  async function handleDisconnect(id: number) {
    try {
      await disconnectKiteConfig(id);
      await load();
    } catch (e: any) {
      setError(e.message);
    }
  }

  function handleConnect(id: number) {
    window.location.href = getKiteConnectUrl(id);
  }

  function startEdit(cfg: UserKiteConfigDto) {
    setEditingId(cfg.id);
    setEditForm(blankEditForm(cfg));
  }

  function cancelEdit() {
    setEditingId(null);
    setEditForm(null);
  }

  async function handleSaveEdit(id: number) {
    if (!editForm) return;
    setEditSaving(true);
    try {
      const updateReq: UpdateKiteConfigRequest = {};
      if (editForm.label) updateReq.label = editForm.label;
      if (editForm.kiteUserId) updateReq.kiteUserId = editForm.kiteUserId;
      if (editForm.apiKey) updateReq.apiKey = editForm.apiKey;
      if (editForm.apiSecret) updateReq.apiSecret = editForm.apiSecret;
      if (Object.keys(updateReq).length > 0) {
        await updateKiteConfig(id, updateReq);
      }
      if (editForm.password || editForm.totpSecret) {
        await setAutoLoginCredentials(id, {
          password: editForm.password || undefined,
          totpSecret: editForm.totpSecret || undefined,
        });
      }
      await load();
      cancelEdit();
      showToast('Config updated', 'ok');
    } catch (e: any) {
      showToast(e.message || 'Save failed', 'err');
    } finally {
      setEditSaving(false);
    }
  }

  async function handleAutoLogin(id: number) {
    setAutoLoginBusy(s => new Set(s).add(id));
    try {
      const result = await runAutoLogin(id);
      showToast(`Auto-login OK · token refreshed at ${result.updatedAt?.substring(0, 19) || 'now'}`, 'ok');
      await load();
    } catch (e: any) {
      showToast(e.message || 'Auto-login failed', 'err');
    } finally {
      setAutoLoginBusy(s => { const n = new Set(s); n.delete(id); return n; });
    }
  }

  return (
    <div style={{ maxWidth: 860, margin: '0 auto', padding: '24px 16px', fontFamily: 'monospace' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 }}>
        <h2 style={{ color: '#90caf9', margin: 0 }}>Kite Token Management</h2>
        <button onClick={() => setShowForm(!showForm)} style={btn('#1565c0')}>
          {showForm ? 'Cancel' : '+ Add Config'}
        </button>
      </div>

      {error && (
        <div style={{ background: '#3a1a1a', border: '1px solid #ef5350', borderRadius: 4, padding: '10px 14px', color: '#ef9a9a', marginBottom: 16, fontSize: 13 }}>
          {error}
        </div>
      )}

      {/* Add Config Form */}
      {showForm && (
        <div style={{ ...card, marginBottom: 24, background: '#16213e' }}>
          <h3 style={{ color: '#80cbc4', marginTop: 0, marginBottom: 16, fontSize: 14 }}>New Kite Config</h3>
          <form onSubmit={handleCreate}>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px 20px', marginBottom: 16 }}>
              <div>
                <span style={label}>Platform User ID *</span>
                <input style={input} type="number" required value={form.platformUserId || ''}
                  onChange={e => setForm(f => ({ ...f, platformUserId: Number(e.target.value) }))}
                  placeholder="User ID from users table" />
              </div>
              <div>
                <span style={label}>Label *</span>
                <input style={input} required value={form.label}
                  onChange={e => setForm(f => ({ ...f, label: e.target.value }))}
                  placeholder="e.g. Main Account" />
              </div>
              <div>
                <span style={label}>Kite API Key *</span>
                <input style={input} required value={form.apiKey}
                  onChange={e => setForm(f => ({ ...f, apiKey: e.target.value }))}
                  placeholder="Zerodha API Key" />
              </div>
              <div>
                <span style={label}>Kite API Secret *</span>
                <input style={input} type="password" required value={form.apiSecret}
                  onChange={e => setForm(f => ({ ...f, apiSecret: e.target.value }))}
                  placeholder="Zerodha API Secret" />
              </div>
              <div>
                <span style={label}>Kite User ID (optional)</span>
                <input style={input} value={form.kiteUserId || ''}
                  onChange={e => setForm(f => ({ ...f, kiteUserId: e.target.value }))}
                  placeholder="Zerodha login ID" />
              </div>
            </div>
            <div style={{ background: '#0d2a3a', border: '1px solid #1565c0', borderRadius: 4, padding: '8px 12px', fontSize: 12, color: '#90caf9', marginBottom: 12 }}>
              After saving, use <strong>Edit</strong> to add the Zerodha password + TOTP secret for headless auto-login, or click <strong>Connect</strong> for the manual OAuth flow.
            </div>
            <button type="submit" disabled={saving} style={btn('#2e7d32', saving)}>
              {saving ? 'Saving...' : 'Save Config'}
            </button>
          </form>
        </div>
      )}

      {/* Config List */}
      {loading ? (
        <div style={{ color: '#aaa', padding: 32 }}>Loading...</div>
      ) : configs.length === 0 ? (
        <div style={{ color: '#666', fontSize: 14 }}>No Kite configs yet. Add one above.</div>
      ) : (
        configs.map(cfg => {
          const autoLoginReady = !!(cfg.hasAutoLoginPassword && cfg.hasAutoLoginTotp);
          const isEditing = editingId === cfg.id;
          const isAutoLoginBusy = autoLoginBusy.has(cfg.id);
          return (
            <div key={cfg.id} style={card}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                <div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 6 }}>
                    <span style={{ color: '#e0e0e0', fontWeight: 700, fontSize: 15 }}>{cfg.label}</span>
                    <span style={{
                      fontSize: 11, padding: '2px 8px', borderRadius: 12, fontWeight: 600,
                      background: cfg.connected ? '#1a3a1a' : '#2a1a1a',
                      color: cfg.connected ? '#66bb6a' : '#ef9a9a',
                      border: `1px solid ${cfg.connected ? '#2e7d32' : '#c62828'}`,
                    }}>
                      {cfg.connected ? '● Connected' : '○ Not connected'}
                    </span>
                    <span style={{
                      fontSize: 11, padding: '2px 8px', borderRadius: 12, fontWeight: 600,
                      background: autoLoginReady ? '#1a2a3a' : '#2a2520',
                      color: autoLoginReady ? '#90caf9' : '#bdbdbd',
                      border: `1px solid ${autoLoginReady ? '#1565c0' : '#666'}`,
                    }}>
                      {autoLoginReady ? '⚡ Auto-login ready' : 'Manual only'}
                    </span>
                    {!cfg.active && (
                      <span style={{ fontSize: 11, padding: '2px 8px', borderRadius: 12, background: '#2a2a1a', color: '#ffd54f', border: '1px solid #f57f17' }}>
                        Inactive
                      </span>
                    )}
                  </div>
                  <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, auto)', gap: '4px 20px', fontSize: 12 }}>
                    <span><span style={{ color: '#666' }}>ID: </span><span style={{ color: '#aaa' }}>{cfg.id}</span></span>
                    <span><span style={{ color: '#666' }}>User: </span><span style={{ color: '#90caf9' }}>{cfg.platformUsername || cfg.platformUserId}</span></span>
                    <span><span style={{ color: '#666' }}>Kite ID: </span><span style={{ color: '#aaa' }}>{cfg.kiteUserId || '—'}</span></span>
                    <span><span style={{ color: '#666' }}>API Key: </span><span style={{ color: '#aaa' }}>{cfg.apiKey}</span></span>
                    <span><span style={{ color: '#666' }}>Password: </span><span style={{ color: cfg.hasAutoLoginPassword ? '#66bb6a' : '#666' }}>{cfg.hasAutoLoginPassword ? 'set' : '—'}</span></span>
                    <span><span style={{ color: '#666' }}>TOTP: </span><span style={{ color: cfg.hasAutoLoginTotp ? '#66bb6a' : '#666' }}>{cfg.hasAutoLoginTotp ? 'set' : '—'}</span></span>
                    <span><span style={{ color: '#666' }}>Updated: </span><span style={{ color: '#aaa' }}>{cfg.updatedAt ? new Date(cfg.updatedAt).toLocaleString() : '—'}</span></span>
                  </div>
                </div>
                <div style={{ display: 'flex', gap: 8, flexShrink: 0, marginLeft: 16, flexWrap: 'wrap', justifyContent: 'flex-end' }}>
                  {autoLoginReady && (
                    <button onClick={() => handleAutoLogin(cfg.id)} disabled={isAutoLoginBusy} style={btn('#7E57C2', isAutoLoginBusy)}>
                      {isAutoLoginBusy ? '…' : '⚡ Auto Login'}
                    </button>
                  )}
                  <button onClick={() => handleConnect(cfg.id)} style={btn('#1565c0')}>
                    Connect (OAuth)
                  </button>
                  {!isEditing && (
                    <button onClick={() => startEdit(cfg)} style={btn('#455a64')}>
                      Edit
                    </button>
                  )}
                  {cfg.connected && (
                    <button onClick={() => handleDisconnect(cfg.id)} style={btn('#e65100')}>
                      Disconnect
                    </button>
                  )}
                  <button onClick={() => handleDelete(cfg.id)} style={btn('#b71c1c')}>
                    Delete
                  </button>
                </div>
              </div>

              {/* Inline edit form */}
              {isEditing && editForm && (
                <div style={{ marginTop: 12, paddingTop: 12, borderTop: '1px solid #333' }}>
                  <div style={{ color: '#80cbc4', fontSize: 12, marginBottom: 10, fontWeight: 600 }}>
                    Edit credentials — leave a field blank to keep its current value.
                  </div>
                  <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px 20px', marginBottom: 12 }}>
                    <div>
                      <span style={label}>Label</span>
                      <input style={input} value={editForm.label}
                        onChange={e => setEditForm(f => f && { ...f, label: e.target.value })} />
                    </div>
                    <div>
                      <span style={label}>Kite User ID</span>
                      <input style={input} value={editForm.kiteUserId}
                        onChange={e => setEditForm(f => f && { ...f, kiteUserId: e.target.value })}
                        placeholder="Zerodha login ID (e.g. AB1234)" />
                    </div>
                    <div>
                      <span style={label}>Kite API Key (leave blank to keep)</span>
                      <input style={input} value={editForm.apiKey}
                        onChange={e => setEditForm(f => f && { ...f, apiKey: e.target.value })}
                        placeholder="Only fill to change" />
                    </div>
                    <div>
                      <span style={label}>Kite API Secret (leave blank to keep)</span>
                      <input style={input} type="password" value={editForm.apiSecret}
                        onChange={e => setEditForm(f => f && { ...f, apiSecret: e.target.value })}
                        placeholder="Only fill to change" />
                    </div>
                    <div>
                      <span style={label}>Zerodha Password (for auto-login)</span>
                      <input style={input} type="password" value={editForm.password}
                        onChange={e => setEditForm(f => f && { ...f, password: e.target.value })}
                        placeholder={cfg.hasAutoLoginPassword ? 'Leave blank to keep' : 'Required for auto-login'} />
                    </div>
                    <div>
                      <span style={label}>TOTP Base32 Secret (for auto-login)</span>
                      <input style={input} type="password" value={editForm.totpSecret}
                        onChange={e => setEditForm(f => f && { ...f, totpSecret: e.target.value })}
                        placeholder={cfg.hasAutoLoginTotp ? 'Leave blank to keep' : 'e.g. JBSWY3DPEHPK3PXP'} />
                    </div>
                  </div>
                  <div style={{ display: 'flex', gap: 8 }}>
                    <button onClick={() => handleSaveEdit(cfg.id)} disabled={editSaving} style={btn('#2e7d32', editSaving)}>
                      {editSaving ? 'Saving…' : 'Save'}
                    </button>
                    <button onClick={cancelEdit} style={btn('#555')}>Cancel</button>
                  </div>
                </div>
              )}

              {cfg.connected && !isEditing && (
                <div style={{ marginTop: 10, background: '#0d1a0d', border: '1px solid #1b5e20', borderRadius: 4, padding: '6px 12px', fontSize: 12, color: '#a5d6a7' }}>
                  Kite token active. KiteConnect pool is using this config for API calls.
                </div>
              )}
            </div>
          );
        })
      )}

      {/* Toast */}
      {toast && (
        <div onClick={() => setToast(null)} style={{
          position: 'fixed', top: 16, right: 16, zIndex: 9999,
          padding: '10px 16px', borderRadius: 4, cursor: 'pointer',
          background: toast.kind === 'ok' ? '#2e7d32' : '#c62828',
          color: 'white', fontSize: 13, maxWidth: 420,
          boxShadow: '0 2px 8px rgba(0,0,0,0.3)',
        }}>{toast.msg}</div>
      )}
    </div>
  );
}
