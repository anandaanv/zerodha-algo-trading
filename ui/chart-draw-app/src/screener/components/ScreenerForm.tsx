import React, { useEffect, useMemo, useState } from "react";
import { UpsertPayload, createScreener, getIntervalUiMapping, getSeriesEnums, IntervalUiMapping } from "../api";

type AliasRow = {
  alias: string;
  interval: string;
  seriesEnum: string;
};

export default function ScreenerForm() {
  const [rows, setRows] = useState<AliasRow[]>([]);
  const [tfMap, setTfMap] = useState<IntervalUiMapping>({});
  const [seriesEnums, setSeriesEnums] = useState<string[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  const tfOptions = useMemo(() => Object.entries(tfMap).map(([label, enumName]) => ({ label, value: enumName })), [tfMap]);

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

    const mapping: Record<string, unknown> = {};
    rows.forEach((r) => {
      mapping[r.alias.trim()] = { reference: r.seriesEnum, interval: r.interval };
    });

    const timeframe = rows[0].interval;

    const payload: UpsertPayload = {
      script: rows.map((r) => `// ${r.alias}: ${r.seriesEnum} @ ${r.interval}`).join("\n") + "\n",
      timeframe,
      mapping,
      workflow: ["SCRIPT"],
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
