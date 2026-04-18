import React, { useState } from "react";
import { Link } from "react-router-dom";
import type { Trade } from "./types";
import { TradeActionTimeline } from "./TradeActionTimeline";

type Props = {
  trades: Trade[];
  loading?: boolean;
  error?: string | null;
};

function formatDate(iso?: string) {
  if (!iso) return "-";
  const d = new Date(iso);
  return d.toLocaleString();
}

export const TradesTable: React.FC<Props> = ({ trades, loading, error }) => {
  const [expandedId, setExpandedId] = useState<number | string | null>(null);

  const toggleExpand = (tradeId: number | string) => {
    setExpandedId(prev => prev === tradeId ? null : tradeId);
  };

  if (loading) return <div>Loading trades…</div>;
  if (error) return <div className="error">Error: {error}</div>;
  if (!trades || trades.length === 0) return <div>No trades found.</div>;

  return (
    <div className="trades-table-wrapper">
      <table className="trades-table" style={{ width: "100%", borderCollapse: "collapse" }}>
        <thead>
          <tr>
            <th style={{ textAlign: "left", padding: "8px" }}>Date</th>
            <th style={{ textAlign: "left", padding: "8px" }}>Script</th>
            <th style={{ textAlign: "left", padding: "8px" }}>Timeframe</th>
            <th style={{ textAlign: "left", padding: "8px" }}>Side</th>
            <th style={{ textAlign: "right", padding: "8px" }}>Entry</th>
            <th style={{ textAlign: "right", padding: "8px" }}>Target</th>
            <th style={{ textAlign: "right", padding: "8px" }}>Stoploss</th>
            <th style={{ textAlign: "left", padding: "8px" }}>Confidence</th>
            <th style={{ textAlign: "left", padding: "8px" }}>Status</th>
            <th style={{ textAlign: "left", padding: "8px" }}>Open</th>
            <th style={{ textAlign: "left", padding: "8px" }}>Run ID</th>
            <th style={{ textAlign: "left", padding: "8px" }}>Actions</th>
          </tr>
        </thead>
        <tbody>
          {trades.map((t) => {
            const chartUrl = `/?script=${encodeURIComponent(t.script)}&timeframe=${encodeURIComponent(
              t.timeframe
            )}`;
            const isExpanded = expandedId === t.id;
            return (
              <React.Fragment key={String(t.id)}>
                <tr
                  style={{ borderTop: "1px solid #e5e7eb", cursor: "pointer" }}
                  onClick={() => toggleExpand(t.id)}
                >
                  <td style={{ padding: "8px" }}>
                    <span style={{ marginRight: "6px", fontSize: "10px" }}>{isExpanded ? "▼" : "▶"}</span>
                    {formatDate(t.timeTriggered || t.createdAt)}
                  </td>
                  <td style={{ padding: "8px", fontWeight: 500 }}>{t.script}</td>
                  <td style={{ padding: "8px" }}>{t.timeframe}</td>
                  <td style={{ padding: "8px" }}>{t.side}</td>
                  <td style={{ padding: "8px", textAlign: "right" }}>{t.entry ?? "-"}</td>
                  <td style={{ padding: "8px", textAlign: "right" }}>{t.target ?? "-"}</td>
                  <td style={{ padding: "8px", textAlign: "right" }}>{t.stoploss ?? "-"}</td>
                  <td style={{ padding: "8px" }}>{t.confidence ?? "-"}</td>
                  <td style={{ padding: "8px" }}>{t.status ?? "-"}</td>
                  <td style={{ padding: "8px" }}>{t.open ? "Yes" : "No"}</td>
                  <td style={{ padding: "8px" }}>{t.runId ?? "-"}</td>
                  <td style={{ padding: "8px", display: "flex", gap: 8 }} onClick={(e) => e.stopPropagation()}>
                    <Link to={`/trades/${t.id}`} title="View details">Details</Link>
                    <a href={chartUrl} target="_blank" rel="noreferrer" title="Open chart">
                      Chart
                    </a>
                  </td>
                </tr>
                {isExpanded && (
                  <tr>
                    <td colSpan={12} style={{ padding: 0 }}>
                      <TradeActionTimeline signalId={Number(t.id)} />
                    </td>
                  </tr>
                )}
              </React.Fragment>
            );
          })}
        </tbody>
      </table>
    </div>
  );
};
