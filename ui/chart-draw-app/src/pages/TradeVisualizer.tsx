import React, { useEffect, useRef, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { createChart, IChartApi, ISeriesApi, CandlestickData, LineStyle } from "lightweight-charts";
import { getApiUrl } from "../config/api";
import { withAuth } from "../utils/apiHelper";

interface OhlcBar {
  time: number;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
}

const INTERVALS = ["FifteenMinute", "OneHour", "Day"];

interface TradeSignal {
  id: number;
  symbol: string;
  direction: string;
  entryPrice: number;
  stopLoss: number;
  target: number;
  signalTime: string;
  candleTime: string;
  timeframe: string;
  status: string;
  strategyType: string;
  patternType: string | null;
  mlScore: number | null;
  notes: string | null;
}

export default function TradeVisualizer() {
  const [searchParams, setSearchParams] = useSearchParams();
  const chartRef = useRef<HTMLDivElement>(null);
  const chartApi = useRef<IChartApi | null>(null);
  const candleSeries = useRef<ISeriesApi<"Candlestick"> | null>(null);

  const [symbol, setSymbol] = useState(searchParams.get("symbol") || "RELIANCE");
  const [interval, setInterval] = useState(searchParams.get("interval") || "OneHour");
  const [entryTime, setEntryTime] = useState(searchParams.get("entryTime") || "");
  const [entryPrice, setEntryPrice] = useState(searchParams.get("entryPrice") || "");
  const [stopLoss, setStopLoss] = useState(searchParams.get("stopLoss") || "");
  const [target, setTarget] = useState(searchParams.get("target") || "");
  const [exitTime, setExitTime] = useState(searchParams.get("exitTime") || "");
  const [exitPrice, setExitPrice] = useState(searchParams.get("exitPrice") || "");
  const [direction, setDirection] = useState(searchParams.get("direction") || "LONG");
  const [loading, setLoading] = useState(false);
  const [signals, setSignals] = useState<TradeSignal[]>([]);
  const [loadingSignals, setLoadingSignals] = useState(false);
  const [signalFilter, setSignalFilter] = useState("");
  const [error, setError] = useState("");
  const [barCount, setBarCount] = useState(0);

  const loadSignals = async () => {
    setLoadingSignals(true);
    try {
      const url = getApiUrl("/api/trade-signals");
      const res = await fetch(url.toString(), withAuth());
      if (!res.ok) throw new Error("Failed to fetch signals");
      const data: TradeSignal[] = await res.json();
      // Sort by time desc
      data.sort((a, b) => new Date(b.signalTime).getTime() - new Date(a.signalTime).getTime());
      setSignals(data);
    } catch (e: any) {
      setError(e.message);
    } finally {
      setLoadingSignals(false);
    }
  };

  const loadFromSignal = (sig: TradeSignal) => {
    setSymbol(sig.symbol);
    setInterval(sig.timeframe === "OneHour" ? "OneHour" : sig.timeframe === "FifteenMinute" ? "FifteenMinute" : "OneHour");
    setEntryTime(sig.signalTime || sig.candleTime || "");
    setEntryPrice(String(sig.entryPrice));
    setStopLoss(String(sig.stopLoss));
    setTarget(String(sig.target));
    setDirection(sig.direction);
    setExitTime("");
    setExitPrice("");
    // Auto-load chart
    setTimeout(() => handleVisualize(), 100);
  };

  const loadChart = async () => {
    if (!symbol) return;
    setLoading(true);
    setError("");

    try {
      const url = getApiUrl(`/api/ohlc?symbol=${symbol}&interval=${interval}`);
      const res = await fetch(url.toString(), withAuth());
      if (!res.ok) throw new Error(`Failed to fetch: ${res.status}`);
      const data: OhlcBar[] = await res.json();

      if (!data || data.length === 0) {
        setError("No data found");
        setLoading(false);
        return;
      }

      // Sort and dedup
      const sorted = data
        .sort((a, b) => a.time - b.time)
        .filter((bar, i, arr) => i === 0 || bar.time !== arr[i - 1].time);

      setBarCount(sorted.length);

      // Filter to show context around the trade
      let filtered = sorted;
      if (entryTime) {
        const entryTs = new Date(entryTime).getTime() / 1000;
        const contextBars = interval === "FifteenMinute" ? 200 : interval === "Day" ? 60 : 100;
        const startIdx = Math.max(0, sorted.findIndex(b => b.time >= entryTs) - contextBars);
        const endIdx = Math.min(sorted.length, startIdx + contextBars * 3);
        filtered = sorted.slice(startIdx, endIdx);
      }

      // Create or reset chart
      if (chartApi.current) {
        chartApi.current.remove();
      }

      if (!chartRef.current) return;

      const chart = createChart(chartRef.current, {
        width: chartRef.current.clientWidth,
        height: 600,
        layout: {
          background: { color: "#0a0a0a" },
          textColor: "#d1d4dc",
        },
        grid: {
          vertLines: { color: "#1a1a2e" },
          horzLines: { color: "#1a1a2e" },
        },
        crosshair: {
          mode: 0,
        },
        timeScale: {
          timeVisible: true,
          secondsVisible: false,
        },
      });
      chartApi.current = chart;

      // Candlestick series
      const series = chart.addCandlestickSeries({
        upColor: "#26a69a",
        downColor: "#ef5350",
        borderDownColor: "#ef5350",
        borderUpColor: "#26a69a",
        wickDownColor: "#ef5350",
        wickUpColor: "#26a69a",
      });

      const candleData: CandlestickData[] = filtered.map(b => ({
        time: b.time as any,
        open: b.open,
        high: b.high,
        low: b.low,
        close: b.close,
      }));

      series.setData(candleData);
      candleSeries.current = series;

      // Add markers and lines
      const markers: any[] = [];

      // Entry marker
      if (entryTime && entryPrice) {
        const entryTs = new Date(entryTime).getTime() / 1000;
        const ep = parseFloat(entryPrice);
        markers.push({
          time: entryTs,
          position: direction === "LONG" ? "belowBar" : "aboveBar",
          color: "#2196F3",
          shape: direction === "LONG" ? "arrowUp" : "arrowDown",
          text: `ENTRY ${ep.toFixed(2)}`,
        });

        // Stop loss line
        if (stopLoss) {
          const sl = parseFloat(stopLoss);
          series.createPriceLine({
            price: sl,
            color: "#ef5350",
            lineWidth: 2,
            lineStyle: LineStyle.Dashed,
            axisLabelVisible: true,
            title: `SL ${sl.toFixed(2)}`,
          });
        }

        // Target line
        if (target) {
          const tgt = parseFloat(target);
          series.createPriceLine({
            price: tgt,
            color: "#26a69a",
            lineWidth: 2,
            lineStyle: LineStyle.Dashed,
            axisLabelVisible: true,
            title: `TGT ${tgt.toFixed(2)}`,
          });
        }

        // Entry price line
        series.createPriceLine({
          price: ep,
          color: "#2196F3",
          lineWidth: 1,
          lineStyle: LineStyle.Dotted,
          axisLabelVisible: true,
          title: `ENTRY ${ep.toFixed(2)}`,
        });
      }

      // Exit marker
      if (exitTime && exitPrice) {
        const exitTs = new Date(exitTime).getTime() / 1000;
        const xp = parseFloat(exitPrice);
        const isWin = direction === "LONG" ? xp > parseFloat(entryPrice || "0") : xp < parseFloat(entryPrice || "0");
        markers.push({
          time: exitTs,
          position: direction === "LONG" ? "aboveBar" : "belowBar",
          color: isWin ? "#26a69a" : "#ef5350",
          shape: isWin ? "circle" : "square",
          text: `EXIT ${xp.toFixed(2)} ${isWin ? "WIN" : "LOSS"}`,
        });
      }

      if (markers.length > 0) {
        series.setMarkers(markers.sort((a, b) => a.time - b.time));
      }

      // Fit content
      chart.timeScale().fitContent();

      // Handle resize
      const handleResize = () => {
        if (chartRef.current) {
          chart.applyOptions({ width: chartRef.current.clientWidth });
        }
      };
      window.addEventListener("resize", handleResize);

    } catch (e: any) {
      setError(e.message || "Failed to load chart");
    } finally {
      setLoading(false);
    }
  };

  // Load on mount if params present
  useEffect(() => {
    if (symbol && entryTime) {
      loadChart();
    }
  }, []);

  // Update URL params
  const updateUrl = () => {
    const params: Record<string, string> = { symbol, interval };
    if (entryTime) params.entryTime = entryTime;
    if (entryPrice) params.entryPrice = entryPrice;
    if (stopLoss) params.stopLoss = stopLoss;
    if (target) params.target = target;
    if (exitTime) params.exitTime = exitTime;
    if (exitPrice) params.exitPrice = exitPrice;
    if (direction) params.direction = direction;
    setSearchParams(params);
  };

  const handleVisualize = () => {
    updateUrl();
    loadChart();
  };

  const inputStyle: React.CSSProperties = {
    background: "#1a1a2e",
    color: "#d1d4dc",
    border: "1px solid #333",
    borderRadius: 4,
    padding: "8px 12px",
    fontSize: 14,
    width: "100%",
  };

  const labelStyle: React.CSSProperties = {
    color: "#888",
    fontSize: 12,
    marginBottom: 4,
    display: "block",
  };

  const btnStyle: React.CSSProperties = {
    background: "#2196F3",
    color: "white",
    border: "none",
    borderRadius: 4,
    padding: "10px 24px",
    fontSize: 14,
    cursor: "pointer",
    fontWeight: "bold",
  };

  return (
    <div style={{ background: "#0a0a0a", minHeight: "100vh", padding: 20, color: "#d1d4dc" }}>
      <h2 style={{ margin: "0 0 20px 0" }}>Trade Visualizer</h2>

      {/* Input Form */}
      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(180px, 1fr))", gap: 12, marginBottom: 16 }}>
        <div>
          <label style={labelStyle}>Symbol</label>
          <input style={inputStyle} value={symbol} onChange={e => setSymbol(e.target.value.toUpperCase())} placeholder="RELIANCE" />
        </div>
        <div>
          <label style={labelStyle}>Interval</label>
          <select style={inputStyle} value={interval} onChange={e => setInterval(e.target.value)}>
            {INTERVALS.map(i => <option key={i} value={i}>{i}</option>)}
          </select>
        </div>
        <div>
          <label style={labelStyle}>Direction</label>
          <select style={inputStyle} value={direction} onChange={e => setDirection(e.target.value)}>
            <option value="LONG">LONG</option>
            <option value="SHORT">SHORT</option>
          </select>
        </div>
        <div>
          <label style={labelStyle}>Entry Time (ISO)</label>
          <input style={inputStyle} value={entryTime} onChange={e => setEntryTime(e.target.value)} placeholder="2026-03-11T06:45:00Z" />
        </div>
        <div>
          <label style={labelStyle}>Entry Price</label>
          <input style={inputStyle} type="number" step="0.01" value={entryPrice} onChange={e => setEntryPrice(e.target.value)} placeholder="1401.0" />
        </div>
        <div>
          <label style={labelStyle}>Stop Loss</label>
          <input style={inputStyle} type="number" step="0.01" value={stopLoss} onChange={e => setStopLoss(e.target.value)} placeholder="1370.0" />
        </div>
        <div>
          <label style={labelStyle}>Target</label>
          <input style={inputStyle} type="number" step="0.01" value={target} onChange={e => setTarget(e.target.value)} placeholder="1455.0" />
        </div>
        <div>
          <label style={labelStyle}>Exit Time (ISO, optional)</label>
          <input style={inputStyle} value={exitTime} onChange={e => setExitTime(e.target.value)} placeholder="2026-03-16T04:45:00Z" />
        </div>
        <div>
          <label style={labelStyle}>Exit Price (optional)</label>
          <input style={inputStyle} type="number" step="0.01" value={exitPrice} onChange={e => setExitPrice(e.target.value)} placeholder="1370.0" />
        </div>
      </div>

      <div style={{ display: "flex", gap: 12, marginBottom: 16, alignItems: "center" }}>
        <button style={btnStyle} onClick={handleVisualize} disabled={loading}>
          {loading ? "Loading..." : "Visualize"}
        </button>
        {barCount > 0 && <span style={{ color: "#666" }}>{barCount} bars loaded</span>}
        {error && <span style={{ color: "#ef5350" }}>{error}</span>}
      </div>

      {/* Signal Loader */}
      <div style={{ marginBottom: 16 }}>
        <div style={{ display: "flex", gap: 12, alignItems: "center", marginBottom: 8 }}>
          <button
            style={{ ...btnStyle, background: "#444" }}
            onClick={loadSignals}
            disabled={loadingSignals}
          >
            {loadingSignals ? "Loading..." : signals.length > 0 ? `Reload Signals (${signals.length})` : "Load Sim Trades"}
          </button>
          {signals.length > 0 && (
            <input
              style={{ ...inputStyle, width: 300 }}
              placeholder="Filter: symbol, pattern, strategy..."
              value={signalFilter}
              onChange={e => setSignalFilter(e.target.value.toUpperCase())}
            />
          )}
        </div>

        {signals.length > 0 && (
          <div style={{
            maxHeight: 250, overflowY: "auto", background: "#111", borderRadius: 8,
            border: "1px solid #222", fontSize: 13,
          }}>
            <table style={{ width: "100%", borderCollapse: "collapse" }}>
              <thead>
                <tr style={{ borderBottom: "1px solid #333", position: "sticky", top: 0, background: "#111" }}>
                  <th style={{ padding: "6px 8px", textAlign: "left" }}>ID</th>
                  <th style={{ padding: "6px 8px", textAlign: "left" }}>Symbol</th>
                  <th style={{ padding: "6px 8px", textAlign: "left" }}>Dir</th>
                  <th style={{ padding: "6px 8px", textAlign: "left" }}>Pattern</th>
                  <th style={{ padding: "6px 8px", textAlign: "left" }}>Strategy</th>
                  <th style={{ padding: "6px 8px", textAlign: "right" }}>Entry</th>
                  <th style={{ padding: "6px 8px", textAlign: "right" }}>SL</th>
                  <th style={{ padding: "6px 8px", textAlign: "right" }}>Target</th>
                  <th style={{ padding: "6px 8px", textAlign: "left" }}>Status</th>
                  <th style={{ padding: "6px 8px", textAlign: "left" }}>Time</th>
                  <th style={{ padding: "6px 8px" }}></th>
                </tr>
              </thead>
              <tbody>
                {signals
                  .filter(s => {
                    if (!signalFilter) return true;
                    const f = signalFilter;
                    return (s.symbol || "").includes(f) 
                      || (s.patternType || "").includes(f) 
                      || (s.strategyType || "").includes(f)
                      || (s.status || "").includes(f);
                  })
                  .slice(0, 100)
                  .map(sig => (
                    <tr
                      key={sig.id}
                      style={{
                        borderBottom: "1px solid #1a1a2e",
                        cursor: "pointer",
                      }}
                      onClick={() => loadFromSignal(sig)}
                      onMouseOver={e => (e.currentTarget.style.background = "#1a1a2e")}
                      onMouseOut={e => (e.currentTarget.style.background = "transparent")}
                    >
                      <td style={{ padding: "4px 8px" }}>{sig.id}</td>
                      <td style={{ padding: "4px 8px", color: "#90caf9" }}>{sig.symbol}</td>
                      <td style={{
                        padding: "4px 8px",
                        color: sig.direction === "LONG" ? "#26a69a" : "#ef5350"
                      }}>{sig.direction}</td>
                      <td style={{ padding: "4px 8px", color: "#aaa" }}>{sig.patternType || "-"}</td>
                      <td style={{ padding: "4px 8px", color: "#666" }}>{sig.strategyType}</td>
                      <td style={{ padding: "4px 8px", textAlign: "right" }}>{sig.entryPrice?.toFixed(2)}</td>
                      <td style={{ padding: "4px 8px", textAlign: "right", color: "#ef5350" }}>{sig.stopLoss?.toFixed(2)}</td>
                      <td style={{ padding: "4px 8px", textAlign: "right", color: "#26a69a" }}>{sig.target?.toFixed(2)}</td>
                      <td style={{
                        padding: "4px 8px",
                        color: sig.status === "COMPLETED" ? "#666" : "#ffb74d"
                      }}>{sig.status}</td>
                      <td style={{ padding: "4px 8px", color: "#666" }}>
                        {sig.signalTime ? new Date(sig.signalTime).toLocaleDateString() : "-"}
                      </td>
                      <td style={{ padding: "4px 8px" }}>
                        <span style={{ color: "#2196F3" }}>View</span>
                      </td>
                    </tr>
                  ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Chart */}
      <div
        ref={chartRef}
        style={{
          width: "100%",
          height: 600,
          background: "#0a0a0a",
          borderRadius: 8,
          border: "1px solid #222",
        }}
      />

      {/* Trade summary */}
      {entryPrice && stopLoss && target && (
        <div style={{ marginTop: 16, padding: 16, background: "#111", borderRadius: 8, display: "flex", gap: 24, flexWrap: "wrap" }}>
          <div>
            <span style={{ color: "#888" }}>Risk: </span>
            <span style={{ color: "#ef5350" }}>
              {Math.abs(parseFloat(entryPrice) - parseFloat(stopLoss)).toFixed(2)}
              {" "}({(Math.abs(parseFloat(entryPrice) - parseFloat(stopLoss)) / parseFloat(entryPrice) * 100).toFixed(1)}%)
            </span>
          </div>
          <div>
            <span style={{ color: "#888" }}>Reward: </span>
            <span style={{ color: "#26a69a" }}>
              {Math.abs(parseFloat(target) - parseFloat(entryPrice)).toFixed(2)}
              {" "}({(Math.abs(parseFloat(target) - parseFloat(entryPrice)) / parseFloat(entryPrice) * 100).toFixed(1)}%)
            </span>
          </div>
          <div>
            <span style={{ color: "#888" }}>RR: </span>
            <span>
              1:{(Math.abs(parseFloat(target) - parseFloat(entryPrice)) / Math.abs(parseFloat(entryPrice) - parseFloat(stopLoss))).toFixed(1)}
            </span>
          </div>
          {exitPrice && (
            <div>
              <span style={{ color: "#888" }}>P&L: </span>
              <span style={{
                color: (direction === "LONG"
                  ? parseFloat(exitPrice) > parseFloat(entryPrice)
                  : parseFloat(exitPrice) < parseFloat(entryPrice))
                  ? "#26a69a" : "#ef5350"
              }}>
                {direction === "LONG"
                  ? (parseFloat(exitPrice) - parseFloat(entryPrice)).toFixed(2)
                  : (parseFloat(entryPrice) - parseFloat(exitPrice)).toFixed(2)}
                {" "}({direction === "LONG"
                  ? ((parseFloat(exitPrice) / parseFloat(entryPrice) - 1) * 100).toFixed(1)
                  : ((1 - parseFloat(exitPrice) / parseFloat(entryPrice)) * 100).toFixed(1)}%)
              </span>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
