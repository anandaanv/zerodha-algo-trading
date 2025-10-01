import React, { useEffect, useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { ScreenerResponse, getScreener, updateScreener, getIntervalUiMapping, IntervalUiMapping, RunConfig } from "../api";
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

  // Scheduling UI state
  const [tfMap, setTfMap] = useState<IntervalUiMapping>({});
  const [scheduleTimeframe, setScheduleTimeframe] = useState<string>("");
  const [symbolsInput, setSymbolsInput] = useState<string>("");
  const [runConfigs, setRunConfigs] = useState<RunConfig[]>([]);
  const [scheduling, setScheduling] = useState<boolean>(false);
  const [scheduleMsg, setScheduleMsg] = useState<string | null>(null);
  const [scheduleError, setScheduleError] = useState<boolean>(false);

  const tfOptions = useMemo(() => Object.entries(tfMap).map(([label, enumName]) => ({ label, value: enumName })), [tfMap]);

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

  useEffect(() => {
    let mounted = true;
    getIntervalUiMapping()
      .then((map) => {
        if (!mounted) return;
        setTfMap(map || {});
        const firstTf = Object.values(map || {})[0] || "";
        setScheduleTimeframe(firstTf);
      })
      .catch(() => {});
    return () => {
      mounted = false;
    };
  }, []);

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
      // Include promptId from API; cast to any to avoid type mismatch if client type is outdated
      promptId: (data as any).promptId || "",
      promptJson: data.promptJson || "{}",
      charts,
    };
  }, [data]);

  // Prefill scheduling run configs from response
  useEffect(() => {
    if (!data) return;
    let parsed: any = {};
    try {
      parsed = JSON.parse(data.schedulingConfigJson || "{}");
    } catch {
      parsed = {};
    }
    const arr = Array.isArray(parsed?.runConfigs) ? parsed.runConfigs : [];
    const normalized: RunConfig[] = arr.map((x: any) => ({
      timeframe: String(x?.timeframe ?? ""),
      symbols: Array.isArray(x?.symbols) ? x.symbols.map((s: any) => String(s)).filter(Boolean) : [],
    })).filter(rc => rc.timeframe && rc.symbols.length > 0);
    setRunConfigs(normalized);
  }, [data]);

  function addRunConfig() {
    const tf = scheduleTimeframe || (tfOptions[0]?.value || "");
    const symbols = symbolsInput.split(",").map((s) => s.trim()).filter(Boolean);
    if (!tf || symbols.length === 0) {
      setScheduleMsg("Please provide timeframe and at least one symbol.");
      setScheduleError(true);
      return;
    }
    setRunConfigs((prev) => [...prev, { timeframe: tf, symbols }]);
    setSymbolsInput("");
  }

  function removeRunConfig(index: number) {
    setRunConfigs((prev) => prev.filter((_, i) => i !== index));
  }

  async function onSchedule() {
    if (!screenerId) return;
    if (runConfigs.length === 0) {
      setScheduleMsg("Please add at least one run config.");
      setScheduleError(true);
      return;
    }
    setScheduling(true);
    setScheduleMsg(null);
    setScheduleError(false);
    try {
      // Submit just the runConfigs; backend should merge/update ScreenerEntity.runconfig
      await updateScreener(screenerId, { script: "", timeframe: "", runConfigs });
      setScheduleMsg("Scheduling configuration saved.");
      setRunConfigs([]);
    } catch (e: any) {
      setScheduleMsg(e?.message || "Failed to save scheduling configuration.");
      setScheduleError(true);
    } finally {
      setScheduling(false);
    }
  }

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
            screenerId={screenerId}
            onSubmit={async (payload) => {
              await updateScreener(screenerId, payload);
            }}
          />
        )}
      </div>

      {/* Scheduling Section */}
      <div className="card" style={{ marginTop: 16 }}>
        <div className="toolbar" style={{ justifyContent: "space-between" }}>
          <h2 style={{ margin: 0 }}>Scheduling</h2>
        </div>
        <div className="row">
          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr auto", gap: 8, alignItems: "end" }}>
            <div>
              <label>Symbols (comma-separated)</label>
              <input
                type="text"
                placeholder="e.g. INFY, TCS, RELIANCE"
                value={symbolsInput}
                onChange={(e) => setSymbolsInput(e.target.value)}
              />
            </div>
            <div>
              <label>Timeframe</label>
              <select
                value={scheduleTimeframe}
                onChange={(e) => setScheduleTimeframe(e.target.value)}
              >
                {tfOptions.map((opt) => (
                  <option key={opt.value} value={opt.value}>
                    {opt.label} ({opt.value})
                  </option>
                ))}
              </select>
            </div>
            <div>
              <button type="button" className="btn" onClick={addRunConfig}>
                Add run config
              </button>
            </div>
          </div>
          <div className="muted" style={{ marginTop: 8 }}>
            Add multiple run configurations. Each run config includes a timeframe and a list of symbols.
          </div>
        </div>

        {runConfigs.length > 0 && (
          <div className="row" style={{ marginTop: 8 }}>
            <label>Pending run configs</label>
            <ul style={{ paddingLeft: 16 }}>
              {runConfigs.map((rc, i) => (
                <li key={i} style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 8 }}>
                  <div>
                    <strong>{rc.timeframe}</strong> — {rc.symbols.join(", ")}
                  </div>
                  <button type="button" className="btn" onClick={() => removeRunConfig(i)}>
                    Remove
                  </button>
                </li>
              ))}
            </ul>
          </div>
        )}

        {scheduleMsg && (
          <div className={`row ${scheduleError ? "error" : "success"}`} style={{ marginTop: 8 }}>
            {scheduleMsg}
          </div>
        )}

        <div className="toolbar" style={{ marginTop: 8 }}>
          <button type="button" className="btn primary" disabled={!screenerId || scheduling || runConfigs.length === 0} onClick={onSchedule}>
            {scheduling ? "Scheduling…" : "Schedule"}
          </button>
        </div>
      </div>
    </div>
  );
}
