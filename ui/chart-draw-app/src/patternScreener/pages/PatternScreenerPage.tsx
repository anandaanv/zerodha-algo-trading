import React, { useEffect, useState } from "react";
import { PatternScreener, PatternScreenerRequest } from "../types";
import { listScreeners, createScreener } from "../api";
import PatternScreenerCard from "../components/PatternScreenerCard";

const EMPTY_REQ: PatternScreenerRequest = {
  name: "",
  segments: "EQ",
  watchingTf: "1h",
  confirmTf: "15m",
  scheduleCron: "0 0 * * * ?",
  enabled: true,
};

export default function PatternScreenerPage() {
  const [screeners, setScreeners] = useState<PatternScreener[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showCreate, setShowCreate] = useState(false);
  const [form, setForm] = useState<PatternScreenerRequest>({ ...EMPTY_REQ });
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    listScreeners()
      .then(setScreeners)
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false));
  }, []);

  const handleCreate = async () => {
    setSaving(true);
    try {
      const s = await createScreener(form);
      setScreeners((prev) => [s, ...prev]);
      setShowCreate(false);
      setForm({ ...EMPTY_REQ });
    } catch (e: any) {
      setError(e.message);
    } finally {
      setSaving(false);
    }
  };

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
    padding: "8px 16px",
    cursor: "pointer",
  });

  return (
    <div
      style={{
        background: "#1a1a1a",
        minHeight: "100vh",
        padding: 24,
        color: "#fff",
        fontFamily: "system-ui",
      }}
    >
      <div
        style={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          marginBottom: 24,
        }}
      >
        <h1 style={{ color: "#90caf9", margin: 0 }}>Pattern Screener</h1>
        <button onClick={() => setShowCreate((s) => !s)} style={btn("#1565c0")}>
          + New Screener
        </button>
      </div>

      {error && (
        <div style={{ background: "#d32f2f", padding: 12, borderRadius: 4, marginBottom: 16 }}>
          {error}
        </div>
      )}

      {showCreate && (
        <div
          style={{
            background: "#1e1e1e",
            border: "1px solid #444",
            borderRadius: 8,
            padding: 16,
            marginBottom: 20,
          }}
        >
          <h3 style={{ color: "#90caf9", marginTop: 0 }}>New Pattern Screener</h3>
          <div style={{ display: "grid", gap: 10 }}>
            <div>
              <label style={{ color: "#aaa", fontSize: 12 }}>Name</label>
              <input
                value={form.name}
                onChange={(e) => setForm((f) => ({ ...f, name: e.target.value }))}
                style={inputStyle}
              />
            </div>
            <div>
              <label style={{ color: "#aaa", fontSize: 12 }}>Segments to trade</label>
              <div style={{ display: "flex", gap: 8, marginTop: 4 }}>
                {["EQ", "FUT", "OPT"].map((seg) => (
                  <label key={seg} style={{ display: "flex", alignItems: "center", gap: 4, cursor: "pointer" }}>
                    <input
                      type="checkbox"
                      checked={form.segments.split(",").includes(seg)}
                      onChange={(e) => {
                        const parts = form.segments.split(",").filter(Boolean);
                        const next = e.target.checked
                          ? [...parts, seg]
                          : parts.filter((p) => p !== seg);
                        setForm((f) => ({ ...f, segments: next.join(",") }));
                      }}
                    />
                    <span style={{ color: "#fff" }}>{seg}</span>
                  </label>
                ))}
              </div>
            </div>
            <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 10 }}>
              <div>
                <label style={{ color: "#aaa", fontSize: 12 }}>Watching TF</label>
                <input
                  value={form.watchingTf}
                  onChange={(e) => setForm((f) => ({ ...f, watchingTf: e.target.value }))}
                  style={inputStyle}
                  placeholder="1h"
                />
              </div>
              <div>
                <label style={{ color: "#aaa", fontSize: 12 }}>Confirm TF</label>
                <input
                  value={form.confirmTf}
                  onChange={(e) => setForm((f) => ({ ...f, confirmTf: e.target.value }))}
                  style={inputStyle}
                  placeholder="15m"
                />
              </div>
            </div>
            <div>
              <label style={{ color: "#aaa", fontSize: 12 }}>Schedule Cron</label>
              <input
                value={form.scheduleCron}
                onChange={(e) => setForm((f) => ({ ...f, scheduleCron: e.target.value }))}
                style={inputStyle}
                placeholder="0 0 * * * ?"
              />
            </div>
          </div>
          <div style={{ display: "flex", gap: 8, marginTop: 12 }}>
            <button onClick={handleCreate} disabled={saving} style={btn("#1565c0")}>
              {saving ? "Saving..." : "Create"}
            </button>
            <button onClick={() => setShowCreate(false)} style={btn("#333")}>
              Cancel
            </button>
          </div>
        </div>
      )}

      {loading && <p style={{ color: "#aaa" }}>Loading...</p>}

      {screeners.map((s) => (
        <PatternScreenerCard
          key={s.id}
          screener={s}
          onUpdated={(updated) =>
            setScreeners((prev) => prev.map((x) => (x.id === updated.id ? updated : x)))
          }
          onDeleted={(id) => setScreeners((prev) => prev.filter((x) => x.id !== id))}
        />
      ))}

      {!loading && screeners.length === 0 && !showCreate && (
        <p style={{ color: "#555" }}>No pattern screeners yet. Create one to get started.</p>
      )}
    </div>
  );
}
