import React, { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { fetchTradeById } from "./tradesApi";
import type { Trade } from "./types";
import { PrettyJson } from "../components/PrettyJson";

function formatDate(iso?: string) {
  if (!iso) return "-";
  const d = new Date(iso);
  return d.toLocaleString();
}

export const TradeDetailPage: React.FC = () => {
  const { id } = useParams();
  const [trade, setTrade] = useState<Trade | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let aborted = false;
    (async () => {
      if (!id) return;
      try {
        setLoading(true);
        setError(null);
        const data = await fetchTradeById(id);
        if (!aborted) setTrade(data);
      } catch (e: any) {
        if (!aborted) setError(e?.message || "Unknown error");
      } finally {
        if (!aborted) setLoading(false);
      }
    })();
    return () => {
      aborted = true;
    };
  }, [id]);

  const chartUrl =
    trade != null
      ? `/?script=${encodeURIComponent(trade.script)}&timeframe=${encodeURIComponent(trade.timeframe)}`
      : undefined;

  if (loading) return <div style={{ padding: 16 }}>Loading trade…</div>;
  if (error) return <div style={{ padding: 16 }}>Error: {error}</div>;
  if (!trade) return <div style={{ padding: 16 }}>Trade not found.</div>;

  return (
    <div style={{ padding: 16 }}>
      <div style={{ marginBottom: 12 }}>
        <Link to="/trades">← Back to Trades</Link>
      </div>

      <h1 style={{ marginBottom: 12 }}>Trade #{String(trade.id)}</h1>

      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(260px, 1fr))", gap: 12 }}>
        <div style={{ border: "1px solid #e5e7eb", borderRadius: 8, padding: 12 }}>
          <h3>Overview</h3>
          <div><strong>Script:</strong> {trade.script}</div>
          <div><strong>Timeframe:</strong> {trade.timeframe}</div>
          <div><strong>Side:</strong> {trade.side}</div>
          <div><strong>Open:</strong> {trade.open ? "Yes" : "No"}</div>
          <div><strong>Run ID:</strong> {trade.runId ?? "-"}</div>
          <div><strong>Created:</strong> {formatDate(trade.createdAt)}</div>
          <div><strong>Updated:</strong> {formatDate(trade.updatedAt)}</div>
          <div><strong>Triggered:</strong> {formatDate(trade.timeTriggered)}</div>
        </div>

        <div style={{ border: "1px solid #e5e7eb", borderRadius: 8, padding: 12 }}>
          <h3>Trade Levels</h3>
          <div><strong>Entry:</strong> {trade.entry ?? "-"}</div>
          <div><strong>Target:</strong> {trade.target ?? "-"}</div>
          <div><strong>Stoploss:</strong> {trade.stoploss ?? "-"}</div>
        </div>

        <div style={{ border: "1px solid #e5e7eb", borderRadius: 8, padding: 12 }}>
          <h3>AI Output</h3>
          <div><strong>Entry:</strong> {trade.ai?.entry ?? "-"}</div>
          <div><strong>Target:</strong> {trade.ai?.target ?? "-"}</div>
          <div><strong>Stoploss:</strong> {trade.ai?.stoploss ?? "-"}</div>
          <div><strong>Rationale:</strong></div>
          <div style={{ whiteSpace: "pre-wrap", background: "#f9fafb", padding: 8, borderRadius: 6, marginTop: 4 }}>
            {trade.ai?.rationale ?? "-"}
          </div>
        </div>
      </div>

      <div style={{ marginTop: 12, border: "1px solid #e5e7eb", borderRadius: 8, padding: 12 }}>
        <h3>Logs</h3>
        <pre
          style={{
            whiteSpace: "pre-wrap",
            background: "#0f172a",
            color: "#e2e8f0",
            padding: 12,
            borderRadius: 6,
            overflowX: "auto",
          }}
        >
{trade.logs ?? "-"}
        </pre>
      </div>

      <div style={{ marginTop: 12, border: "1px solid #e5e7eb", borderRadius: 8, padding: 12 }}>
        <h3>Screener Debug</h3>
        <PrettyJson data={trade.screenerDebug ?? {}} />
      </div>

      <div style={{ marginTop: 16, display: "flex", gap: 12 }}>
        {chartUrl && (
          <a href={chartUrl} target="_blank" rel="noreferrer" className="btn">
            Open Chart
          </a>
        )}
        <Link to="/trades" className="btn">Back</Link>
      </div>
    </div>
  );
};
