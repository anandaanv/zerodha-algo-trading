import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import AiProvidersSection from './AiProvidersSection';
import KiteConfigPage from '../admin/kiteConfig/KiteConfigPage';

type Section = 'ai-providers' | 'kite';

const NAV: { id: Section; label: string; icon: string }[] = [
  { id: 'ai-providers', label: 'AI Providers', icon: '🤖' },
  { id: 'kite', label: 'Kite Config', icon: '📈' },
];

export default function SettingsPage() {
  const navigate = useNavigate();
  const [active, setActive] = useState<Section>('ai-providers');

  return (
    <div style={{ background: '#111', minHeight: '100vh', display: 'flex', flexDirection: 'column' }}>
      {/* Top bar */}
      <div style={{ background: '#1a1a1a', borderBottom: '1px solid #333', padding: '14px 24px', display: 'flex', alignItems: 'center', gap: 16 }}>
        <button
          onClick={() => navigate('/')}
          style={{ background: '#2a2a2a', color: '#aaa', border: '1px solid #444', borderRadius: 5, padding: '6px 14px', cursor: 'pointer', fontSize: 13 }}
        >
          ← Back
        </button>
        <h1 style={{ margin: 0, color: '#90caf9', fontSize: '1.2rem', fontWeight: 700 }}>Settings</h1>
      </div>

      <div style={{ display: 'flex', flex: 1 }}>
        {/* Sidebar */}
        <div style={{ width: 200, background: '#161616', borderRight: '1px solid #2a2a2a', padding: '20px 0' }}>
          {NAV.map(n => (
            <button
              key={n.id}
              onClick={() => setActive(n.id)}
              style={{
                display: 'block', width: '100%', textAlign: 'left',
                padding: '11px 20px', border: 'none', cursor: 'pointer', fontSize: 14,
                background: active === n.id ? '#1e1e2e' : 'transparent',
                color: active === n.id ? '#90caf9' : '#aaa',
                borderLeft: active === n.id ? '3px solid #90caf9' : '3px solid transparent',
                fontWeight: active === n.id ? 600 : 400,
              }}
            >
              {n.icon} {n.label}
            </button>
          ))}
        </div>

        {/* Content */}
        <div style={{ flex: 1, padding: 32, overflowY: 'auto' }}>
          {active === 'ai-providers' && <AiProvidersSection />}
          {active === 'kite' && <KiteConfigPage />}
        </div>
      </div>
    </div>
  );
}
