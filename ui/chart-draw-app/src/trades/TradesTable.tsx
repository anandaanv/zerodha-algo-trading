import React from "react";
import { Link } from "react-router-dom";
import type { Trade } from "./types";

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
            return (
              <tr key={String(t.id)} style={{ borderTop: "1px solid #e5e7eb" }}>
                <td style={{ padding: "8px" }}>{formatDate(t.timeTriggered || t.createdAt)}</td>
                <td style={{ padding: "8px", fontWeight: 500 }}>{t.script}</td>
                <td style={{ padding: "8px" }}>{t.timeframe}</td>
                <td style={{ padding: "8px" }}>{t.side}</td>
                <td style={{ padding: "8px", textAlign: "right" }}>{t.entry ?? "-"}</td>
                <td style={{ padding: "8px", textAlign: "right" }}>{t.target ?? "-"}</td>
                <td style={{ padding: "8px", textAlign: "right" }}>{t.stoploss ?? "-"}</td>
                <td style={{ padding: "8px" }}>{t.open ? "Yes" : "No"}</td>
                <td style={{ padding: "8px" }}>{t.runId ?? "-"}</td>
                <td style={{ padding: "8px", display: "flex", gap: 8 }}>
                  <Link to={`/trades/${t.id}`} title="View details">Details</Link>
                  <a href={chartUrl} target="_blank" rel="noreferrer" title="Open chart">
                    Chart
                  </a>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
};
