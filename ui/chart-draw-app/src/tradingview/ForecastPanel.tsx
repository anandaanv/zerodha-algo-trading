import React, { useState, useCallback } from 'react';
import type { ForecastResponse, ModelForecast } from './forecastApi';
import { fetchForecast } from './forecastApi';
import { MODEL_COLORS } from './useForecastOverlay';

interface ForecastPanelProps {
  open: boolean;
  onClose: () => void;
  symbol: string;
  timeframe: string;
  /** Called when new forecast data is ready — parent draws on chart */
  onForecastReady: (data: ForecastResponse) => void;
  /** Called when user clears/closes forecast */
  onForecastClear: () => void;
}

interface ModelToggles {
  chronos: boolean;
  lagLlama: boolean;
  granite: boolean;
}

const HORIZONS = [6, 12, 24, 48];

export default function ForecastPanel({
  open,
  onClose,
  symbol,
  timeframe,
  onForecastReady,
  onForecastClear,
}: ForecastPanelProps) {
  const [horizon, setHorizon] = useState(12);
  const [toggles, setToggles] = useState<ModelToggles>({
    chronos: true,
    lagLlama: true,
    granite: true,
  });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lastForecast, setLastForecast] = useState<ForecastResponse | null>(null);

  const runForecast = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await fetchForecast(symbol, timeframe, horizon);
      setLastForecast(data);
      onForecastReady(data);
    } catch (e: any) {
      setError(e.message || 'Forecast failed');
    } finally {
      setLoading(false);
    }
  }, [symbol, timeframe, horizon, onForecastReady]);

  const handleClear = () => {
    setLastForecast(null);
    setError(null);
    onForecastClear();
  };

  if (!open) return null;

  return (
    <div style={styles.panel}>
      {/* Header */}
      <div style={styles.header}>
        <span style={styles.title}>TSFM Forecast</span>
        <button style={styles.closeBtn} onClick={() => { handleClear(); onClose(); }} title="Close">×</button>
      </div>

      {/* Symbol + Timeframe info */}
      <div style={styles.info}>
        <span style={styles.infoChip}>{symbol}</span>
        <span style={styles.infoChip}>{timeframe}</span>
      </div>

      {/* Horizon selector */}
      <div style={styles.section}>
        <div style={styles.sectionLabel}>Horizon (candles)</div>
        <div style={styles.horizonRow}>
          {HORIZONS.map(h => (
            <button
              key={h}
              style={{ ...styles.horizonBtn, ...(horizon === h ? styles.horizonBtnActive : {}) }}
              onClick={() => setHorizon(h)}
            >{h}</button>
          ))}
        </div>
      </div>

      {/* Model toggles */}
      <div style={styles.section}>
        <div style={styles.sectionLabel}>Models</div>
        {(['chronos', 'lagLlama', 'granite'] as const).map(key => {
          const labels: Record<string, string> = {
            chronos: 'Chronos-Bolt',
            lagLlama: 'Lag-Llama',
            granite: 'Granite TTM',
          };
          return (
            <label key={key} style={styles.toggleRow}>
              <input
                type="checkbox"
                checked={toggles[key]}
                onChange={() => setToggles(prev => ({ ...prev, [key]: !prev[key] }))}
                style={{ marginRight: 8 }}
              />
              <span
                style={{
                  display: 'inline-block',
                  width: 10,
                  height: 10,
                  borderRadius: '50%',
                  backgroundColor: MODEL_COLORS[key],
                  marginRight: 7,
                  flexShrink: 0,
                }}
              />
              <span style={styles.toggleLabel}>{labels[key]}</span>
            </label>
          );
        })}
      </div>

      {/* Run button */}
      <button
        style={{ ...styles.runBtn, opacity: loading ? 0.6 : 1 }}
        onClick={runForecast}
        disabled={loading}
      >
        {loading ? 'Running...' : 'Run Forecast'}
      </button>

      {lastForecast && !loading && (
        <button style={styles.clearBtn} onClick={handleClear}>
          Clear Overlay
        </button>
      )}

      {/* Error */}
      {error && (
        <div style={styles.errorBox}>{error}</div>
      )}

      {/* Convergence metrics */}
      {lastForecast && !loading && (
        <div style={styles.section}>
          <div style={styles.sectionLabel}>Convergence (triangle signal)</div>
          {(['chronos', 'lagLlama', 'granite'] as const).map(key => {
            const modelLabels: Record<string, string> = {
              chronos: 'Chronos',
              lagLlama: 'Lag-Llama',
              granite: 'Granite',
            };
            const model: ModelForecast = lastForecast[key as keyof Pick<ForecastResponse, 'chronos' | 'lagLlama' | 'granite'>];
            if (!model.available) {
              return (
                <div key={key} style={styles.metricRow}>
                  <span style={{ ...styles.metricLabel, color: MODEL_COLORS[key] }}>{modelLabels[key]}</span>
                  <span style={styles.metricUnavail}>offline</span>
                </div>
              );
            }
            const pct = Math.round(model.convergenceMetric * 100);
            return (
              <div key={key} style={styles.metricRow}>
                <span style={{ ...styles.metricLabel, color: MODEL_COLORS[key] }}>{modelLabels[key]}</span>
                <div style={styles.barBg}>
                  <div
                    style={{
                      ...styles.barFill,
                      width: `${pct}%`,
                      backgroundColor: pct > 60 ? '#f44336' : pct > 40 ? '#ff9800' : MODEL_COLORS[key],
                    }}
                  />
                </div>
                <span style={styles.metricPct}>{pct}%</span>
              </div>
            );
          })}
          {(() => {
            const models = ['chronos', 'lagLlama', 'granite'] as const;
            const available = models.filter(k => lastForecast[k].available);
            if (available.length === 0) return null;
            const avg = available.reduce((s, k) => s + lastForecast[k].convergenceMetric, 0) / available.length;
            const avgPct = Math.round(avg * 100);
            return (
              <div style={styles.consensusRow}>
                <span style={styles.consensusLabel}>Consensus:</span>
                <span style={{
                  ...styles.consensusBadge,
                  backgroundColor: avgPct > 60 ? '#f44336' : avgPct > 40 ? '#ff9800' : '#388e3c',
                }}>
                  {avgPct > 60 ? 'TRIANGLE' : avgPct > 40 ? 'FORMING' : 'TREND'} {avgPct}%
                </span>
              </div>
            );
          })()}
        </div>
      )}

      {/* Legend */}
      <div style={styles.legend}>
        <div style={styles.legendRow}>
          <div style={{ ...styles.legendLine, backgroundColor: MODEL_COLORS.chronos }} />
          <span>Chronos median</span>
        </div>
        <div style={styles.legendRow}>
          <div style={{ ...styles.legendLine, backgroundColor: MODEL_COLORS.lagLlama }} />
          <span>Lag-Llama median</span>
        </div>
        <div style={styles.legendRow}>
          <div style={{ ...styles.legendLine, backgroundColor: MODEL_COLORS.granite }} />
          <span>Granite TTM median</span>
        </div>
        <div style={{ ...styles.legendRow, opacity: 0.6 }}>
          <div style={{ ...styles.legendLine, borderTop: '1px dashed #aaa', backgroundColor: 'transparent' }} />
          <span>10/90 pct bands (dashed)</span>
        </div>
      </div>
    </div>
  );
}

// ─── Styles ──────────────────────────────────────────────────────────────────

const styles: Record<string, React.CSSProperties> = {
  panel: {
    width: '100%',
    height: '100%',
    backgroundColor: '#1a1d2e',
    color: 'rgba(255,255,255,0.87)',
    display: 'flex',
    flexDirection: 'column',
    overflowY: 'auto',
    fontSize: 13,
    userSelect: 'none',
  },
  header: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    padding: '12px 14px 8px',
    borderBottom: '1px solid rgba(255,255,255,0.1)',
    flexShrink: 0,
  },
  title: {
    fontWeight: 700,
    fontSize: 14,
    letterSpacing: '0.5px',
    color: '#fff',
  },
  closeBtn: {
    background: 'none',
    border: 'none',
    color: 'rgba(255,255,255,0.5)',
    fontSize: 20,
    cursor: 'pointer',
    padding: '0 4px',
    lineHeight: 1,
  },
  info: {
    display: 'flex',
    gap: 8,
    padding: '8px 14px',
    flexShrink: 0,
  },
  infoChip: {
    backgroundColor: 'rgba(255,255,255,0.1)',
    borderRadius: 4,
    padding: '2px 8px',
    fontSize: 12,
    fontWeight: 600,
  },
  section: {
    padding: '10px 14px',
    borderBottom: '1px solid rgba(255,255,255,0.07)',
    flexShrink: 0,
  },
  sectionLabel: {
    fontSize: 11,
    fontWeight: 600,
    color: 'rgba(255,255,255,0.45)',
    textTransform: 'uppercase',
    letterSpacing: '0.8px',
    marginBottom: 8,
  },
  horizonRow: {
    display: 'flex',
    gap: 6,
  },
  horizonBtn: {
    flex: 1,
    padding: '5px 0',
    backgroundColor: 'rgba(255,255,255,0.08)',
    border: '1px solid rgba(255,255,255,0.12)',
    borderRadius: 4,
    color: 'rgba(255,255,255,0.7)',
    cursor: 'pointer',
    fontSize: 12,
    fontWeight: 600,
    transition: 'all 0.15s',
  },
  horizonBtnActive: {
    backgroundColor: '#1565c0',
    borderColor: '#1976d2',
    color: '#fff',
  },
  toggleRow: {
    display: 'flex',
    alignItems: 'center',
    padding: '4px 0',
    cursor: 'pointer',
    fontSize: 13,
    color: 'rgba(255,255,255,0.8)',
  },
  toggleLabel: {
    fontWeight: 500,
  },
  runBtn: {
    margin: '10px 14px 6px',
    padding: '9px',
    backgroundColor: '#1976d2',
    color: '#fff',
    border: 'none',
    borderRadius: 5,
    cursor: 'pointer',
    fontWeight: 700,
    fontSize: 13,
    flexShrink: 0,
    transition: 'opacity 0.15s',
  },
  clearBtn: {
    margin: '0 14px 10px',
    padding: '6px',
    backgroundColor: 'rgba(255,255,255,0.08)',
    color: 'rgba(255,255,255,0.6)',
    border: '1px solid rgba(255,255,255,0.15)',
    borderRadius: 5,
    cursor: 'pointer',
    fontSize: 12,
    flexShrink: 0,
  },
  errorBox: {
    margin: '4px 14px',
    padding: '8px 10px',
    backgroundColor: 'rgba(244,67,54,0.15)',
    border: '1px solid rgba(244,67,54,0.3)',
    borderRadius: 4,
    color: '#ef9a9a',
    fontSize: 12,
  },
  metricRow: {
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    marginBottom: 7,
  },
  metricLabel: {
    width: 80,
    fontSize: 11,
    fontWeight: 600,
    flexShrink: 0,
  },
  metricUnavail: {
    fontSize: 11,
    color: 'rgba(255,255,255,0.3)',
    fontStyle: 'italic',
  },
  barBg: {
    flex: 1,
    height: 6,
    backgroundColor: 'rgba(255,255,255,0.1)',
    borderRadius: 3,
    overflow: 'hidden',
  },
  barFill: {
    height: '100%',
    borderRadius: 3,
    transition: 'width 0.4s ease',
  },
  metricPct: {
    width: 32,
    textAlign: 'right',
    fontSize: 11,
    color: 'rgba(255,255,255,0.6)',
    flexShrink: 0,
  },
  consensusRow: {
    display: 'flex',
    alignItems: 'center',
    gap: 10,
    marginTop: 10,
  },
  consensusLabel: {
    fontSize: 11,
    fontWeight: 600,
    color: 'rgba(255,255,255,0.45)',
    textTransform: 'uppercase',
    letterSpacing: '0.5px',
  },
  consensusBadge: {
    padding: '3px 10px',
    borderRadius: 12,
    fontSize: 11,
    fontWeight: 700,
    color: '#fff',
    letterSpacing: '0.5px',
  },
  legend: {
    padding: '12px 14px',
    flexShrink: 0,
    marginTop: 'auto',
    borderTop: '1px solid rgba(255,255,255,0.07)',
  },
  legendRow: {
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    marginBottom: 6,
    fontSize: 11,
    color: 'rgba(255,255,255,0.5)',
  },
  legendLine: {
    width: 22,
    height: 2,
    borderRadius: 1,
    flexShrink: 0,
  },
};
