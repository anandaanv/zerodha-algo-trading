import React, { useState } from "react";
import { PatternScreener, PatternScreenerRun, PatternScreenerRequest } from "../types";
import { updateScreener, deleteScreener, triggerRun, getRuns } from "../api";
import PatternRunResultsPanel from "./PatternRunResultsPanel";

const SCHEDULE_PRESETS = [
  { label: "Every 15 minutes", cron: "0 */15 * * * ?" },
  { label: "Every 30 minutes", cron: "0 */30 * * * ?" },
  { label: "Hourly", cron: "0 0 * * * ?" },
  { label: "Daily at 9:15am", cron: "0 15 9 * * MON-FRI" },
  { label: "Daily at 3:30pm", cron: "0 30 15 * * MON-FRI" },
  { label: "Custom", cron: "custom" },
];

interface Props {
  screener: PatternScreener;
  onUpdated: (s: PatternScreener) => void;
  onDeleted: (id: number) => void;
}

export default function PatternScreenerCard({ screener, onUpdated, onDeleted }: Props) {
  const [expanded, setExpanded] = useState(false);
  const [editing, setEditing] = useState(false);
  const [running, setRunning] = useState(false);
  const [runs, setRuns] = useState<PatternScreenerRun[] | null>(null);
  const [selectedRunId, setSelectedRunId] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  const [name, setName] = useState(screener.name);
  const [segments, setSegments] = useState(screener.segments);
  const [watchingTf, setWatchingTf] = useState(screener.watchingTf);
  const [confirmTf, setConfirmTf] = useState(screener.confirmTf);
  const [scheduleCron, setScheduleCron] = useState(screener.scheduleCron);
  const [customCron, setCustomCron] = useState("");
  const [preset, setPreset] = useState(
    () => SCHEDULE_PRESETS.find((p) => p.cron === screener.scheduleCron)?.cron ?? "custom"
  );

  const handleToggle = async (e: React.MouseEvent) => {
    e.stopPropagation();
    try {
      const req: PatternScreenerRequest = {
        name: screener.name,
        segments: screener.segments,
        watchingTf: screener.watchingTf,
        confirmTf: screener.confirmTf,
        scheduleCron: screener.scheduleCron,
        enabled: !screener.enabled,
      };
      const updated = await updateScreener(screener.id, req);
      onUpdated(updated);
    } catch (e: any) {
      setError(e.message);
    }
  };

  const handleDelete = async (e: React.MouseEvent) => {
    e.stopPropagation();
    if (!window.confirm(`Delete screener "${screener.name}"?`)) return;
    try {
      await deleteScreener(screener.id);
      onDeleted(screener.id);
    } catch (e: any) {
      setError(e.message);
    }
  };

  const handleRun = async () => {
    setRunning(true);
    setError(null);
    try {
      await triggerRun(screener.id);
      setTimeout(() => loadRuns(), 2000);
    } catch (e: any) {
      setError(e.message);
    } finally {
      setRunning(false);
    }
  };

  const loadRuns = async () => {
    const r = await getRuns(screener.id);
    setRuns(r);
    if (r.length > 0 && !selectedRunId) setSelectedRunId(r[0].id);
  };

  const handleSave = async () => {
    try {
      const cron = preset === "custom" ? customCron : preset;
      const req: PatternScreenerRequest = {
        name,
        segments,
        watchingTf,
        confirmTf,
        scheduleCron: cron,
        enabled: screener.enabled,
      };
      const updated = await updateScreener(screener.id, req);
      onUpdated(updated);
      setEditing(false);
    } catch (e: any) {
      setError(e.message);
    }
  };

  const fmt = (s: string | null) => (s ? new Date(s).toLocaleString() : "Never");

  const inputStyle: React.CSSProperties = {
    background: "#2a2a2a",
    color: "#fff",
    border: "1px solid #444",
    borderRadius: 4,
    padding: "6px 8px",
    width: "100%",
    boxSizing: "border-box",
  };
  const btn = (bg: string): React.CSSProperties => ({
    background: bg,
    color: "#fff",
    border: "none",
    borderRadius: 4,
    padding: "6px 14px",
    cursor: "pointer",
    fontSize: 13,
  });

  return (
    <div
      style={{
        background: "#1e1e1e",
        border: "1px solid #333",
        borderRadius: 8,
        marginBottom: 10,
      }}
    >
      {/* Header */}
      <div
        onClick={() => setExpanded((e) => !e)}
        style={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          padding: "12px 16px",
          cursor: "pointer",
        }}
      >
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <span style={{ fontWeight: 600, color: "#90caf9" }}>{screener.name}</span>
          <span style={{ color: "#aaa", fontSize: 12 }}>
            {screener.segments} · {screener.watchingTf}/{screener.confirmTf}
          </span>
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <span style={{ color: "#666", fontSize: 12 }}>Last: {fmt(screener.lastRunAt)}</span>
          {/* Toggle */}
          <div
            onClick={handleToggle}
            title={screener.enabled ? "Disable" : "Enable"}
            style={{
              width: 40,
              height: 22,
              borderRadius: 11,
              background: screener.enabled ? "#2e7d32" : "#555",
              position: "relative",
              cursor: "pointer",
              flexShrink: 0,
            }}
          >
            <div
              style={{
                width: 16,
                height: 16,
                borderRadius: "50%",
                background: "#fff",
                position: "absolute",
                top: 3,
                left: screener.enabled ? 21 : 3,
                transition: "left 0.2s",
              }}
            />
          </div>
          {/* Delete */}
          <button
            onClick={handleDelete}
            style={{
              background: "transparent",
              color: "#ef5350",
              border: "1px solid #ef5350",
              borderRadius: 4,
              padding: "2px 8px",
              cursor: "pointer",
              fontSize: 12,
            }}
          >
            ✕
          </button>
          <span style={{ color: "#aaa", fontSize: 12 }}>{expanded ? "▲" : "▼"}</span>
        </div>
      </div>

      {expanded && (
        <div style={{ padding: "0 16px 16px" }}>
          {error && <p style={{ color: "#ef9a9a" }}>{error}</p>}

          {editing ? (
            <div style={{ display: "grid", gap: 10, marginBottom: 12 }}>
              <div>
                <label style={{ color: "#aaa", fontSize: 12 }}>Name</label>
                <input
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  style={inputStyle}
                />
              </div>
              <div>
                <label style={{ color: "#aaa", fontSize: 12 }}>Segments (comma-separated: EQ,FUT,OPT)</label>
                <input
                  value={segments}
                  onChange={(e) => setSegments(e.target.value)}
                  style={inputStyle}
                />
              </div>
              <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 10 }}>
                <div>
                  <label style={{ color: "#aaa", fontSize: 12 }}>Watching TF</label>
                  <input
                    value={watchingTf}
                    onChange={(e) => setWatchingTf(e.target.value)}
                    style={inputStyle}
                    placeholder="1h"
                  />
                </div>
                <div>
                  <label style={{ color: "#aaa", fontSize: 12 }}>Confirm TF</label>
                  <input
                    value={confirmTf}
                    onChange={(e) => setConfirmTf(e.target.value)}
                    style={inputStyle}
                    placeholder="15m"
                  />
                </div>
              </div>
              <div>
                <label style={{ color: "#aaa", fontSize: 12 }}>Schedule</label>
                <select
                  value={preset}
                  onChange={(e) => {
                    setPreset(e.target.value);
                    if (e.target.value !== "custom") setScheduleCron(e.target.value);
                  }}
                  style={{ ...inputStyle, marginTop: 4 }}
                >
                  {SCHEDULE_PRESETS.map((p) => (
                    <option key={p.cron} value={p.cron}>
                      {p.label}
                    </option>
                  ))}
                </select>
                {preset === "custom" && (
                  <input
                    value={customCron}
                    onChange={(e) => setCustomCron(e.target.value)}
                    placeholder="Spring cron"
                    style={{ ...inputStyle, marginTop: 4 }}
                  />
                )}
              </div>
              <div style={{ display: "flex", gap: 8 }}>
                <button onClick={handleSave} style={btn("#1565c0")}>
                  Save
                </button>
                <button onClick={() => setEditing(false)} style={btn("#333")}>
                  Cancel
                </button>
              </div>
            </div>
          ) : (
            <div style={{ display: "flex", gap: 8, marginBottom: 12 }}>
              <button onClick={() => setEditing(true)} style={btn("#333")}>
                Edit
              </button>
              <button onClick={handleRun} disabled={running} style={btn("#2e7d32")}>
                {running ? "Starting..." : "Run Now"}
              </button>
              <button
                onClick={() => {
                  loadRuns();
                }}
                style={btn("#333")}
              >
                Run History
              </button>
            </div>
          )}

          {runs && (
            <div>
              <div style={{ display: "flex", gap: 8, marginBottom: 8, flexWrap: "wrap" }}>
                {runs.map((r) => (
                  <button
                    key={r.id}
                    onClick={() => setSelectedRunId(r.id)}
                    style={{
                      background: selectedRunId === r.id ? "#1565c0" : "#2a2a2a",
                      color: "#fff",
                      border: "1px solid #444",
                      borderRadius: 4,
                      padding: "4px 10px",
                      cursor: "pointer",
                      fontSize: 12,
                    }}
                  >
                    {new Date(r.startedAt).toLocaleString()} — {r.status} ({r.signalsCreated}{" "}
                    signals)
                  </button>
                ))}
              </div>
              {selectedRunId && (
                <PatternRunResultsPanel screenerId={screener.id} runId={selectedRunId} />
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
