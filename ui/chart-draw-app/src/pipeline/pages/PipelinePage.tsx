import React, { useEffect, useState } from "react";
import { PatternScreener, PatternScreenerRun, PatternScreenerRunResult } from "../../patternScreener/types";
import { listScreeners, getRuns, getRunResults, triggerRun } from "../../patternScreener/api";
import { TradeSignal, TradeOrder } from "../types";
import { getSignal, getAllSignals, getOrdersForSignal, getAllOrders } from "../api";

const DARK_BG = "#1a1a1a";
const CARD_BG = "#1e1e1e";
const TEXT_PRIMARY = "#fff";
const TEXT_SECONDARY = "#aaa";
const ACCENT = "#90caf9";
const BORDER = "#444";

const containerStyle: React.CSSProperties = {
  background: DARK_BG,
  minHeight: "100vh",
  padding: 24,
  color: TEXT_PRIMARY,
  fontFamily: "system-ui",
};

const tabContainerStyle: React.CSSProperties = {
  display: "flex",
  gap: 8,
  marginBottom: 24,
  borderBottom: `1px solid ${BORDER}`,
};

const tabButtonStyle = (active: boolean): React.CSSProperties => ({
  background: active ? ACCENT : "transparent",
  color: active ? "#000" : TEXT_PRIMARY,
  border: "none",
  borderRadius: "4px 4px 0 0",
  padding: "12px 20px",
  cursor: "pointer",
  fontSize: 14,
  fontWeight: 500,
  transition: "all 0.2s",
});

const btnStyle = (bg: string): React.CSSProperties => ({
  background: bg,
  color: "#fff",
  border: "none",
  borderRadius: 4,
  padding: "8px 16px",
  cursor: "pointer",
  fontSize: 13,
});

const badgeStyle = (bg: string): React.CSSProperties => ({
  background: bg,
  color: "#fff",
  padding: "2px 8px",
  borderRadius: 3,
  fontSize: 12,
  fontWeight: 500,
  display: "inline-block",
});

const statusColor = (status: string): string => {
  if (!status) return "#555";
  const s = status.toUpperCase();
  if (s === "RUNNING") return "#fbc02d";
  if (s === "COMPLETED") return "#4caf50";
  if (s === "FAILED" || s === "ERROR") return "#d32f2f";
  if (s === "SIGNAL_CREATED") return "#4caf50";
  if (s === "PATTERN_FOUND") return "#2196f3";
  if (s === "DUPLICATE") return "#777";
  if (s === "NO_PATTERN") return "#555";
  if (s === "FILTERED") return "#ff9800";
  if (s === "WATCHING_ENTRY") return "#2196f3";
  if (s === "ENTRY_PENDING") return "#fbc02d";
  if (s === "ACTIVE") return "#4caf50";
  if (s === "EXIT_PENDING") return "#ff9800";
  if (s === "EXPIRED") return "#9e9e9e";
  if (s === "CANCELLED") return "#555";
  if (s === "COMPLETED") return "#4caf50";
  if (s === "OPEN") return "#fbc02d";
  if (s === "CLOSED") return "#4caf50";
  return "#aaa";
};

const tableStyle: React.CSSProperties = {
  width: "100%",
  borderCollapse: "collapse",
  marginTop: 12,
};

const cellStyle: React.CSSProperties = {
  padding: "12px 8px",
  textAlign: "left",
  borderBottom: `1px solid ${BORDER}`,
  fontSize: 13,
};

const rowStyle: React.CSSProperties = {
  background: CARD_BG,
};

const cardStyle: React.CSSProperties = {
  background: CARD_BG,
  border: `1px solid ${BORDER}`,
  borderRadius: 8,
  padding: 16,
  marginBottom: 16,
};

export default function PipelinePage() {
  const [activeTab, setActiveTab] = useState<"screeners" | "signals" | "orders">("screeners");

  // Screeners tab
  const [screeners, setScreeners] = useState<PatternScreener[]>([]);
  const [selectedScreener, setSelectedScreener] = useState<number | null>(null);
  const [runs, setRuns] = useState<PatternScreenerRun[]>([]);
  const [selectedRun, setSelectedRun] = useState<number | null>(null);
  const [runResults, setRunResults] = useState<PatternScreenerRunResult[]>([]);
  const [expandedSignals, setExpandedSignals] = useState<Set<number>>(new Set());
  const [signalsCache, setSignalsCache] = useState<Record<number, TradeSignal>>({});
  const [ordersCache, setOrdersCache] = useState<Record<number, TradeOrder[]>>({});

  // Signals tab
  const [allSignals, setAllSignals] = useState<TradeSignal[]>([]);
  const [statusFilter, setStatusFilter] = useState<string | null>(null);
  const [expandedSignalsTab, setExpandedSignalsTab] = useState<Set<number>>(new Set());

  // Orders tab
  const [allOrders, setAllOrders] = useState<TradeOrder[]>([]);

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [running, setRunning] = useState<number | null>(null);

  // Load screeners on mount
  useEffect(() => {
    setLoading(true);
    listScreeners()
      .then(setScreeners)
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false));
  }, []);

  // Load runs when screener selected
  useEffect(() => {
    if (selectedScreener) {
      getRuns(selectedScreener)
        .then(setRuns)
        .catch((e) => setError(e.message));
    }
  }, [selectedScreener]);

  // Load run results when run selected
  useEffect(() => {
    if (selectedScreener && selectedRun) {
      getRunResults(selectedScreener, selectedRun)
        .then(setRunResults)
        .catch((e) => setError(e.message));
    }
  }, [selectedScreener, selectedRun]);

  // Load all signals when signals tab active
  useEffect(() => {
    if (activeTab === "signals") {
      getAllSignals(statusFilter || undefined)
        .then(setAllSignals)
        .catch((e) => setError(e.message));
    }
  }, [activeTab, statusFilter]);

  // Load all orders when orders tab active
  useEffect(() => {
    if (activeTab === "orders") {
      // @ts-ignore
      import("../api").then(({ getAllOrders }) => {
        getAllOrders()
          .then(setAllOrders)
          .catch((e) => setError(e.message));
      });
    }
  }, [activeTab]);

  const handleExpandSignal = async (signalId: number) => {
    const newSet = new Set(expandedSignals);
    if (newSet.has(signalId)) {
      newSet.delete(signalId);
    } else {
      newSet.add(signalId);
      // Load signal details if not cached
      if (!signalsCache[signalId]) {
        try {
          const sig = await getSignal(signalId);
          setSignalsCache((prev) => ({ ...prev, [signalId]: sig }));
          // Also load orders for this signal
          const orders = await getOrdersForSignal(signalId);
          setOrdersCache((prev) => ({ ...prev, [signalId]: orders }));
        } catch (e: any) {
          setError(e.message);
        }
      }
    }
    setExpandedSignals(newSet);
  };

  const handleExpandSignalTab = async (signalId: number) => {
    const newSet = new Set(expandedSignalsTab);
    if (newSet.has(signalId)) {
      newSet.delete(signalId);
    } else {
      newSet.add(signalId);
      // Load orders for this signal if not cached
      if (!ordersCache[signalId]) {
        try {
          const orders = await getOrdersForSignal(signalId);
          setOrdersCache((prev) => ({ ...prev, [signalId]: orders }));
        } catch (e: any) {
          setError(e.message);
        }
      }
    }
    setExpandedSignalsTab(newSet);
  };

  const handleRunNow = async (screenerId: number) => {
    setRunning(screenerId);
    try {
      await triggerRun(screenerId);
      // Reload runs
      const updated = await getRuns(screenerId);
      setRuns(updated);
    } catch (e: any) {
      setError(e.message);
    } finally {
      setRunning(null);
    }
  };

  const formatTime = (dateStr: string | null): string => {
    if (!dateStr) return "—";
    try {
      const date = new Date(dateStr);
      return date.toLocaleString();
    } catch {
      return dateStr;
    }
  };

  const renderSignalDetails = (signalId: number) => {
    const signal = signalsCache[signalId];
    if (!signal) return <p style={{ color: TEXT_SECONDARY }}>Loading...</p>;

    const orders = ordersCache[signalId] || [];
    return (
      <div style={{ background: "#252525", padding: 12, borderRadius: 4, marginTop: 8 }}>
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(180px, 1fr))", gap: 12, marginBottom: 12 }}>
          <div>
            <span style={{ color: TEXT_SECONDARY, fontSize: 11 }}>Direction</span>
            <div style={{ fontSize: 14, fontWeight: 600, marginTop: 2, color: signal.direction === 'LONG' ? '#4caf50' : '#d32f2f' }}>
              {signal.direction}
            </div>
          </div>
          <div>
            <span style={{ color: TEXT_SECONDARY, fontSize: 11 }}>Pattern</span>
            <div style={{ fontSize: 14, fontWeight: 600, marginTop: 2 }}>{signal.patternType}</div>
          </div>
          <div>
            <span style={{ color: TEXT_SECONDARY, fontSize: 11 }}>Entry Price</span>
            <div style={{ fontSize: 14, fontWeight: 600, marginTop: 2 }}>{signal.entryPrice.toFixed(2)}</div>
          </div>
          <div>
            <span style={{ color: TEXT_SECONDARY, fontSize: 11 }}>Stop Loss</span>
            <div style={{ fontSize: 14, fontWeight: 600, marginTop: 2 }}>{signal.stopLoss.toFixed(2)}</div>
          </div>
          <div>
            <span style={{ color: TEXT_SECONDARY, fontSize: 11 }}>Target</span>
            <div style={{ fontSize: 14, fontWeight: 600, marginTop: 2 }}>{signal.target.toFixed(2)}</div>
          </div>
          <div>
            <span style={{ color: TEXT_SECONDARY, fontSize: 11 }}>RR Ratio</span>
            <div style={{ fontSize: 14, fontWeight: 600, marginTop: 2 }}>{signal.rrRatio.toFixed(2)}</div>
          </div>
          <div>
            <span style={{ color: TEXT_SECONDARY, fontSize: 11 }}>ML Score</span>
            <div style={{ fontSize: 14, fontWeight: 600, marginTop: 2 }}>{signal.mlScore.toFixed(2)}</div>
          </div>
          <div>
            <span style={{ color: TEXT_SECONDARY, fontSize: 11 }}>Status</span>
            <div style={{ marginTop: 2 }}>
              <span style={badgeStyle(statusColor(signal.status))}>{signal.status}</span>
            </div>
          </div>
        </div>
        {orders.length > 0 && (
          <div style={{ marginTop: 12, borderTop: `1px solid ${BORDER}`, paddingTop: 12 }}>
            <h4 style={{ margin: "0 0 8px 0", color: ACCENT, fontSize: 13 }}>Orders ({orders.length})</h4>
            <table style={tableStyle}>
              <thead>
                <tr style={{ background: "#1a1a1a" }}>
                  <th style={cellStyle}>Entry</th>
                  <th style={cellStyle}>Exit</th>
                  <th style={cellStyle}>PnL</th>
                  <th style={cellStyle}>Status</th>
                </tr>
              </thead>
              <tbody>
                {orders.map((ord) => (
                  <tr key={ord.id} style={rowStyle}>
                    <td style={cellStyle}>{ord.entryPrice.toFixed(2)}</td>
                    <td style={cellStyle}>{ord.exitPrice ? ord.exitPrice.toFixed(2) : "—"}</td>
                    <td style={{ ...cellStyle, color: ord.realisedPnl === null ? TEXT_SECONDARY : ord.realisedPnl >= 0 ? "#4caf50" : "#d32f2f" }}>
                      {ord.realisedPnl === null ? "—" : ord.realisedPnl.toFixed(2)}
                    </td>
                    <td style={cellStyle}>
                      <span style={badgeStyle(statusColor(ord.status))}>{ord.status}</span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    );
  };

  return (
    <div style={containerStyle}>
      <h1 style={{ color: ACCENT, margin: "0 0 24px 0" }}>Pipeline</h1>

      {error && (
        <div style={{ background: "#d32f2f", padding: 12, borderRadius: 4, marginBottom: 16, fontSize: 13 }}>
          {error}
        </div>
      )}

      {/* Tabs */}
      <div style={tabContainerStyle}>
        <button
          onClick={() => setActiveTab("screeners")}
          style={tabButtonStyle(activeTab === "screeners")}
        >
          Screener Runs
        </button>
        <button
          onClick={() => setActiveTab("signals")}
          style={tabButtonStyle(activeTab === "signals")}
        >
          All Signals
        </button>
        <button
          onClick={() => setActiveTab("orders")}
          style={tabButtonStyle(activeTab === "orders")}
        >
          Orders
        </button>
      </div>

      {/* Tab 1: Screener Runs */}
      {activeTab === "screeners" && (
        <div>
          {loading ? (
            <p style={{ color: TEXT_SECONDARY }}>Loading screeners...</p>
          ) : screeners.length === 0 ? (
            <p style={{ color: TEXT_SECONDARY }}>No screeners configured.</p>
          ) : (
            <div>
              {screeners.map((screener) => (
                <div key={screener.id} style={{ marginBottom: 16 }}>
                  <div
                    onClick={() => setSelectedScreener(selectedScreener === screener.id ? null : screener.id)}
                    style={{
                      ...cardStyle,
                      cursor: "pointer",
                      display: "flex",
                      justifyContent: "space-between",
                      alignItems: "center",
                    }}
                  >
                    <div>
                      <h3 style={{ margin: "0 0 4px 0", color: ACCENT, fontSize: 16 }}>{screener.name}</h3>
                      <p style={{ margin: 0, color: TEXT_SECONDARY, fontSize: 12 }}>
                        {screener.segments} • Last run: {formatTime(screener.lastRunAt)}
                      </p>
                    </div>
                    <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
                      <span style={badgeStyle(screener.enabled ? "#4caf50" : "#777")}>
                        {screener.enabled ? "Enabled" : "Disabled"}
                      </span>
                      <button
                        onClick={(e) => {
                          e.stopPropagation();
                          handleRunNow(screener.id);
                        }}
                        disabled={running === screener.id}
                        style={btnStyle(running === screener.id ? "#555" : "#1565c0")}
                      >
                        {running === screener.id ? "Running..." : "Run Now"}
                      </button>
                    </div>
                  </div>

                  {selectedScreener === screener.id && (
                    <div style={{ marginLeft: 16, marginTop: 12, borderLeft: `2px solid ${BORDER}`, paddingLeft: 16 }}>
                      {runs.length === 0 ? (
                        <p style={{ color: TEXT_SECONDARY, fontSize: 13 }}>No runs yet.</p>
                      ) : (
                        <div>
                          <h4 style={{ color: ACCENT, margin: "0 0 8px 0", fontSize: 13 }}>Recent Runs</h4>
                          <table style={tableStyle}>
                            <thead>
                              <tr style={{ background: "transparent" }}>
                                <th style={cellStyle}>Started</th>
                                <th style={cellStyle}>Status</th>
                                <th style={cellStyle}>Symbols</th>
                                <th style={cellStyle}>Signals</th>
                              </tr>
                            </thead>
                            <tbody>
                              {runs.map((run) => (
                                <React.Fragment key={run.id}>
                                  <tr
                                    onClick={() => setSelectedRun(selectedRun === run.id ? null : run.id)}
                                    style={{ ...rowStyle, cursor: "pointer" }}
                                  >
                                    <td style={cellStyle}>{formatTime(run.startedAt)}</td>
                                    <td style={cellStyle}>
                                      <span style={badgeStyle(statusColor(run.status))}>{run.status}</span>
                                    </td>
                                    <td style={cellStyle}>{run.totalSymbols}</td>
                                    <td style={cellStyle}>{run.signalsCreated}</td>
                                  </tr>
                                  {selectedRun === run.id && (
                                    <tr style={{ background: "#252525" }}>
                                      <td colSpan={4} style={{ padding: 12 }}>
                                        <h4 style={{ color: ACCENT, margin: "0 0 8px 0", fontSize: 12 }}>Results</h4>
                                        <table style={tableStyle}>
                                          <thead>
                                            <tr style={{ background: "transparent" }}>
                                              <th style={cellStyle}>Symbol</th>
                                              <th style={cellStyle}>Status</th>
                                              <th style={cellStyle}>Pattern / Error</th>
                                              <th style={cellStyle}>Signal ID</th>
                                            </tr>
                                          </thead>
                                          <tbody>
                                            {runResults.map((result) => {
                                              let patternLabel = "—";
                                              if (result.status === "ERROR") {
                                                patternLabel = result.errorMessage || "Unknown error";
                                              } else if (result.patternsFound && result.patternsFound !== "[]") {
                                                try {
                                                  const parsed = JSON.parse(result.patternsFound);
                                                  patternLabel = parsed.map((p: any) => p.patternType).filter(Boolean).join(", ") || result.patternsFound;
                                                } catch {
                                                  patternLabel = result.patternsFound;
                                                }
                                              }
                                              return (
                                              <tr key={result.id} style={rowStyle}>
                                                <td style={cellStyle}>{result.symbol}</td>
                                                <td style={cellStyle}>
                                                  <span style={badgeStyle(statusColor(result.status))}>{result.status}</span>
                                                </td>
                                                <td style={{ ...cellStyle, color: result.status === "ERROR" ? "#ef9a9a" : TEXT_PRIMARY, maxWidth: 300, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}
                                                    title={result.status === "ERROR" ? result.errorMessage || "" : ""}>
                                                  {patternLabel}
                                                </td>
                                                <td style={cellStyle}>
                                                  {result.signalId ? (
                                                    <button
                                                      onClick={() => handleExpandSignal(result.signalId!)}
                                                      style={{
                                                        ...btnStyle("#2196f3"),
                                                        padding: "4px 8px",
                                                        fontSize: 12,
                                                      }}
                                                    >
                                                      {expandedSignals.has(result.signalId) ? "Hide" : `Signal #${result.signalId}`}
                                                    </button>
                                                  ) : (
                                                    "—"
                                                  )}
                                                </td>
                                              </tr>
                                              );
                                            })}
                                          </tbody>
                                        </table>
                                        {Array.from(expandedSignals).map((sigId) => (
                                          <div key={sigId} style={{ marginTop: 12 }}>
                                            {renderSignalDetails(sigId)}
                                          </div>
                                        ))}
                                      </td>
                                    </tr>
                                  )}
                                </React.Fragment>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      )}
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {/* Tab 2: All Signals */}
      {activeTab === "signals" && (
        <div>
          <div style={{ display: "flex", gap: 8, marginBottom: 16, flexWrap: "wrap" }}>
            {["All", "WATCHING_ENTRY", "ENTRY_PENDING", "ACTIVE", "COMPLETED", "EXPIRED"].map((st) => (
              <button
                key={st}
                onClick={() => setStatusFilter(st === "All" ? null : st)}
                style={{
                  ...btnStyle(statusFilter === (st === "All" ? null : st) ? ACCENT : "#333"),
                  color: statusFilter === (st === "All" ? null : st) ? "#000" : TEXT_PRIMARY,
                }}
              >
                {st}
              </button>
            ))}
          </div>

          {loading ? (
            <p style={{ color: TEXT_SECONDARY }}>Loading signals...</p>
          ) : allSignals.length === 0 ? (
            <p style={{ color: TEXT_SECONDARY }}>No signals.</p>
          ) : (
            <table style={tableStyle}>
              <thead>
                <tr style={{ background: "transparent" }}>
                  <th style={cellStyle}>Symbol</th>
                  <th style={cellStyle}>Direction</th>
                  <th style={cellStyle}>Pattern</th>
                  <th style={cellStyle}>Entry</th>
                  <th style={cellStyle}>SL</th>
                  <th style={cellStyle}>Target</th>
                  <th style={cellStyle}>RR</th>
                  <th style={cellStyle}>ML</th>
                  <th style={cellStyle}>Status</th>
                  <th style={cellStyle}>Time</th>
                </tr>
              </thead>
              <tbody>
                {allSignals.map((sig) => (
                  <React.Fragment key={sig.id}>
                    <tr
                      style={rowStyle}
                      onClick={() => handleExpandSignalTab(sig.id)}
                    >
                      <td style={cellStyle}>{sig.symbol}</td>
                      <td style={{ ...cellStyle, color: sig.direction === 'LONG' ? '#4caf50' : '#d32f2f' }}>
                        {sig.direction}
                      </td>
                      <td style={cellStyle}>{sig.patternType}</td>
                      <td style={cellStyle}>{sig.entryPrice.toFixed(2)}</td>
                      <td style={cellStyle}>{sig.stopLoss.toFixed(2)}</td>
                      <td style={cellStyle}>{sig.target.toFixed(2)}</td>
                      <td style={cellStyle}>{sig.rrRatio.toFixed(2)}</td>
                      <td style={cellStyle}>{sig.mlScore.toFixed(2)}</td>
                      <td style={cellStyle}>
                        <span style={badgeStyle(statusColor(sig.status))}>{sig.status}</span>
                      </td>
                      <td style={cellStyle}>{formatTime(sig.signalTime)}</td>
                    </tr>
                    {expandedSignalsTab.has(sig.id) && (
                      <tr style={{ background: "#252525" }}>
                        <td colSpan={10} style={{ padding: 12 }}>
                          {renderSignalDetails(sig.id)}
                        </td>
                      </tr>
                    )}
                  </React.Fragment>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}

      {/* Tab 3: Orders */}
      {activeTab === "orders" && (
        <div>
          {loading ? (
            <p style={{ color: TEXT_SECONDARY }}>Loading orders...</p>
          ) : allOrders.length === 0 ? (
            <p style={{ color: TEXT_SECONDARY }}>No orders.</p>
          ) : (
            <table style={tableStyle}>
              <thead>
                <tr style={{ background: "transparent" }}>
                  <th style={cellStyle}>Symbol</th>
                  <th style={cellStyle}>Direction</th>
                  <th style={cellStyle}>Entry</th>
                  <th style={cellStyle}>Exit</th>
                  <th style={cellStyle}>PnL</th>
                  <th style={cellStyle}>Status</th>
                  <th style={cellStyle}>Entry Time</th>
                </tr>
              </thead>
              <tbody>
                {allOrders.map((ord) => (
                  <tr key={ord.id} style={rowStyle}>
                    <td style={cellStyle}>{ord.symbol}</td>
                    <td style={{ ...cellStyle, color: ord.direction === 'LONG' ? '#4caf50' : '#d32f2f' }}>
                      {ord.direction}
                    </td>
                    <td style={cellStyle}>{ord.entryPrice.toFixed(2)}</td>
                    <td style={cellStyle}>{ord.exitPrice ? ord.exitPrice.toFixed(2) : "—"}</td>
                    <td style={{ ...cellStyle, color: ord.realisedPnl === null ? TEXT_SECONDARY : ord.realisedPnl >= 0 ? "#4caf50" : "#d32f2f" }}>
                      {ord.realisedPnl === null ? "—" : ord.realisedPnl.toFixed(2)}
                    </td>
                    <td style={cellStyle}>
                      <span style={badgeStyle(statusColor(ord.status))}>{ord.status}</span>
                    </td>
                    <td style={cellStyle}>{formatTime(ord.entryTime)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
    </div>
  );
}
