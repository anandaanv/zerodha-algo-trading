import React, { useState, useEffect } from 'react';
import { useAuth } from '../../context/AuthContext';
import {
  UserKiteConfigDto, CreateKiteConfigRequest,
  listKiteConfigs, createKiteConfig, updateKiteConfig,
  deleteKiteConfig, disconnectKiteConfig, getKiteConnectUrl,
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
const btn = (color: string): React.CSSProperties => ({
  background: color, color: '#fff', border: 'none', borderRadius: 4,
  padding: '5px 14px', cursor: 'pointer', fontSize: 12, fontWeight: 600,
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

  useEffect(() => { load(); }, []);

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
              After saving, click <strong>Connect</strong> on the config card. Make sure the Kite developer console has redirect URI set to: <code style={{ background: '#0d1117', padding: '1px 5px', borderRadius: 3 }}>{window.location.origin}/kite-callback/config/&#123;id&#125;</code>
            </div>
            <button type="submit" disabled={saving} style={btn(saving ? '#555' : '#2e7d32')}>
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
        configs.map(cfg => (
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
                  <span><span style={{ color: '#666' }}>Updated: </span><span style={{ color: '#aaa' }}>{cfg.updatedAt ? new Date(cfg.updatedAt).toLocaleString() : '—'}</span></span>
                </div>
              </div>
              <div style={{ display: 'flex', gap: 8, flexShrink: 0, marginLeft: 16 }}>
                <button onClick={() => handleConnect(cfg.id)} style={btn('#1565c0')}>
                  Connect
                </button>
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
            {cfg.connected && (
              <div style={{ marginTop: 10, background: '#0d1a0d', border: '1px solid #1b5e20', borderRadius: 4, padding: '6px 12px', fontSize: 12, color: '#a5d6a7' }}>
                Kite token active. KiteConnect pool is using this config for API calls.
              </div>
            )}
          </div>
        ))
      )}
    </div>
  );
}
