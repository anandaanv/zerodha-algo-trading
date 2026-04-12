import React, { useEffect, useState } from "react";
import { PatternScreenerRunResult } from "../types";
import { getRunResults } from "../api";

const STATUS_COLORS: Record<string, string> = {
  NO_PATTERN: "#666",
  PATTERN_FOUND: "#ffa726",
  SIGNAL_CREATED: "#4caf50",
  DUPLICATE: "#42a5f5",
  FILTERED: "#ab47bc",
  ERROR: "#ef5350",
};

export default function PatternRunResultsPanel({
  screenerId,
  runId,
}: {
  screenerId: number;
  runId: number;
}) {
  const [results, setResults] = useState<PatternScreenerRunResult[]>([]);
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState<string>("ALL");

  useEffect(() => {
    getRunResults(screenerId, runId)
      .then(setResults)
      .catch(console.error)
      .finally(() => setLoading(false));
  }, [screenerId, runId]);

  const filtered = filter === "ALL" ? results : results.filter((r) => r.status === filter);
  const counts = results.reduce(
    (acc, r) => {
      acc[r.status] = (acc[r.status] || 0) + 1;
      return acc;
    },
    {} as Record<string, number>
  );

  const cell: React.CSSProperties = {
    padding: "8px 12px",
    borderBottom: "1px solid #2a2a2a",
    fontSize: 13,
  };

  if (loading) return <p style={{ color: "#aaa", padding: 16 }}>Loading results...</p>;

  return (
    <div>
      {/* Filter bar */}
      <div style={{ display: "flex", gap: 8, marginBottom: 12, flexWrap: "wrap" }}>
        {["ALL", "SIGNAL_CREATED", "PATTERN_FOUND", "DUPLICATE", "NO_PATTERN", "ERROR"].map(
          (s) => (
            <button
              key={s}
              onClick={() => setFilter(s)}
              style={{
                background: filter === s ? "#1565c0" : "#2a2a2a",
                color: s === "ALL" ? "#fff" : STATUS_COLORS[s] || "#fff",
                border: "1px solid #444",
                borderRadius: 4,
                padding: "4px 10px",
                cursor: "pointer",
                fontSize: 12,
              }}
            >
              {s}
              {s !== "ALL" && counts[s] ? ` (${counts[s]})` : ""}
            </button>
          )
        )}
      </div>

      <table style={{ width: "100%", borderCollapse: "collapse" }}>
        <thead>
          <tr style={{ background: "#1a1a1a" }}>
            {["Symbol", "Status", "Patterns", "Signal ID", "Time (ms)"].map((h) => (
              <th
                key={h}
                style={{
                  ...cell,
                  color: "#90caf9",
                  textAlign: "left",
                  borderBottom: "2px solid #333",
                }}
              >
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {filtered.map((r) => {
            let patterns: any[] = [];
            try {
              patterns = JSON.parse(r.patternsFound || "[]");
            } catch {}
            return (
              <tr key={r.id} style={{ background: "#1e1e1e" }}>
                <td style={{ ...cell, fontWeight: 600 }}>{r.symbol}</td>
                <td style={{ ...cell }}>
                  <span
                    style={{
                      color: STATUS_COLORS[r.status] || "#fff",
                      fontWeight: 600,
                    }}
                  >
                    {r.status}
                  </span>
                  {r.errorMessage && (
                    <div style={{ color: "#ef9a9a", fontSize: 11, marginTop: 2 }}>
                      {r.errorMessage}
                    </div>
                  )}
                </td>
                <td style={{ ...cell, color: "#aaa", fontSize: 12 }}>
                  {patterns.length > 0
                    ? patterns.map((p: any) => `${p.patternType} (${p.bullish ? "↑" : "↓"})`).join(", ")
                    : "—"}
                </td>
                <td style={{ ...cell }}>
                  {r.signalId ? (
                    <span style={{ color: "#4caf50", fontWeight: 600 }}>#{r.signalId}</span>
                  ) : (
                    <span style={{ color: "#555" }}>—</span>
                  )}
                </td>
                <td style={{ ...cell, color: "#666" }}>{r.processingMs}</td>
              </tr>
            );
          })}
          {filtered.length === 0 && (
            <tr>
              <td colSpan={5} style={{ ...cell, color: "#555", textAlign: "center" }}>
                No results
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );
}
