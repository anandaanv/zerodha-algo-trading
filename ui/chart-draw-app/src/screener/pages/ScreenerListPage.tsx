import React, { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { ScreenerResponse, listScreeners } from "../api";

function parseWorkflow(configJson: string): string[] {
  try {
    const cfg = JSON.parse(configJson || "{}");
    const wf = Array.isArray(cfg?.workflow) ? cfg.workflow : [];
    return wf.map((x: any) => String(x));
  } catch {
    return [];
  }
}

export default function ScreenerListPage() {
  const [items, setItems] = useState<ScreenerResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let mounted = true;
    setLoading(true);
    listScreeners()
      .then((data) => {
        if (mounted) setItems(data);
      })
      .catch((err) => setError(err.message || "Failed to load screeners"))
      .finally(() => setLoading(false));
    return () => {
      mounted = false;
    };
  }, []);

  const rows = useMemo(() => {
    return items.map((s) => {
      const wf = parseWorkflow(s.configJson);
      return (
        <tr key={s.id}>
          <td>
            <Link to={`/screener/${s.id}`}>#{s.id}</Link>
          </td>
          <td>{s.timeframe || "-"}</td>
          <td title={s.script} style={{ maxWidth: 280, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
            {s.script || ""}
          </td>
          <td>{wf.length ? wf.join(", ") : "-"}</td>
          <td>
            <Link className="btn" to={`/screener/${s.id}`}>
              Edit
            </Link>
          </td>
        </tr>
      );
    });
  }, [items]);

  return (
    <div className="container">
      <nav className="toolbar" style={{ marginBottom: 16 }}>
        <Link className="btn" to="/">Chart</Link>
        <Link className="btn primary" to="/screener/new">New Screener</Link>
      </nav>

      <div className="card">
        <h1>Screeners</h1>

        {loading && <p className="muted">Loading…</p>}
        {error && <p className="error">{error}</p>}

        {!loading && !error && (
          <div style={{ overflowX: "auto" }}>
            <table style={{ width: "100%", borderCollapse: "collapse" }}>
              <thead>
                <tr>
                  <th style={{ textAlign: "left", padding: "8px" }}>ID</th>
                  <th style={{ textAlign: "left", padding: "8px" }}>Timeframe</th>
                  <th style={{ textAlign: "left", padding: "8px" }}>Script</th>
                  <th style={{ textAlign: "left", padding: "8px" }}>Workflow</th>
                  <th style={{ textAlign: "left", padding: "8px" }}>Actions</th>
                </tr>
              </thead>
              <tbody>
                {rows.length ? (
                  rows
                ) : (
                  <tr>
                    <td colSpan={5} className="muted" style={{ padding: "8px" }}>
                      No screeners yet. Create one to get started.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
