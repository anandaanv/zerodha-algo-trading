import React, { useState } from 'react';
import { ElliottScreener, CreateScreenerRequest } from '../types';
import { createScreener, updateScreener, triggerRun } from '../api';

interface Props {
  screener?: ElliottScreener;
  onSaved: (s: ElliottScreener) => void;
  onCancel?: () => void;
  onRun?: (run: any) => void;
  runningId?: number | null;
}

const SCHEDULE_PRESETS = [
  { label: 'Every 15 minutes', cron: '0 */15 * * * ?' },
  { label: 'Every 30 minutes', cron: '0 */30 * * * ?' },
  { label: 'Hourly', cron: '0 0 * * * ?' },
  { label: 'Every 4 hours', cron: '0 0 */4 * * ?' },
  { label: 'Daily at 9:15am', cron: '0 15 9 * * MON-FRI' },
  { label: 'Daily at 3:30pm', cron: '0 30 15 * * MON-FRI' },
  { label: 'Weekly (Mon 9:15am)', cron: '0 15 9 * * MON' },
  { label: 'Custom', cron: 'custom' },
];

export default function ScreenerConfigPanel({
  screener,
  onSaved,
  onCancel,
  onRun,
  runningId,
}: Props) {
  const [name, setName] = useState(screener?.name ?? '');
  const [symbols, setSymbols] = useState(screener?.symbols ?? '');
  const [timeframes, setTimeframes] = useState(
    screener?.timeframes ?? '1w,1d,1h,15m'
  );
  const [primaryTf, setPrimaryTf] = useState(
    screener?.primaryTimeframe ?? ''
  );
  const [scheduleCron, setScheduleCron] = useState(
    screener?.scheduleCron ?? '0 0 * * * ?'
  );
  const [selectedPreset, setSelectedPreset] = useState(() => {
    const preset = SCHEDULE_PRESETS.find(
      (p) => p.cron === (screener?.scheduleCron ?? '0 0 * * * ?')
    );
    return preset?.cron ?? 'custom';
  });
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState(!screener);
  const [running, setRunning] = useState(false);

  const handlePresetChange = (value: string) => {
    setSelectedPreset(value);
    if (value !== 'custom') setScheduleCron(value);
  };

  const handleSave = async () => {
    setSaving(true);
    setSaveError(null);
    try {
      const req: CreateScreenerRequest = {
        name,
        symbols,
        timeframes,
        primaryTimeframe: primaryTf || undefined,
        scheduleCron,
      };
      const saved = screener
        ? await updateScreener(screener.id, req)
        : await createScreener(req);
      onSaved(saved);
      if (!screener) setExpanded(false);
    } catch (e: any) {
      setSaveError(e.message);
    } finally {
      setSaving(false);
    }
  };

  const handleRun = async () => {
    if (!screener) return;
    setRunning(true);
    try {
      const run = await triggerRun(screener.id);
      onRun?.(run);
    } catch (e: any) {
      setSaveError(e.message);
    } finally {
      setRunning(false);
    }
  };

  const fmtDate = (iso: string | null) => {
    if (!iso) return 'Never';
    try {
      return new Date(iso).toLocaleString();
    } catch {
      return iso;
    }
  };

  return (
    <div
      style={{
        background: '#1e1e1e',
        border: '1px solid #333',
        borderRadius: 8,
        marginBottom: 12,
      }}
    >
      {screener && (
        <div
          onClick={() => setExpanded(!expanded)}
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            padding: '12px 16px',
            cursor: 'pointer',
            alignItems: 'center',
          }}
        >
          <div>
            <span style={{ fontWeight: 600, color: '#90caf9' }}>
              {screener.name}
            </span>
            <span style={{ color: '#aaa', marginLeft: 12, fontSize: 13 }}>
              {screener.symbols.split(',').length} symbol(s) · {screener.timeframes}
            </span>
          </div>
          <div style={{ color: '#aaa', fontSize: 12 }}>
            Next: {fmtDate(screener.nextRunAt)} {expanded ? '▲' : '▼'}
          </div>
        </div>
      )}

      {expanded && (
        <div style={{ padding: '0 16px 16px' }}>
          <div
            style={{
              display: 'grid',
              gap: 12,
              marginTop: screener ? 0 : 16,
            }}
          >
            <div>
              <label style={{ color: '#aaa', fontSize: 13 }}>Name</label>
              <input
                value={name}
                onChange={(e) => setName(e.target.value)}
                style={{
                  display: 'block',
                  width: '100%',
                  background: '#2a2a2a',
                  color: '#fff',
                  border: '1px solid #444',
                  borderRadius: 4,
                  padding: '6px 8px',
                  boxSizing: 'border-box',
                  marginTop: 4,
                }}
              />
            </div>
            <div>
              <label style={{ color: '#aaa', fontSize: 13 }}>
                Symbols (comma-separated)
              </label>
              <textarea
                value={symbols}
                onChange={(e) => setSymbols(e.target.value)}
                rows={3}
                placeholder="e.g. RELIANCE,INFY,TCS"
                style={{
                  display: 'block',
                  width: '100%',
                  background: '#2a2a2a',
                  color: '#fff',
                  border: '1px solid #444',
                  borderRadius: 4,
                  padding: '6px 8px',
                  boxSizing: 'border-box',
                  marginTop: 4,
                }}
              />
            </div>
            <div>
              <label style={{ color: '#aaa', fontSize: 13 }}>
                Timeframes (high→low, comma-separated)
              </label>
              <input
                value={timeframes}
                onChange={(e) => setTimeframes(e.target.value)}
                placeholder="e.g. 1w,1d,1h,15m"
                style={{
                  display: 'block',
                  width: '100%',
                  background: '#2a2a2a',
                  color: '#fff',
                  border: '1px solid #444',
                  borderRadius: 4,
                  padding: '6px 8px',
                  boxSizing: 'border-box',
                  marginTop: 4,
                }}
              />
            </div>
            <div>
              <label style={{ color: '#aaa', fontSize: 13 }}>
                Primary Timeframe (optional, defaults to last TF)
              </label>
              <input
                value={primaryTf}
                onChange={(e) => setPrimaryTf(e.target.value)}
                placeholder="e.g. 15m"
                style={{
                  display: 'block',
                  width: '100%',
                  background: '#2a2a2a',
                  color: '#fff',
                  border: '1px solid #444',
                  borderRadius: 4,
                  padding: '6px 8px',
                  boxSizing: 'border-box',
                  marginTop: 4,
                }}
              />
            </div>
            <div>
              <label style={{ color: '#aaa', fontSize: 13 }}>Schedule</label>
              <select
                value={selectedPreset}
                onChange={(e) => handlePresetChange(e.target.value)}
                style={{
                  display: 'block',
                  width: '100%',
                  background: '#2a2a2a',
                  color: '#fff',
                  border: '1px solid #444',
                  borderRadius: 4,
                  padding: '6px 8px',
                  marginTop: 4,
                }}
              >
                {SCHEDULE_PRESETS.map((p) => (
                  <option key={p.cron} value={p.cron}>
                    {p.label}
                  </option>
                ))}
              </select>
              {selectedPreset === 'custom' && (
                <input
                  value={scheduleCron}
                  onChange={(e) => setScheduleCron(e.target.value)}
                  placeholder="Spring cron expression"
                  style={{
                    display: 'block',
                    width: '100%',
                    background: '#2a2a2a',
                    color: '#fff',
                    border: '1px solid #444',
                    borderRadius: 4,
                    padding: '6px 8px',
                    boxSizing: 'border-box',
                    marginTop: 4,
                  }}
                />
              )}
            </div>
          </div>

          {saveError && (
            <p style={{ color: '#ef9a9a', marginTop: 8 }}>{saveError}</p>
          )}

          <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
            <button
              onClick={handleSave}
              disabled={saving}
              style={{
                background: '#1565c0',
                color: '#fff',
                border: 'none',
                padding: '8px 20px',
                borderRadius: 4,
                cursor: 'pointer',
              }}
            >
              {saving ? 'Saving...' : 'Save'}
            </button>
            {screener && (
              <button
                onClick={handleRun}
                disabled={running || runningId === screener.id}
                style={{
                  background: '#2e7d32',
                  color: '#fff',
                  border: 'none',
                  padding: '8px 20px',
                  borderRadius: 4,
                  cursor: 'pointer',
                }}
              >
                {running ? 'Running...' : 'Run Now'}
              </button>
            )}
            {onCancel && (
              <button
                onClick={onCancel}
                style={{
                  background: '#333',
                  color: '#aaa',
                  border: 'none',
                  padding: '8px 16px',
                  borderRadius: 4,
                  cursor: 'pointer',
                }}
              >
                Cancel
              </button>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
