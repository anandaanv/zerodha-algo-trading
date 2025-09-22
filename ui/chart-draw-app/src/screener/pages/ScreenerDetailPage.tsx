import React, { useEffect, useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { ScreenerResponse, getScreener, updateScreener } from "../api";
import ScreenerForm from "../components/ScreenerForm";

function tryParse(json: string): any {
  try {
    return JSON.parse(json || "{}");
  } catch {
    return null;
  }
}

function tryParseArray(json: string): string[] {
  try {
    const arr = JSON.parse(json || "[]");
    return Array.isArray(arr) ? arr.map((x: any) => String(x)) : [];
  } catch {
    return [];
  }
}

export default function ScreenerDetailPage() {
  const { id } = useParams();
  const screenerId = Number(id);
  const [data, setData] = useState<ScreenerResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let mounted = true;
    setLoading(true);
    getScreener(screenerId)
      .then((res) => {
        if (mounted) setData(res);
      })
      .catch((err) => setError(err.message || "Failed to load screener"))
      .finally(() => setLoading(false));
    return () => {
      mounted = false;
    };
  }, [screenerId]);

  const initial = useMemo(() => {
    if (!data) return null;
    const cfg = tryParse(data.configJson || "{}") || {};
    const mapping = cfg?.mapping || {};
    const workflow = Array.isArray(cfg?.workflow) ? cfg.workflow.map((x: any) => String(x)) : [];
    const charts = tryParseArray(data.chartsJson || "[]");

    return {
      script: data.script || "",
      timeframe: data.timeframe || "",
      mapping,
      workflow,
      promptJson: data.promptJson || "{}",
      charts,
    };
  }, [data]);

  return (
    <div className="container">
      <nav className="toolbar" style={{ marginBottom: 16 }}>
        <Link className="btn" to="/screener">Back to list</Link>
        <Link className="btn" to="/">Chart</Link>
        <Link className="btn primary" to="/screener/new">New Screener</Link>
      </nav>

      <div className="card">
        <div className="toolbar" style={{ justifyContent: "space-between" }}>
          <h1 style={{ margin: 0 }}>Edit Screener #{screenerId}</h1>
        </div>

        {loading && <p className="muted">Loading…</p>}
        {error && <p className="error">{error}</p>}

        {initial && (
          <ScreenerForm
            mode="edit"
            initial={initial}
            submitLabel="Save"
            onSubmit={async (payload) => {
              await updateScreener(screenerId, payload);
            }}
          />
        )}
      </div>
    </div>
  );
}
