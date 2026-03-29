import React, { useState } from 'react';
import { ElliottTradeSuggestion, SuggestionState } from '../types';
import SuggestionCard from './SuggestionCard';

interface Props {
  suggestions: ElliottTradeSuggestion[];
  onRefresh: () => void;
  onSuggestionUpdated: (s: ElliottTradeSuggestion) => void;
}

const ALL_STATES = ['ALL', 'PROPOSED', 'ANTICIPATORY', 'ACTIVE', 'SUCCESSFUL', 'FAILED', 'REJECTED'] as const;

function tabColor(state: string): string {
  switch (state) {
    case 'PROPOSED':
      return '#1565c0';
    case 'ANTICIPATORY':
      return '#e65100';
    case 'ACTIVE':
      return '#2e7d32';
    case 'SUCCESSFUL':
      return '#1b5e20';
    case 'FAILED':
      return '#b71c1c';
    case 'REJECTED':
      return '#424242';
    default:
      return '#555';
  }
}

export default function SuggestionListPanel({
  suggestions,
  onRefresh,
  onSuggestionUpdated,
}: Props) {
  const [stateFilter, setStateFilter] = useState<SuggestionState | 'ALL'>('ALL');

  const filtered =
    stateFilter === 'ALL'
      ? suggestions
      : suggestions.filter((s) => s.state === stateFilter);
  const countFor = (state: string) => suggestions.filter((s) => s.state === state).length;

  return (
    <div>
      <div
        style={{
          display: 'flex',
          gap: 8,
          marginBottom: 16,
          flexWrap: 'wrap',
          alignItems: 'center',
        }}
      >
        {ALL_STATES.map((s) => (
          <button
            key={s}
            onClick={() => setStateFilter(s as any)}
            style={{
              background:
                stateFilter === s
                  ? s === 'ALL'
                    ? '#555'
                    : tabColor(s)
                  : '#333',
              color: '#fff',
              border: 'none',
              padding: '4px 12px',
              borderRadius: 4,
              cursor: 'pointer',
              fontWeight: stateFilter === s ? 700 : 400,
            }}
          >
            {s} {s !== 'ALL' && `(${countFor(s)})`}
          </button>
        ))}
        <button
          onClick={onRefresh}
          style={{
            marginLeft: 'auto',
            background: '#333',
            color: '#aaa',
            border: '1px solid #555',
            padding: '4px 12px',
            borderRadius: 4,
            cursor: 'pointer',
          }}
        >
          Refresh
        </button>
      </div>

      {filtered.length === 0 ? (
        <p style={{ color: '#888' }}>No suggestions in this state.</p>
      ) : (
        filtered.map((s) => (
          <SuggestionCard
            key={s.id}
            suggestion={s}
            onUpdated={onSuggestionUpdated}
          />
        ))
      )}
    </div>
  );
}
