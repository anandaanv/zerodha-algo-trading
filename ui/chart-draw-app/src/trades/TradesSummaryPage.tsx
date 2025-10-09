import React, { useEffect, useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { fetchTrades } from "./tradesApi";
import type { Trade } from "./types";
import { TradesTable } from "./TradesTable";

function toInputDate(d: Date) {
  // yyyy-MM-dd
  const pad = (n: number) => n.toString().padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

function fromInputDate(s?: string | null) {
  if (!s) return undefined;
  const d = new Date(s);
  if (Number.isNaN(d.getTime())) return undefined;
  return d;
}

export const TradesSummaryPage: React.FC = () => {
  const [searchParams, setSearchParams] = useSearchParams();

  // Defaults
  const defaultTo = new Date();
  const defaultFrom = new Date(defaultTo.getTime() - 30 * 24 * 60 * 60 * 1000);

  const [from, setFrom] = useState<string>(searchParams.get("from") || toInputDate(defaultFrom));
  const [to, setTo] = useState<string>(searchParams.get("to") || toInputDate(defaultTo));
  const [openOnly, setOpenOnly] = useState<boolean>(
    (searchParams.get("open") ?? "true").toLowerCase() !== "false"
  );
  const [script, setScript] = useState<string>(searchParams.get("script") || "");
  const [side, setSide] = useState<string>(searchParams.get("side") || "ALL");
  const [timeframe, setTimeframe] = useState<string>(searchParams.get("timeframe") || "");

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [trades, setTrades] = useState<Trade[]>([]);

  const queryFrom = useMemo(() => fromInputDate(from), [from]);
  const queryTo = useMemo(() => {
    // Include full "to" day end
    const d = fromInputDate(to);
    if (!d) return undefined;
    return new Date(d.getTime() + 24 * 60 * 60 * 1000 - 1);
  }, [to]);

  useEffect(() => {
    const params: Record<string, string> = {};
    if (from) params.from = from;
    if (to) params.to = to;
    params.open = String(openOnly);
    if (script) params.script = script;
    if (timeframe) params.timeframe = timeframe;
    if (side && side !== "ALL") params.side = side;
    setSearchParams(params, { replace: true });
  }, [from, to, openOnly, script, side, timeframe, setSearchParams]);

  useEffect(() => {
    let aborted = false;
    async function load() {
      try {
        setLoading(true);
        setError(null);
        const data = await fetchTrades({
          from: queryFrom,
          to: queryTo,
          open: openOnly,
          status: openOnly ? "ACTIVE" : undefined,
          script: script || undefined,
          timeframe: timeframe || undefined,
          side: side && side !== "ALL" ? side : undefined,
        });
        if (!aborted) setTrades(data);
      } catch (e: any) {
        if (!aborted) setError(e?.message || "Unknown error");
      } finally {
        if (!aborted) setLoading(false);
      }
    }
    load();
    return () => {
      aborted = true;
    };
  }, [queryFrom, queryTo, openOnly, script, side, timeframe]);

  return (
    <div style={{ padding: 16 }}>
      <h1 style={{ marginBottom: 12 }}>Trades Summary</h1>

      <div
        style={{
          display: "grid",
          gridTemplateColumns: "repeat(auto-fit, minmax(180px, 1fr))",
          gap: 12,
          marginBottom: 16,
          alignItems: "end",
        }}
      >
        <div>
          <label style={{ display: "block", marginBottom: 4 }}>From</label>
          <input
            type="date"
            value={from}
            onChange={(e) => setFrom(e.target.value)}
            style={{ width: "100%", padding: 8 }}
          />
        </div>
        <div>
          <label style={{ display: "block", marginBottom: 4 }}>To</label>
          <input
            type="date"
            value={to}
            onChange={(e) => setTo(e.target.value)}
            style={{ width: "100%", padding: 8 }}
          />
        </div>
        <div>
          <label style={{ display: "block", marginBottom: 4 }}>Script</label>
          <input
            type="text"
            placeholder="e.g. AAPL"
            value={script}
            onChange={(e) => setScript(e.target.value)}
            style={{ width: "100%", padding: 8 }}
          />
        </div>
        <div>
          <label style={{ display: "block", marginBottom: 4 }}>Timeframe</label>
          <input
            type="text"
            placeholder="e.g. H1"
            value={timeframe}
            onChange={(e) => setTimeframe(e.target.value)}
            style={{ width: "100%", padding: 8 }}
          />
        </div>
        <div>
          <label style={{ display: "block", marginBottom: 4 }}>Side</label>
          <select
            value={side}
            onChange={(e) => setSide(e.target.value)}
            style={{ width: "100%", padding: 8 }}
          >
            <option value="ALL">All</option>
            <option value="BUY">BUY</option>
            <option value="SELL">SELL</option>
          </select>
        </div>
        <div>
          <label style={{ display: "block", marginBottom: 4 }}>Active only</label>
          <input
            type="checkbox"
            checked={openOnly}
            onChange={(e) => setOpenOnly(e.target.checked)}
          />{" "}
          Open
        </div>
      </div>

      <TradesTable trades={trades} loading={loading} error={error} />
    </div>
  );
};
