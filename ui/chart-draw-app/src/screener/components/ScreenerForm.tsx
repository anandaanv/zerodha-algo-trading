import React, { useEffect, useMemo, useState } from "react";
import { UpsertPayload, createScreener, getIntervalUiMapping, getSeriesEnums, IntervalUiMapping, validateScreenerScript } from "../api";

type AliasRow = {
  alias: string;
  interval: string;
  seriesEnum: string;
};

const WORKFLOW_OPTIONS = ["SCRIPT", "OPENAI"] as const;

export default function ScreenerForm() {
  const [rows, setRows] = useState<AliasRow[]>([]);
  const [tfMap, setTfMap] = useState<IntervalUiMapping>({});
  const [seriesEnums, setSeriesEnums] = useState<string[]>([]);
  // Additional fields
  const [script, setScript] = useState<string>("");
  const [workflow, setWorkflow] = useState<string[]>(["SCRIPT"]);
  const [promptJson, setPromptJson] = useState<string>("{}");
  const [chartsInput, setChartsInput] = useState<string>("");

  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [validating, setValidating] = useState(false);
  const [validateMsg, setValidateMsg] = useState<string | null>(null);

  const tfOptions = useMemo(() => Object.entries(tfMap).map(([label, enumName]) => ({ label, value: enumName })), [tfMap]);
  const requiresOpenAI = useMemo(() => workflow.includes("OPENAI"), [workflow]);

  useEffect(() => {
    async function load() {
      try {
        const [map, enums] = await Promise.all([getIntervalUiMapping(), getSeriesEnums()]);
        setTfMap(map || {});
        setSeriesEnums(enums || []);
        const firstTf = Object.values(map || {})[0] || "";
        const firstSe = (enums && enums[0]) || "";
        setRows([{ alias: "wave", interval: firstTf, seriesEnum: firstSe }]);
      } catch (e: any) {
        setError(e.message || "Failed to load metadata.");
      }
    }
    load();
  }, []);

  function addRow() {
    const idx = rows.length + 1;
    const firstTf = tfOptions[0]?.value || "";
    const firstSe = seriesEnums[0] || "";
    setRows((prev) => [...prev, { alias: `alias${idx}`, interval: firstTf, seriesEnum: firstSe }]);
  }

  function removeRow(index: number) {
    setRows((prev) => prev.filter((_, i) => i !== index));
  }

  function updateRow(index: number, patch: Partial<AliasRow>) {
    setRows((prev) => prev.map((r, i) => (i === index ? { ...r, ...patch } : r)));
  }

  function toggleWorkflow(opt: (typeof WORKFLOW_OPTIONS)[number]) {
    setWorkflow((prev) => (prev.includes(opt) ? prev.filter((o) => o !== opt) : [...prev, opt]));
  }

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    setSuccess(null);

    if (rows.length === 0) {
      setSubmitting(false);
      setError("Please add at least one alias.");
      return;
    }

    const seen = new Set<string>();
    for (const r of rows) {
      if (!r.alias.trim()) {
        setSubmitting(false);
        setError("Alias cannot be empty.");
        return;
      }
      if (seen.has(r.alias.trim())) {
        setSubmitting(false);
        setError(`Duplicate alias: ${r.alias}`);
        return;
      }
      seen.add(r.alias.trim());
      if (!r.interval) {
        setSubmitting(false);
        setError(`Please select Interval for alias "${r.alias}".`);
        return;
      }
      if (!r.seriesEnum) {
        setSubmitting(false);
        setError(`Please select SeriesEnum for alias "${r.alias}".`);
        return;
      }
    }

    // Build mapping
    const mapping: Record<string, unknown> = {};
    rows.forEach((r) => {
      mapping[r.alias.trim()] = { reference: r.seriesEnum, interval: r.interval };
    });

    // timeframe metadata: first alias interval
    const timeframe = rows[0].interval;

    // Parse charts list
    let charts: string[] | undefined = undefined;
    if (chartsInput && chartsInput.trim()) {
      charts = chartsInput.split(",").map((s) => s.trim()).filter(Boolean);
    }

    const payload: UpsertPayload = {
      script,
      timeframe,
      mapping,
      workflow,
      promptJson: requiresOpenAI ? promptJson : undefined,
      charts,
    };

    try {
      const res = await createScreener(payload);
      setSuccess(`Created screener #${res.id}`);
    } catch (err: any) {
      setError(err.message || "Failed to create screener.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form onSubmit={onSubmit}>
      {/* Alias rows */}
      <div className="row">
        <label>Aliases</label>
        <div className="muted">Add multiple aliases. Each alias has SeriesEnum, Interval, and a name.</div>
      </div>

      {rows.map((r, i) => (
        <div className="row" key={i} style={{ display: "grid", gridTemplateColumns: "1fr 1fr 1fr auto", gap: 8, alignItems: "center" }}>
          <div>
            <label>SeriesEnum</label>
            <select value={r.seriesEnum} onChange={(e) => updateRow(i, { seriesEnum: e.target.value })}>
              {seriesEnums.map((se) => (
                <option key={se} value={se}>
                  {se}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label>Interval</label>
            <select value={r.interval} onChange={(e) => updateRow(i, { interval: e.target.value })}>
              {tfOptions.map((opt) => (
                <option key={opt.value} value={opt.value}>
                  {opt.label} ({opt.value})
                </option>
              ))}
            </select>
          </div>
          <div>
            <label>Alias name</label>
            <input type="text" placeholder="e.g. wave, tide" value={r.alias} onChange={(e) => updateRow(i, { alias: e.target.value })} />
          </div>
          <div>
            <button type="button" className="btn" onClick={() => removeRow(i)} disabled={rows.length === 1}>
              Remove
            </button>
          </div>
        </div>
      ))}

      <div className="toolbar" style={{ marginTop: 8 }}>
        <button type="button" className="btn" onClick={addRow}>
          Add alias
        </button>
      </div>

      {/* Script */}
      <div className="row">
        <label>Script</label>
        <textarea spellCheck={false} value={script} onChange={(e) => setScript(e.target.value)} placeholder="// Your screener script here" />
        <div className="toolbar" style={{ marginTop: 8 }}>
          <button
            type="button"
            className="btn"
            onClick={async () => {
              setValidateMsg(null);
              setError(null);
              setSuccess(null);
              setValidating(true);
              try {
                const res = await validateScreenerScript(script);
                if (res.ok) {
                  setValidateMsg("Script compiled successfully.");
                } else {
                  setValidateMsg(res.error || "Compilation failed.");
                }
              } catch (e: any) {
                setValidateMsg(e.message || "Compilation failed.");
              } finally {
                setValidating(false);
              }
            }}
            disabled={validating || !script.trim()}
          >
            {validating ? "Validating…" : "Validate Script"}
          </button>
        </div>
        {validateMsg && (
          <div className={/successfully/.test(validateMsg) ? "row success" : "row error"} style={{ marginTop: 8 }}>
            {validateMsg}
          </div>
        )}
        <div className="muted">You can reference the aliases defined above in your script.</div>
      </div>

      {/* Workflow */}
      <div className="row">
        <label>Workflow</label>
        <div className="toolbar">
          {WORKFLOW_OPTIONS.map((opt) => (
            <label className="pill" key={opt}>
              <input type="checkbox" checked={workflow.includes(opt)} onChange={() => toggleWorkflow(opt)} />
              {opt}
            </label>
          ))}
        </div>
        <div className="muted">Enable OPENAI to attach an AI analysis step after the script.</div>
      </div>

      {/* Prompt JSON shown only if OPENAI */}
      {requiresOpenAI && (
        <div className="row">
          <label>Prompt JSON</label>
          <textarea spellCheck={false} value={promptJson} onChange={(e) => setPromptJson(e.target.value)} />
          <div className="muted">Provide JSON prompt configuration for the AI step.</div>
        </div>
      )}

      {/* Charts */}
      <div className="row">
        <label>Charts Intervals (comma-separated)</label>
        <input type="text" placeholder="e.g. DAY_1,HOUR_1" value={chartsInput} onChange={(e) => setChartsInput(e.target.value)} />
        <div className="muted">These intervals will be analyzed in the AI step (if enabled).</div>
      </div>

      {error && <div className="row error">{error}</div>}
      {success && <div className="row success">{success}</div>}

      <div className="toolbar">
        <button type="submit" className="btn primary" disabled={submitting}>
          {submitting ? "Creating…" : "Create Screener"}
        </button>
        <button
          type="button"
          className="btn"
          onClick={() => {
            const firstTf = tfOptions[0]?.value || "";
            const firstSe = seriesEnums[0] || "";
            setRows([{ alias: "wave", interval: firstTf, seriesEnum: firstSe }]);
            setScript("");
            setWorkflow(["SCRIPT"]);
            setPromptJson("{}");
            setChartsInput("");
            setError(null);
            setSuccess(null);
          }}
        >
          Reset
        </button>
      </div>
    </form>
  );
}
