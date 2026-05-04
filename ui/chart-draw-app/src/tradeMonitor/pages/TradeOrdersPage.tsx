import React, { useState, useEffect } from "react";
import {
  getTradeOrders,
  getTradeSummary,
  triggerScan,
  getDistinctStrategyTypes,
  getDistinctPatterns,
  TradeOrder,
  TradeSummaryDto,
  TradeOrderStatus,
} from "../api";
import { TradeActionTimelineDark } from "../components/TradeActionTimelineDark";

const PATTERN_FILTER_KEY = "tradeOrders.filter.pattern";

const TradeOrdersPage: React.FC = () => {
  const [orders, setOrders] = useState<TradeOrder[]>([]);
  const [summary, setSummary] = useState<TradeSummaryDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>("");
  const [filterStatus, setFilterStatus] = useState<TradeOrderStatus | "ALL">(
    "ALL"
  );
  const [filterStrategy, setFilterStrategy] = useState<string>("");
  const [filterPattern, setFilterPattern] = useState<string>(
    () => localStorage.getItem(PATTERN_FILTER_KEY) || ""
  );
  const [strategyOptions, setStrategyOptions] = useState<string[]>([]);
  const [patternOptions, setPatternOptions] = useState<string[]>([]);
  const [scanning, setScanning] = useState(false);
  const [scanMessage, setScanMessage] = useState<string>("");
  const [expandedId, setExpandedId] = useState<number | null>(null);

  // Persist pattern filter so it survives reloads (URL doesn't carry it on this page).
  useEffect(() => {
    if (filterPattern) localStorage.setItem(PATTERN_FILTER_KEY, filterPattern);
    else localStorage.removeItem(PATTERN_FILTER_KEY);
  }, [filterPattern]);

  // Load dropdown options once on mount.
  useEffect(() => {
    getDistinctStrategyTypes().then(setStrategyOptions).catch(() => {});
    getDistinctPatterns().then(setPatternOptions).catch(() => {});
  }, []);

  const fetchData = async (
    status?: string,
    strategy?: string,
    pattern?: string,
  ) => {
    setLoading(true);
    setError("");
    try {
      const [ordersData, summaryData] = await Promise.all([
        getTradeOrders(status, strategy, pattern),
        getTradeSummary(),
      ]);
      setOrders(ordersData);
      setSummary(summaryData);
    } catch (err) {
      setError(`Error loading data: ${err}`);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchData(
      filterStatus === "ALL" ? undefined : filterStatus,
      filterStrategy || undefined,
      filterPattern || undefined,
    );
  }, [filterStatus, filterStrategy, filterPattern]);

  useEffect(() => {
    const interval = setInterval(
      () =>
        fetchData(
          filterStatus === "ALL" ? undefined : filterStatus,
          filterStrategy || undefined,
          filterPattern || undefined,
        ),
      30000
    );
    return () => clearInterval(interval);
  }, [filterStatus, filterStrategy, filterPattern]);

  const handleScan = async () => {
    setScanning(true);
    setScanMessage("");
    try {
      const result = await triggerScan();
      setScanMessage(`Scan completed: ${result.created} new signals created`);
      setTimeout(() => setScanMessage(""), 5000);
      fetchData(
        filterStatus === "ALL" ? undefined : filterStatus,
        filterStrategy || undefined,
        filterPattern || undefined,
      );
    } catch (err) {
      setError(`Error during scan: ${err}`);
    } finally {
      setScanning(false);
    }
  };

  const containerStyle: React.CSSProperties = {
    padding: 24,
    backgroundColor: "#1a1a1a",
    minHeight: "100vh",
    color: "#fff",
    fontFamily: "system-ui",
  };

  const summaryBarStyle: React.CSSProperties = {
    display: "grid",
    gridTemplateColumns: "repeat(auto-fit, minmax(150px, 1fr))",
    gap: 12,
    marginBottom: 24,
    backgroundColor: "#2a2a2a",
    padding: 16,
    borderRadius: 4,
  };

  const summaryItemStyle: React.CSSProperties = {
    textAlign: "center",
  };

  const summaryLabelStyle: React.CSSProperties = {
    fontSize: 12,
    color: "#aaa",
    marginBottom: 4,
  };

  const summaryValueStyle = (value: number): React.CSSProperties => ({
    fontSize: 18,
    fontWeight: "bold",
    color:
      value > 0 ? "#4caf50" : value < 0 ? "#ef5350" : "#90caf9",
  });

  const controlsStyle: React.CSSProperties = {
    display: "flex",
    justifyContent: "space-between",
    alignItems: "center",
    marginBottom: 16,
  };

  const filterButtonStyle = (active: boolean): React.CSSProperties => ({
    padding: "8px 16px",
    margin: "0 4px",
    backgroundColor: active ? "#90caf9" : "#333",
    color: active ? "#000" : "#fff",
    border: "none",
    borderRadius: 4,
    cursor: "pointer",
    fontWeight: "bold",
  });

  const scanButtonStyle: React.CSSProperties = {
    padding: "8px 16px",
    backgroundColor: "#90caf9",
    color: "#000",
    border: "none",
    borderRadius: 4,
    cursor: "pointer",
    fontWeight: "bold",
    opacity: scanning ? 0.6 : 1,
  };

  const tableStyle: React.CSSProperties = {
    width: "100%",
    borderCollapse: "collapse",
    marginTop: 16,
  };

  const thStyle: React.CSSProperties = {
    color: "#90caf9",
    textAlign: "left",
    padding: 12,
    borderBottom: "2px solid #333",
    fontSize: 12,
  };

  const tdStyle: React.CSSProperties = {
    padding: 12,
    borderBottom: "1px solid #333",
    fontSize: 13,
  };

  const directionStyle = (direction: string): React.CSSProperties => ({
    color: direction === "LONG" ? "#4caf50" : "#ef5350",
    fontWeight: "bold",
  });

  const pnlStyle = (pnl: number | undefined): React.CSSProperties => ({
    color: pnl ? (pnl > 0 ? "#4caf50" : "#ef5350") : "#aaa",
  });

  const statusBadgeStyle = (status: string): React.CSSProperties => ({
    display: "inline-block",
    padding: "4px 8px",
    borderRadius: 4,
    fontSize: 11,
    fontWeight: "bold",
    backgroundColor:
      status === "OPEN"
        ? "#ffc107"
        : status === "CLOSED"
          ? "#4caf50"
          : "#ef5350",
    color: status === "OPEN" ? "#000" : "#fff",
  });

  if (loading && !orders.length) {
    return (
      <div style={containerStyle}>
        <p>Loading...</p>
      </div>
    );
  }

  const realisedPnl = summary?.realisedPnlInr || 0;
  const unrealisedPnl = summary?.unrealisedPnlInr || 0;
  const totalPnl = summary?.totalPnlInr || 0;
  const winRate = summary?.winRatePct || 0;

  return (
    <div style={containerStyle}>
      {error && (
        <div
          style={{
            backgroundColor: "#d32f2f",
            color: "#fff",
            padding: 12,
            borderRadius: 4,
            marginBottom: 16,
          }}
        >
          {error}
        </div>
      )}

      {scanMessage && (
        <div
          style={{
            backgroundColor: "#4caf50",
            color: "#fff",
            padding: 12,
            borderRadius: 4,
            marginBottom: 16,
          }}
        >
          {scanMessage}
        </div>
      )}

      <h1 style={{ margin: "0 0 24px 0" }}>Trade Orders</h1>

      {summary && (
        <div style={summaryBarStyle}>
          <div style={summaryItemStyle}>
            <div style={summaryLabelStyle}>Realised P&L</div>
            <div style={summaryValueStyle(realisedPnl)}>
              {realisedPnl > 0 ? "+" : ""}
              {realisedPnl.toFixed(2)}
            </div>
          </div>
          <div style={summaryItemStyle}>
            <div style={summaryLabelStyle}>Unrealised P&L</div>
            <div style={summaryValueStyle(unrealisedPnl)}>
              {unrealisedPnl > 0 ? "+" : ""}
              {unrealisedPnl.toFixed(2)}
            </div>
          </div>
          <div style={summaryItemStyle}>
            <div style={summaryLabelStyle}>Total P&L</div>
            <div style={summaryValueStyle(totalPnl)}>
              {totalPnl > 0 ? "+" : ""}
              {totalPnl.toFixed(2)}
            </div>
          </div>
          <div style={summaryItemStyle}>
            <div style={summaryLabelStyle}>Win Rate</div>
            <div style={{ fontSize: 18, fontWeight: "bold", color: "#90caf9" }}>
              {winRate.toFixed(1)}%
            </div>
          </div>
          <div style={summaryItemStyle}>
            <div style={summaryLabelStyle}>Avg Win</div>
            <div style={{ fontSize: 18, fontWeight: "bold", color: "#4caf50" }}>
              {(summary.avgWinInr || 0).toFixed(2)}
            </div>
          </div>
          <div style={summaryItemStyle}>
            <div style={summaryLabelStyle}>Avg Loss</div>
            <div style={{ fontSize: 18, fontWeight: "bold", color: "#ef5350" }}>
              {(summary.avgLossInr || 0).toFixed(2)}
            </div>
          </div>
          <div style={summaryItemStyle}>
            <div style={summaryLabelStyle}>Open Positions</div>
            <div style={{ fontSize: 18, fontWeight: "bold", color: "#90caf9" }}>
              {summary.openTrades}
            </div>
          </div>
          <div style={summaryItemStyle}>
            <div style={summaryLabelStyle}>Total Trades</div>
            <div style={{ fontSize: 18, fontWeight: "bold", color: "#90caf9" }}>
              {summary.totalTrades}
            </div>
          </div>
        </div>
      )}

      <div style={controlsStyle}>
        <div>
          <button
            style={filterButtonStyle(filterStatus === "ALL")}
            onClick={() => setFilterStatus("ALL")}
          >
            All
          </button>
          <button
            style={filterButtonStyle(filterStatus === "OPEN")}
            onClick={() => setFilterStatus("OPEN")}
          >
            Open
          </button>
          <button
            style={filterButtonStyle(filterStatus === "CLOSED")}
            onClick={() => setFilterStatus("CLOSED")}
          >
            Closed
          </button>
          <button
            style={filterButtonStyle(filterStatus === "FAILED")}
            onClick={() => setFilterStatus("FAILED")}
          >
            Failed
          </button>
        </div>
        <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
          <label style={{ fontSize: 12, color: "#aaa" }}>Strategy:</label>
          <select
            value={filterStrategy}
            onChange={(e) => setFilterStrategy(e.target.value)}
            style={{
              padding: "6px 10px",
              backgroundColor: "#2a2a2a",
              color: "#fff",
              border: "1px solid #444",
              borderRadius: 4,
              fontSize: 13,
            }}
          >
            <option value="">All</option>
            {strategyOptions.map((s) => (
              <option key={s} value={s}>{s}</option>
            ))}
          </select>
          <label style={{ fontSize: 12, color: "#aaa", marginLeft: 8 }}>
            Pattern:
            {filterPattern && (
              <span style={{ fontSize: 10, color: "#666", marginLeft: 4 }}>(saved)</span>
            )}
          </label>
          <select
            value={filterPattern}
            onChange={(e) => setFilterPattern(e.target.value)}
            style={{
              padding: "6px 10px",
              backgroundColor: "#2a2a2a",
              color: "#fff",
              border: "1px solid #444",
              borderRadius: 4,
              fontSize: 13,
            }}
          >
            <option value="">All</option>
            {patternOptions.map((p) => (
              <option key={p} value={p}>{p}</option>
            ))}
          </select>
        </div>
        <button
          style={scanButtonStyle}
          onClick={handleScan}
          disabled={scanning}
        >
          {scanning ? "Scanning..." : "Scan Now"}
        </button>
      </div>

      <table style={tableStyle}>
        <thead>
          <tr>
            <th style={thStyle}>Entry Time</th>
            <th style={thStyle}>Exit Time</th>
            <th style={thStyle}>Symbol</th>
            <th style={thStyle}>Segment</th>
            <th style={thStyle}>Dir</th>
            <th style={thStyle}>Qty</th>
            <th style={thStyle}>Entry</th>
            <th style={thStyle}>Exit</th>
            <th style={thStyle}>Stop</th>
            <th style={thStyle}>Target</th>
            <th style={thStyle}>P&L</th>
            <th style={thStyle}>LTP</th>
            <th style={thStyle}>Unreal P&L</th>
            <th style={thStyle}>Status</th>
            <th style={thStyle}>Exit Reason</th>
            <th style={thStyle}>Pattern</th>
          </tr>
        </thead>
        <tbody>
          {orders.length === 0 ? (
            <tr>
              <td
                colSpan={16}
                style={{
                  ...tdStyle,
                  textAlign: "center",
                  color: "#aaa",
                }}
              >
                No orders found
              </td>
            </tr>
          ) : (
            orders.map((order) => (
              <React.Fragment key={order.id}>
              <tr
                onClick={() => order.signal?.id && setExpandedId(prev => prev === order.signal!.id ? null : order.signal!.id)}
                style={{ cursor: "pointer" }}
              >
                <td style={tdStyle}>
                  <span style={{ marginRight: 8, fontSize: 10, color: "#888" }}>
                    {expandedId === order.signal?.id ? "▼" : "▶"}
                  </span>
                  {new Date(order.entryTime).toLocaleString()}
                </td>
                <td style={tdStyle}>
                  {order.exitTime
                    ? new Date(order.exitTime).toLocaleString()
                    : "-"}
                </td>
                <td style={tdStyle}>{order.symbol}</td>
                <td style={tdStyle}>{order.segment}</td>
                <td style={tdStyle}>
                  <span style={directionStyle(order.direction)}>
                    {order.direction}
                  </span>
                </td>
                <td style={tdStyle}>{order.quantity}</td>
                <td style={tdStyle}>
                  {order.entryPrice != null ? order.entryPrice.toFixed(4) : "-"}
                  {order.segment === "OPT" &&
                    order.underlyingEntryPrice != null && (
                      <div style={{ fontSize: 10, color: "#888" }}>
                        {order.underlyingSymbol} @{" "}
                        {order.underlyingEntryPrice.toFixed(2)}
                      </div>
                    )}
                </td>
                <td style={tdStyle}>
                  {order.exitPrice != null ? order.exitPrice.toFixed(4) : "-"}
                  {order.segment === "OPT" &&
                    order.underlyingExitPrice != null && (
                      <div style={{ fontSize: 10, color: "#888" }}>
                        {order.underlyingSymbol} @{" "}
                        {order.underlyingExitPrice.toFixed(2)}
                      </div>
                    )}
                </td>
                <td style={tdStyle}>
                  {order.stopLoss != null ? order.stopLoss.toFixed(2) : "-"}
                </td>
                <td style={tdStyle}>
                  {order.target != null ? order.target.toFixed(2) : "-"}
                </td>
                <td style={pnlStyle(order.realisedPnl)}>
                  {order.realisedPnl != null ? (
                    <>
                      {order.realisedPnl > 0 ? "+" : ""}
                      {order.realisedPnl.toFixed(2)}
                    </>
                  ) : order.status === "OPEN" ? (
                    "~"
                  ) : (
                    "-"
                  )}
                </td>
                <td style={tdStyle}>
                  {order.ltp != null ? order.ltp.toFixed(2) : "-"}
                </td>
                <td style={pnlStyle(order.unrealisedPnl)}>
                  {order.unrealisedPnl != null ? (
                    <>
                      {order.unrealisedPnl > 0 ? "+" : ""}
                      {order.unrealisedPnl.toFixed(2)}
                    </>
                  ) : (
                    "-"
                  )}
                </td>
                <td style={tdStyle}>
                  <span style={statusBadgeStyle(order.status)}>
                    {order.status}
                  </span>
                </td>
                <td style={tdStyle}>{order.exitReason || "-"}</td>
                <td style={tdStyle}>{order.signal?.patternType || "-"}</td>
              </tr>
              {expandedId === order.signal?.id && order.signal?.id && (
                <tr key={`exp-${order.id}`}>
                  <td colSpan={16} style={{ padding: 0 }}>
                    <TradeActionTimelineDark signalId={order.signal!.id} />
                  </td>
                </tr>
              )}
              </React.Fragment>
            ))
          )}
        </tbody>
      </table>
    </div>
  );
};

export default TradeOrdersPage;
