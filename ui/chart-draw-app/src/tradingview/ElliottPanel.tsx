import React, { useState } from 'react';
import type {
  AdvancedElliottResult,
  ElliottEntryCandidate,
  ElliottScoredScenario,
  ElliottConfluenceZone,
  ScenarioStatus,
  VerifiedElliottResult,
  ScenarioFamilyCandidate,
  HumanResearchSummary,
} from './copilotTypes';

interface Props {
  result: AdvancedElliottResult | null;
  loading: boolean;
  error: string | null;
  verifiedResult?: VerifiedElliottResult | null;
  verifiedLoading?: boolean;
  verifiedError?: string | null;
}

const STATUS_COLOR: Record<ScenarioStatus, string> = {
  LEADING:          '#1b5e20',
  ACTIVE_ALTERNATE: '#1565c0',
  WEAK_ALTERNATE:   '#e65100',
  AWAITING_TRIGGER: '#37474f',
  INVALIDATED:      '#b71c1c',
  COMPLETED:        '#4a148c',
};

const ENTRY_STYLE_COLOR: Record<string, string> = {
  AGGRESSIVE:   '#b71c1c',
  MODERATE:     '#e65100',
  CONSERVATIVE: '#1565c0',
};

export default function ElliottPanel({
  result, loading, error,
  verifiedResult, verifiedLoading, verifiedError,
}: Props) {
  const [promptOpen, setPromptOpen] = useState(false);
  const [expandedScenarioId, setExpandedScenarioId] = useState<string | null>(null);
  const [aiOpen, setAiOpen] = useState(false);

  if (loading) {
    return (
      <div style={{ padding: '12px', color: '#90caf9', fontSize: 13 }}>
        Running Elliott analysis…
      </div>
    );
  }

  if (error) {
    return (
      <div style={{ padding: '12px', color: '#ef9a9a', fontSize: 13 }}>
        Error: {error}
      </div>
    );
  }

  if (!result) return null;

  const { entryCandidates, scoredScenarios, confluenceZones, hypothesisSnapshot, promptSummary } = result;

  return (
    <div style={{ padding: '8px 12px', fontSize: 12, color: '#e0e0e0' }}>

      {/* ── Section A: Entry Candidates ── */}
      <div style={{ marginBottom: 12 }}>
        <div style={{ fontWeight: 700, color: '#90caf9', marginBottom: 6, fontSize: 13 }}>
          Entry Candidates ({entryCandidates.length})
        </div>
        {entryCandidates.length === 0 ? (
          <div style={{ color: '#757575', fontStyle: 'italic' }}>No entry candidates</div>
        ) : (
          entryCandidates.map(ec => (
            <EntryCard key={ec.id} candidate={ec} />
          ))
        )}
      </div>

      {/* ── Section B: Scored Scenarios ── */}
      <div style={{ marginBottom: 12 }}>
        <div style={{ fontWeight: 700, color: '#90caf9', marginBottom: 6, fontSize: 13 }}>
          Scenarios ({scoredScenarios.length})
        </div>
        {scoredScenarios.length === 0 ? (
          <div style={{ color: '#757575', fontStyle: 'italic' }}>No scenarios</div>
        ) : (
          scoredScenarios.map(ss => (
            <ScenarioRow
              key={ss.scenario?.id ?? ss.status}
              ss={ss}
              expanded={expandedScenarioId === ss.scenario?.id}
              onToggle={() => setExpandedScenarioId(
                expandedScenarioId === ss.scenario?.id ? null : ss.scenario?.id
              )}
            />
          ))
        )}
      </div>

      {/* ── Section C: Confluence Zones ── */}
      <div style={{ marginBottom: 12 }}>
        <div style={{ fontWeight: 700, color: '#90caf9', marginBottom: 6, fontSize: 13 }}>
          Confluence Zones
        </div>
        {confluenceZones.filter(z => z.score >= 2 && z.zoneType !== 'SR_ONLY').length === 0 ? (
          <div style={{ color: '#757575', fontStyle: 'italic' }}>No significant zones</div>
        ) : (
          confluenceZones
            .filter(z => z.score >= 2 && z.zoneType !== 'SR_ONLY')
            .map(z => <ZoneRow key={z.id} zone={z} />)
        )}
      </div>

      {/* ── Section D: Hypothesis State ── */}
      {hypothesisSnapshot && (
        <div style={{ marginBottom: 12 }}>
          <div style={{ fontWeight: 700, color: '#90caf9', marginBottom: 6, fontSize: 13 }}>
            State Tracker
          </div>
          <div style={{ display: 'flex', gap: 12, marginBottom: 4 }}>
            <span>
              Relabels:{' '}
              <span style={{ color: hypothesisSnapshot.relabelCount > 2 ? '#ef9a9a' : '#a5d6a7', fontWeight: 600 }}>
                {hypothesisSnapshot.relabelCount}
              </span>
            </span>
            {hypothesisSnapshot.leadingScenarioId && (
              <span>Leading: <span style={{ color: '#a5d6a7' }}>{hypothesisSnapshot.leadingScenarioId}</span></span>
            )}
          </div>
          {hypothesisSnapshot.transitions.slice(-3).map((t, i) => (
            <div key={i} style={{ color: '#bdbdbd', marginBottom: 2 }}>
              {t.scenarioId}: {t.fromStatus ?? '∅'} → <span style={{ color: STATUS_COLOR[t.toStatus] ?? '#fff' }}>{t.toStatus}</span>
              {t.reason && <span style={{ color: '#757575' }}> ({t.reason})</span>}
            </div>
          ))}
        </div>
      )}

      {/* ── Section E: Prompt Summary ── */}
      {promptSummary && (
        <div style={{ marginBottom: 8 }}>
          <button
            onClick={() => setPromptOpen(p => !p)}
            style={{
              background: 'none', border: '1px solid #424242', color: '#bdbdbd',
              cursor: 'pointer', padding: '2px 8px', borderRadius: 3, fontSize: 11,
            }}
          >
            {promptOpen ? '▾' : '▸'} Raw AI Prompt Summary
          </button>
          {promptOpen && (
            <pre style={{
              marginTop: 6, padding: 8, background: '#1a1a1a', borderRadius: 4,
              fontSize: 10, color: '#9e9e9e', overflowX: 'auto', maxHeight: 200,
              overflowY: 'auto', whiteSpace: 'pre-wrap',
            }}>
              {promptSummary}
            </pre>
          )}
        </div>
      )}

      {/* ── Section F: Verified Elliott (Second Pass + AI) ── */}
      {verifiedLoading && (
        <div style={{ padding: '8px 0', color: '#90caf9', fontSize: 12 }}>Running verified analysis…</div>
      )}
      {verifiedError && (
        <div style={{ padding: '8px 0', color: '#ef9a9a', fontSize: 12 }}>Verified error: {verifiedError}</div>
      )}
      {verifiedResult && <VerifiedSection verified={verifiedResult} aiOpen={aiOpen} onToggleAi={() => setAiOpen(o => !o)} />}
    </div>
  );
}

// ── Verified Section ──────────────────────────────────────────────────────────

function VerifiedSection({
  verified, aiOpen, onToggleAi,
}: { verified: VerifiedElliottResult; aiOpen: boolean; onToggleAi: () => void }) {
  const { filteredScenarioSet: fss, aiConfirmed, aiReasoning, aiConfidence } = verified;
  const humanSummary = fss?.humanSummary;
  const leading = fss?.leadingScenario;

  return (
    <div style={{ borderTop: '1px solid #333', paddingTop: 8, marginTop: 4 }}>
      <div style={{ fontWeight: 700, color: '#ce93d8', marginBottom: 6, fontSize: 13 }}>
        Verified Analysis (2nd Pass + AI)
      </div>

      {/* Market state summary */}
      {humanSummary?.marketStateSummary && (
        <div style={{
          background: '#1a1a2e', border: '1px solid #3f2080', borderRadius: 4,
          padding: '6px 10px', marginBottom: 8, color: '#e1bee7', fontSize: 12,
        }}>
          {humanSummary.marketStateSummary}
        </div>
      )}

      {/* AI confirmation badge */}
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 8 }}>
        <span style={{
          background: aiConfirmed ? '#1b5e20' : '#b71c1c',
          color: '#fff', padding: '2px 8px', borderRadius: 3, fontSize: 11, fontWeight: 700,
        }}>
          AI {aiConfirmed ? '✓ CONFIRMED' : '✗ CHALLENGED'}
        </span>
        <span style={{ color: '#bdbdbd', fontSize: 11 }}>
          Confidence: {(aiConfidence * 100).toFixed(0)}%
        </span>
      </div>

      {/* Leading scenario family */}
      {leading && <FamilyCard family={leading} label="Leading" />}

      {/* Active alternates */}
      {fss?.activeAlternates?.length > 0 && (
        <div style={{ marginBottom: 6 }}>
          <div style={{ color: '#90caf9', fontSize: 11, marginBottom: 3 }}>
            Alternates ({fss.activeAlternates.length})
          </div>
          {fss.activeAlternates.map(f => <FamilyCard key={f.id} family={f} label="Alt" />)}
        </div>
      )}

      {/* Action notes */}
      {humanSummary?.actionHandlingNotes?.length > 0 && (
        <div style={{ marginBottom: 6 }}>
          <div style={{ color: '#ffcc80', fontSize: 11, marginBottom: 2 }}>Actions</div>
          {humanSummary.actionHandlingNotes.map((n, i) => (
            <div key={i} style={{ color: '#bdbdbd', fontSize: 11, paddingLeft: 8 }}>• {n}</div>
          ))}
        </div>
      )}

      {/* AI reasoning collapsible */}
      {aiReasoning && (
        <div style={{ marginTop: 4 }}>
          <button
            onClick={onToggleAi}
            style={{
              background: 'none', border: '1px solid #424242', color: '#bdbdbd',
              cursor: 'pointer', padding: '2px 8px', borderRadius: 3, fontSize: 11,
            }}
          >
            {aiOpen ? '▾' : '▸'} AI Reasoning
          </button>
          {aiOpen && (
            <pre style={{
              marginTop: 6, padding: 8, background: '#1a1a1a', borderRadius: 4,
              fontSize: 10, color: '#9e9e9e', overflowX: 'auto', maxHeight: 150,
              overflowY: 'auto', whiteSpace: 'pre-wrap',
            }}>
              {aiReasoning}
            </pre>
          )}
        </div>
      )}
    </div>
  );
}

function FamilyCard({ family, label }: { family: ScenarioFamilyCandidate; label: string }) {
  const dirColor = family.directionalBias === 'UP' ? '#a5d6a7'
    : family.directionalBias === 'DOWN' ? '#ef9a9a' : '#ffcc80';
  return (
    <div style={{
      background: '#1e1e2e', border: '1px solid #333', borderRadius: 4,
      padding: '5px 8px', marginBottom: 4,
    }}>
      <div style={{ display: 'flex', gap: 6, alignItems: 'center', marginBottom: 2 }}>
        <span style={{
          background: '#3f2080', color: '#e1bee7',
          padding: '1px 5px', borderRadius: 3, fontSize: 10,
        }}>{label}</span>
        <span style={{ color: dirColor, fontWeight: 700, fontSize: 11 }}>
          {family.directionalBias}
        </span>
        <span style={{ color: '#bdbdbd', fontSize: 11 }}>
          {family.familyType.replace(/_/g, ' ')}
        </span>
        <span style={{ color: '#90caf9', fontSize: 10, marginLeft: 'auto' }}>
          {family.score?.finalRankScore?.toFixed(2) ?? '—'}
        </span>
      </div>
      <div style={{ color: '#757575', fontSize: 10 }}>
        Inv: {family.primaryInvalidationLevel.toFixed(2)}
        {family.triggerEligible && <span style={{ color: '#a5d6a7', marginLeft: 6 }}>● Trigger</span>}
        {family.tradableNow && <span style={{ color: '#ffcc80', marginLeft: 6 }}>● Now</span>}
      </div>
    </div>
  );
}

// ── Entry Card ────────────────────────────────────────────────────────────────

function EntryCard({ candidate }: { candidate: ElliottEntryCandidate }) {
  const dirColor = candidate.bullish ? '#a5d6a7' : '#ef9a9a';
  const dirLabel = candidate.bullish ? 'LONG' : 'SHORT';
  const styleColor = ENTRY_STYLE_COLOR[candidate.entryStyle] ?? '#90caf9';

  return (
    <div style={{
      background: '#1e2a1e', border: '1px solid #2e4a2e', borderRadius: 4,
      padding: '6px 10px', marginBottom: 6,
    }}>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 4 }}>
        <span style={{ background: dirColor, color: '#000', fontWeight: 700, padding: '1px 6px', borderRadius: 3, fontSize: 11 }}>
          {dirLabel}
        </span>
        <span style={{ background: styleColor, color: '#fff', padding: '1px 5px', borderRadius: 3, fontSize: 10 }}>
          {candidate.entryStyle}
        </span>
        <span style={{ color: '#bdbdbd', fontSize: 11 }}>{candidate.triggerType}</span>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4,1fr)', gap: 4, fontSize: 11 }}>
        <span>Entry <b style={{ color: '#fff' }}>{candidate.entryPrice.toFixed(2)}</b></span>
        <span>SL <b style={{ color: '#ef9a9a' }}>{candidate.stopLoss.toFixed(2)}</b></span>
        <span>T1 <b style={{ color: '#a5d6a7' }}>{candidate.target1.toFixed(2)}</b></span>
        <span>R:R <b style={{ color: '#90caf9' }}>{candidate.riskRewardRatio.toFixed(2)}</b></span>
      </div>
      {candidate.rationale.length > 0 && (
        <ul style={{ margin: '4px 0 0', paddingLeft: 16, color: '#9e9e9e', fontSize: 10 }}>
          {candidate.rationale.map((r, i) => <li key={i}>{r}</li>)}
        </ul>
      )}
    </div>
  );
}

// ── Scenario Row ──────────────────────────────────────────────────────────────

function ScenarioRow({
  ss, expanded, onToggle,
}: {
  ss: ElliottScoredScenario;
  expanded: boolean;
  onToggle: () => void;
}) {
  const statusColor = STATUS_COLOR[ss.status] ?? '#757575';

  return (
    <div style={{ marginBottom: 4 }}>
      <div
        onClick={onToggle}
        style={{
          display: 'flex', gap: 8, alignItems: 'center', cursor: 'pointer',
          background: '#1a1a1a', padding: '4px 8px', borderRadius: 3,
        }}
      >
        <span style={{
          background: statusColor, color: '#fff', fontSize: 10,
          padding: '1px 5px', borderRadius: 2, minWidth: 100, textAlign: 'center',
        }}>
          {ss.status}
        </span>
        <span style={{ color: '#bdbdbd', flex: 1 }}>{ss.scenario?.direction ?? '—'}</span>
        <span style={{ color: '#90caf9' }}>{ss.totalScore.toFixed(1)}</span>
        <span style={{ color: '#616161' }}>{expanded ? '▾' : '▸'}</span>
      </div>
      {expanded && ss.scenario?.hypotheses?.map(h => (
        <div key={h.id} style={{
          background: '#111', padding: '4px 10px', marginTop: 2, borderRadius: 3,
          borderLeft: '2px solid ' + statusColor, fontSize: 11, color: '#9e9e9e',
        }}>
          <div>{h.currentPositionDescription}</div>
          <div style={{ display: 'flex', gap: 12, marginTop: 2 }}>
            <span>SL: <b style={{ color: '#ef9a9a' }}>{h.invalidationLevel.toFixed(2)}</b></span>
            {h.primaryTarget && (
              <span>T: <b style={{ color: '#a5d6a7' }}>{h.primaryTarget.level.toFixed(2)}</b></span>
            )}
            <span>Score: {h.totalScore.toFixed(1)}</span>
          </div>
        </div>
      ))}
    </div>
  );
}

// ── Zone Row ─────────────────────────────────────────────────────────────────

function ZoneRow({ zone }: { zone: ElliottConfluenceZone }) {
  const isDecision = zone.zoneType === 'DECISION';
  return (
    <div style={{
      display: 'flex', gap: 8, alignItems: 'center',
      padding: '3px 6px', marginBottom: 2,
      background: isDecision ? '#1a1500' : '#1a1a1a', borderRadius: 3,
      borderLeft: isDecision ? '2px solid #f9a825' : '2px solid #424242',
    }}>
      <span style={{ color: '#bdbdbd', minWidth: 120 }}>
        {zone.lowerPrice.toFixed(2)} — {zone.upperPrice.toFixed(2)}
      </span>
      <span style={{
        background: isDecision ? '#f9a825' : '#424242', color: isDecision ? '#000' : '#bdbdbd',
        padding: '0 4px', borderRadius: 2, fontSize: 10,
      }}>
        {zone.score.toFixed(1)}
      </span>
      <span style={{ color: '#757575', fontSize: 10 }}>{zone.factorCount}f</span>
      <span style={{ color: '#757575', fontSize: 10, flex: 1 }}>{zone.zoneType}</span>
    </div>
  );
}
