import React, { useEffect, useState } from 'react';
import { ElliottScreener, ElliottTradeSuggestion } from '../types';
import { listScreeners, listSuggestions } from '../api';
import ScreenerConfigPanel from '../components/ScreenerConfigPanel';
import SuggestionListPanel from '../components/SuggestionListPanel';

export default function ElliottScreenerPage() {
  const [activeTab, setActiveTab] = useState<'screeners' | 'suggestions'>('suggestions');
  const [screeners, setScreeners] = useState<ElliottScreener[]>([]);
  const [suggestions, setSuggestions] = useState<ElliottTradeSuggestion[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showCreateForm, setShowCreateForm] = useState(false);
  const [runningId, setRunningId] = useState<number | null>(null);

  const loadAll = async () => {
    setLoading(true);
    setError(null);
    try {
      const [sc, sg] = await Promise.all([listScreeners(), listSuggestions()]);
      setScreeners(sc);
      setSuggestions(sg);
    } catch (e: any) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadAll();
  }, []);

  const handleRun = async (run: any) => {
    // Refresh suggestions after a run
    try {
      const sg = await listSuggestions();
      setSuggestions(sg);
    } catch {}
  };

  return (
    <div
      style={{
        background: '#1a1a1a',
        minHeight: '100vh',
        padding: 24,
        color: '#fff',
      }}
    >
      <h1 style={{ color: '#90caf9', marginTop: 0 }}>Elliott Screener</h1>

      <div style={{ display: 'flex', gap: 12, marginBottom: 24 }}>
        <button
          onClick={() => setActiveTab('suggestions')}
          style={{
            background: activeTab === 'suggestions' ? '#1565c0' : '#333',
            color: '#fff',
            border: 'none',
            padding: '8px 20px',
            borderRadius: 4,
            cursor: 'pointer',
            fontWeight: activeTab === 'suggestions' ? 700 : 400,
          }}
        >
          Trade Suggestions{' '}
          {suggestions.filter((s) => s.state === 'PROPOSED').length > 0 &&
            `(${suggestions.filter((s) => s.state === 'PROPOSED').length} new)`}
        </button>
        <button
          onClick={() => setActiveTab('screeners')}
          style={{
            background: activeTab === 'screeners' ? '#1565c0' : '#333',
            color: '#fff',
            border: 'none',
            padding: '8px 20px',
            borderRadius: 4,
            cursor: 'pointer',
            fontWeight: activeTab === 'screeners' ? 700 : 400,
          }}
        >
          Screener Config
        </button>
      </div>

      {loading && <p style={{ color: '#aaa' }}>Loading...</p>}
      {error && <p style={{ color: '#ef9a9a' }}>Error: {error}</p>}

      {activeTab === 'screeners' && (
        <div>
          <button
            onClick={() => setShowCreateForm(true)}
            style={{
              background: '#1565c0',
              color: '#fff',
              border: 'none',
              padding: '8px 20px',
              borderRadius: 4,
              cursor: 'pointer',
              marginBottom: 16,
            }}
          >
            + New Screener
          </button>

          {showCreateForm && (
            <ScreenerConfigPanel
              onSaved={(s) => {
                setScreeners((prev) => [s, ...prev]);
                setShowCreateForm(false);
              }}
              onCancel={() => setShowCreateForm(false)}
            />
          )}

          {screeners.map((s) => (
            <ScreenerConfigPanel
              key={s.id}
              screener={s}
              onSaved={(updated) =>
                setScreeners((prev) =>
                  prev.map((x) => (x.id === updated.id ? updated : x))
                )
              }
              onRun={handleRun}
              runningId={runningId}
            />
          ))}

          {!loading && screeners.length === 0 && !showCreateForm && (
            <p style={{ color: '#888' }}>
              No screeners yet. Create one to get started.
            </p>
          )}
        </div>
      )}

      {activeTab === 'suggestions' && (
        <SuggestionListPanel
          suggestions={suggestions}
          onRefresh={loadAll}
          onSuggestionUpdated={(updated) =>
            setSuggestions((prev) =>
              prev.map((s) => (s.id === updated.id ? updated : s))
            )
          }
        />
      )}
    </div>
  );
}
