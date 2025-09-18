import React, { useMemo, useState } from "react";
import { UpsertPayload, createScreener } from "../api";

const WORKFLOW_OPTIONS = ["SCRIPT", "OPENAI"] as const;

export default function ScreenerForm() {
  const [timeframe, setTimeframe] = useState("DAY_1");
  const [script, setScript] = useState<string>("// Write your screener script here");
  const [mappingJson, setMappingJson] = useState<string>(`{
  "NSE_EQ": { "symbol": "NIFTY", "interval": "DAY_1", "candles": 500 }
}`);
  const [workflow, setWorkflow] = useState<string[]>(["SCRIPT"]);
  const [promptJson, setPromptJson] = useState<string>(`{
  "system": "You are a helpful assistant for chart analysis.",
  "temperature": 0.2
}`);
  const [chartsInput, setChartsInput] = useState<string>("DAY_1,HOUR_1");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  const requiresOpenAI = useMemo(() => workflow.includes("OPENAI"), [workflow]);

  function toggleWorkflow(opt: (typeof WORKFLOW_OPTIONS)[number]) {
    setWorkflow((prev) => (prev.includes(opt) ? prev.filter((o) => o !== opt) : [...prev, opt]));
  }

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    setSuccess(null);

    let mapping: Record<string, unknown> | undefined;
    try {
      mapping = mappingJson.trim() ? JSON.parse(mappingJson) : undefined;
    } catch {
      setSubmitting(false);
      setError("Mapping JSON is invalid.");
      return;
    }

    let charts: string[] | undefined;
    try {
      charts = chartsInput.split(",").map((s) => s.trim()).filter(Boolean);
    } catch {
      charts = undefined;
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
      <div className="grid">
        <div>
          <div className="row">
            <label>Timeframe</label>
            <input type="text" placeholder="e.g. DAY_1" value={timeframe} onChange={(e) => setTimeframe(e.target.value)} />
            <div className="muted">
              Must match backend interval names used elsewhere, e.g., <code className="inline">DAY_1</code>, <code className="inline">HOUR_1</code>.
            </div>
          </div>

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

          {requiresOpenAI && (
            <div className="row">
              <label>Prompt JSON (only if OPENAI selected)</label>
              <textarea spellCheck={false} value={promptJson} onChange={(e) => setPromptJson(e.target.value)} />
              <div className="muted">Provide JSON prompt config consumed by your AI step.</div>
            </div>
          )}

          <div className="row">
            <label>Charts Intervals (comma-separated)</label>
            <input type="text" placeholder="e.g. DAY_1,HOUR_1,MIN_5" value={chartsInput} onChange={(e) => setChartsInput(e.target.value)} />
            <div className="muted">These intervals will be analyzed in the AI step (if enabled).</div>
          </div>
        </div>

        <div>
          <div className="row">
            <label>Script</label>
            <textarea spellCheck={false} value={script} onChange={(e) => setScript(e.target.value)} />
          </div>

          <div className="row">
            <label>Mapping JSON</label>
            <textarea spellCheck={false} value={mappingJson} onChange={(e) => setMappingJson(e.target.value)} />
            <div className="muted">Define series mapping required by your screener.</div>
          </div>
        </div>
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
            setScript("");
            setMappingJson("");
            setPromptJson("");
            setChartsInput("");
            setWorkflow(["SCRIPT"]);
            setTimeframe("");
          }}
        >
          Clear
        </button>
      </div>
    </form>
  );
}
