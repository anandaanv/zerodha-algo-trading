import React, { useState, useEffect, useRef } from "react";
import {
  createChart,
  type IChartApi,
  type ISeriesApi,
  type SeriesMarker,
  type Time,
} from "lightweight-charts";
import { getApiUrl } from "../../config/api";
import { withAuth } from "../../utils/apiHelper";

interface RunSummary {
  run_id: string;
  stocks_count: number;
  total_trades: number;
  wins: number;
  losses: number;
  total_pnl_pct: number;
  win_rate_pct: number;
  created_at: string;
}

interface IndexData {
  runs: RunSummary[];
}

interface PivotData {
  label: string;
  bar_index: number;
  timestamp: string;
  price: number;
  type: "HIGH" | "LOW";
}

interface BarData {
  bar_index: number;
  timestamp: string;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
}

interface Trade {
  symbol: string;
  pattern_type: "HNS" | "REV_HNS";
  direction: "LONG" | "SHORT";
  entry_bar: number;
  entry_time: string;
  entry_price: number;
  stop_initial: number;
  target_initial: number;
  exit_bar: number;
  exit_time: string;
  exit_price: number;
  exit_reason: "TARGET" | "STOP" | "TIMEOUT";
  pnl_pct: number;
  was_winner: boolean;
  holding_bars: number;
  pattern_pivots: PivotData[];
  bars_around: BarData[];
}

interface RunData {
  run_id: string;
  strategy_name: string;
  timeframe: string;
  stocks_count: number;
  total_trades: number;
  wins: number;
  losses: number;
  total_pnl_pct: number;
  trades: Trade[];
}

type FilterType = "all" | "winners" | "losers" | "stop" | "target" | "timeout";
type SortType = "pnl" | "chronological";

export default function SimulationViewer() {
  const [runs, setRuns] = useState<RunSummary[]>([]);
  const [selectedRun, setSelectedRun] = useState<RunData | null>(null);
  const [filteredTrades, setFilteredTrades] = useState<Trade[]>([]);
  const [selectedTrade, setSelectedTrade] = useState<Trade | null>(null);

  const [filterType, setFilterType] = useState<FilterType>("all");
  const [selectedSymbol, setSelectedSymbol] = useState<string>("");
  const [sortType, setSortType] = useState<SortType>("pnl");

  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const candleSeriesRef = useRef<ISeriesApi<"Candlestick"> | null>(null);

  // Fetch runs index on mount
  useEffect(() => {
    const fetchIndex = async () => {
      try {
        const res = await fetch(
          getApiUrl("/api/simulation-results").toString(),
          withAuth()
        );
        if (res.ok) {
          const data: IndexData = await res.json();
          setRuns(data.runs || []);
        }
      } catch (e) {
        console.error("Failed to fetch simulation runs:", e);
      }
    };

    fetchIndex();
  }, []);

  // Fetch selected run data
  const handleSelectRun = async (run: RunSummary) => {
    try {
      const res = await fetch(
        getApiUrl(`/api/simulation-results/${run.run_id}`).toString(),
        withAuth()
      );
      if (res.ok) {
        const data: RunData = await res.json();
        setSelectedRun(data);
        setSelectedTrade(null);
        applyFiltersAndSort(data.trades);
      }
    } catch (e) {
      console.error("Failed to fetch run data:", e);
    }
  };

  // Apply filters and sorting
  const applyFiltersAndSort = (trades: Trade[]) => {
    let filtered = trades;

    // Apply exit reason filter
    if (filterType !== "all") {
      filtered = filtered.filter((t) => {
        if (filterType === "winners") return t.was_winner;
        if (filterType === "losers") return !t.was_winner;
        if (filterType === "stop") return t.exit_reason === "STOP";
        if (filterType === "target") return t.exit_reason === "TARGET";
        if (filterType === "timeout") return t.exit_reason === "TIMEOUT";
        return true;
      });
    }

    // Apply symbol filter
    if (selectedSymbol) {
      filtered = filtered.filter((t) => t.symbol === selectedSymbol);
    }

    // Apply sorting
    if (sortType === "pnl") {
      filtered.sort((a, b) => b.pnl_pct - a.pnl_pct);
    } else {
      filtered.sort(
        (a, b) =>
          new Date(a.entry_time).getTime() -
          new Date(b.entry_time).getTime()
      );
    }

    setFilteredTrades(filtered);
  };

  useEffect(() => {
    if (selectedRun) {
      applyFiltersAndSort(selectedRun.trades);
    }
  }, [filterType, selectedSymbol, sortType, selectedRun]);

  // Render chart when trade is selected
  useEffect(() => {
    if (!selectedTrade || !containerRef.current) return;

    if (chartRef.current) {
      chartRef.current.remove();
    }

    const chart = createChart(containerRef.current, {
      layout: { background: { color: "#fff" } },
      width: containerRef.current.clientWidth,
      height: 500,
      timeScale: { timeVisible: true, secondsVisible: false },
    });

    const candleSeries = chart.addCandlestickSeries({
      upColor: "#26a69a",
      downColor: "#ef5350",
      borderUpColor: "#26a69a",
      borderDownColor: "#ef5350",
      wickUpColor: "#26a69a",
      wickDownColor: "#ef5350",
    });

    // Convert bars to chart format
    const candleData = selectedTrade.bars_around.map((bar) => ({
      time: Math.floor(new Date(bar.timestamp).getTime() / 1000) as Time,
      open: bar.open,
      high: bar.high,
      low: bar.low,
      close: bar.close,
    }));

    candleSeries.setData(candleData);
    candleSeriesRef.current = candleSeries;

    // Add markers
    const markers: SeriesMarker<Time>[] = [];

    // Add pattern pivots
    selectedTrade.pattern_pivots.forEach((pivot) => {
      const time = Math.floor(
        new Date(pivot.timestamp).getTime() / 1000
      ) as Time;
      markers.push({
        time,
        position: pivot.type === "HIGH" ? "aboveBar" : "belowBar",
        color: pivot.type === "HIGH" ? "#ff6b6b" : "#4ecdc4",
        shape: "circle",
        text: pivot.label,
      });
    });

    // Add entry marker
    const entryTime = Math.floor(
      new Date(selectedTrade.entry_time).getTime() / 1000
    ) as Time;
    markers.push({
      time: entryTime,
      position: selectedTrade.direction === "LONG" ? "belowBar" : "aboveBar",
      color: "#0084ff",
      shape: "arrowUp",
      text: "Entry",
    });

    // Add exit marker
    const exitTime = Math.floor(
      new Date(selectedTrade.exit_time).getTime() / 1000
    ) as Time;
    const exitColor =
      selectedTrade.exit_reason === "TARGET"
        ? "#52c41a"
        : selectedTrade.exit_reason === "STOP"
          ? "#ff4d4f"
          : "#9ca3af";
    markers.push({
      time: exitTime,
      position: "inBar",
      color: exitColor,
      shape: "circle",
      text: selectedTrade.exit_reason,
    });

    candleSeries.setMarkers(markers);

    // Add price lines for stop and target
    candleSeries.createPriceLine({
      price: selectedTrade.stop_initial,
      color: "#ff4d4f",
      lineWidth: 2,
      lineStyle: 2,
      title: "Stop",
    });

    candleSeries.createPriceLine({
      price: selectedTrade.target_initial,
      color: "#52c41a",
      lineWidth: 2,
      lineStyle: 2,
      title: "Target",
    });

    chart.timeScale().fitContent();
    chartRef.current = chart;

    const handleResize = () => {
      if (containerRef.current && chartRef.current) {
        chartRef.current.applyOptions({
          width: containerRef.current.clientWidth,
        });
      }
    };

    window.addEventListener("resize", handleResize);

    return () => {
      window.removeEventListener("resize", handleResize);
    };
  }, [selectedTrade]);

  const getUniqueSymbols = (): string[] => {
    if (!selectedRun) return [];
    return Array.from(new Set(selectedRun.trades.map((t) => t.symbol))).sort();
  };

  return (
    <div style={{ display: "flex", height: "100vh", background: "#f5f5f5" }}>
      {/* Left Panel - Runs & Trades List */}
      <div
        style={{
          width: "25%",
          borderRight: "1px solid #ddd",
          overflowY: "auto",
          background: "#fff",
          padding: "1rem",
        }}
      >
        <h2 style={{ marginTop: 0 }}>Simulation Runs</h2>
        {runs.length === 0 ? (
          <p style={{ color: "#999" }}>No simulation runs available</p>
        ) : (
          <div>
            {runs.map((run) => (
              <div
                key={run.run_id}
                onClick={() => handleSelectRun(run)}
                style={{
                  padding: "0.75rem",
                  margin: "0.5rem 0",
                  background:
                    selectedRun?.run_id === run.run_id ? "#e3f2fd" : "#f9f9f9",
                  border: "1px solid #ddd",
                  borderRadius: "4px",
                  cursor: "pointer",
                  fontSize: "0.85rem",
                }}
              >
                <div style={{ fontWeight: 600 }}>{run.run_id}</div>
                <div>Trades: {run.total_trades}</div>
                <div style={{ color: run.total_pnl_pct > 0 ? "#52c41a" : "#ff4d4f" }}>
                  P/L: {run.total_pnl_pct.toFixed(2)}%
                </div>
                <div>Win Rate: {run.win_rate_pct.toFixed(1)}%</div>
              </div>
            ))}
          </div>
        )}

        {selectedRun && (
          <div style={{ marginTop: "2rem" }}>
            <h3>Filters</h3>
            <div style={{ marginBottom: "1rem" }}>
              <label style={{ display: "block", marginBottom: "0.5rem" }}>
                Exit Reason:
              </label>
              <select
                value={filterType}
                onChange={(e) => setFilterType(e.target.value as FilterType)}
                style={{
                  width: "100%",
                  padding: "0.5rem",
                  borderRadius: "4px",
                  border: "1px solid #ddd",
                }}
              >
                <option value="all">All</option>
                <option value="winners">Winners</option>
                <option value="losers">Losers</option>
                <option value="target">Target Hit</option>
                <option value="stop">Stop Hit</option>
                <option value="timeout">Timeout</option>
              </select>
            </div>

            <div style={{ marginBottom: "1rem" }}>
              <label style={{ display: "block", marginBottom: "0.5rem" }}>
                Symbol:
              </label>
              <select
                value={selectedSymbol}
                onChange={(e) => setSelectedSymbol(e.target.value)}
                style={{
                  width: "100%",
                  padding: "0.5rem",
                  borderRadius: "4px",
                  border: "1px solid #ddd",
                }}
              >
                <option value="">All Symbols</option>
                {getUniqueSymbols().map((sym) => (
                  <option key={sym} value={sym}>
                    {sym}
                  </option>
                ))}
              </select>
            </div>

            <div style={{ marginBottom: "1rem" }}>
              <label style={{ display: "block", marginBottom: "0.5rem" }}>
                Sort By:
              </label>
              <select
                value={sortType}
                onChange={(e) => setSortType(e.target.value as SortType)}
                style={{
                  width: "100%",
                  padding: "0.5rem",
                  borderRadius: "4px",
                  border: "1px solid #ddd",
                }}
              >
                <option value="pnl">P/L (Highest First)</option>
                <option value="chronological">Chronological</option>
              </select>
            </div>

            <h3>Trades ({filteredTrades.length})</h3>
            <div style={{ maxHeight: "400px", overflowY: "auto" }}>
              {filteredTrades.map((trade, idx) => (
                <div
                  key={idx}
                  onClick={() => setSelectedTrade(trade)}
                  style={{
                    padding: "0.75rem",
                    margin: "0.5rem 0",
                    background:
                      selectedTrade === trade ? "#fff3cd" : "#f9f9f9",
                    border: "1px solid #ddd",
                    borderRadius: "4px",
                    cursor: "pointer",
                    fontSize: "0.8rem",
                  }}
                >
                  <div style={{ fontWeight: 600 }}>{trade.symbol}</div>
                  <div>
                    {trade.pattern_type} {trade.direction}
                  </div>
                  <div
                    style={{
                      color: trade.pnl_pct > 0 ? "#52c41a" : "#ff4d4f",
                      fontWeight: 600,
                    }}
                  >
                    {trade.pnl_pct > 0 ? "+" : ""}
                    {trade.pnl_pct.toFixed(2)}%
                  </div>
                  <div style={{ color: "#666" }}>
                    {trade.exit_reason} ({trade.holding_bars} bars)
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>

      {/* Right Panel - Trade Detail & Chart */}
      <div style={{ width: "75%", padding: "1.5rem", overflowY: "auto" }}>
        {selectedTrade ? (
          <div>
            <h2 style={{ marginTop: 0 }}>{selectedTrade.symbol}</h2>
            <div
              style={{
                display: "grid",
                gridTemplateColumns: "1fr 1fr 1fr",
                gap: "1rem",
                marginBottom: "1.5rem",
              }}
            >
              <div style={{ background: "#f9f9f9", padding: "1rem", borderRadius: "4px" }}>
                <div style={{ fontSize: "0.85rem", color: "#666" }}>Pattern</div>
                <div style={{ fontWeight: 600, fontSize: "1.1rem" }}>
                  {selectedTrade.pattern_type}
                </div>
                <div style={{ fontSize: "0.9rem", marginTop: "0.5rem" }}>
                  {selectedTrade.direction}
                </div>
              </div>

              <div style={{ background: "#f9f9f9", padding: "1rem", borderRadius: "4px" }}>
                <div style={{ fontSize: "0.85rem", color: "#666" }}>Entry</div>
                <div style={{ fontWeight: 600, fontSize: "1.1rem" }}>
                  {selectedTrade.entry_price.toFixed(2)}
                </div>
                <div style={{ fontSize: "0.85rem", marginTop: "0.5rem", color: "#999" }}>
                  {new Date(selectedTrade.entry_time).toLocaleString()}
                </div>
              </div>

              <div style={{ background: "#f9f9f9", padding: "1rem", borderRadius: "4px" }}>
                <div style={{ fontSize: "0.85rem", color: "#666" }}>Exit</div>
                <div style={{ fontWeight: 600, fontSize: "1.1rem" }}>
                  {selectedTrade.exit_price.toFixed(2)}
                </div>
                <div style={{ fontSize: "0.85rem", marginTop: "0.5rem", color: "#999" }}>
                  {new Date(selectedTrade.exit_time).toLocaleString()}
                </div>
              </div>
            </div>

            <div
              style={{
                display: "grid",
                gridTemplateColumns: "1fr 1fr 1fr",
                gap: "1rem",
                marginBottom: "1.5rem",
              }}
            >
              <div style={{ background: "#f0f5ff", padding: "1rem", borderRadius: "4px" }}>
                <div style={{ fontSize: "0.85rem", color: "#0050b3" }}>Stop Level</div>
                <div style={{ fontWeight: 600, fontSize: "1rem" }}>
                  {selectedTrade.stop_initial.toFixed(2)}
                </div>
              </div>

              <div style={{ background: "#f6ffed", padding: "1rem", borderRadius: "4px" }}>
                <div style={{ fontSize: "0.85rem", color: "#274000" }}>Target Level</div>
                <div style={{ fontWeight: 600, fontSize: "1rem" }}>
                  {selectedTrade.target_initial.toFixed(2)}
                </div>
              </div>

              <div
                style={{
                  background:
                    selectedTrade.pnl_pct > 0 ? "#f6ffed" : "#fff1f0",
                  padding: "1rem",
                  borderRadius: "4px",
                }}
              >
                <div
                  style={{
                    fontSize: "0.85rem",
                    color: selectedTrade.pnl_pct > 0 ? "#274000" : "#5c0a0a",
                  }}
                >
                  P/L
                </div>
                <div
                  style={{
                    fontWeight: 600,
                    fontSize: "1.2rem",
                    color: selectedTrade.pnl_pct > 0 ? "#52c41a" : "#ff4d4f",
                  }}
                >
                  {selectedTrade.pnl_pct > 0 ? "+" : ""}
                  {selectedTrade.pnl_pct.toFixed(2)}%
                </div>
                <div style={{ fontSize: "0.85rem", marginTop: "0.5rem", color: "#666" }}>
                  Exit: {selectedTrade.exit_reason} ({selectedTrade.holding_bars} bars)
                </div>
              </div>
            </div>

            <div style={{ background: "#fff", padding: "1rem", borderRadius: "4px" }}>
              <div ref={containerRef} style={{ width: "100%", height: "500px" }} />
            </div>
          </div>
        ) : (
          <div
            style={{
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              height: "100%",
              color: "#999",
            }}
          >
            Select a run and trade to view details and chart
          </div>
        )}
      </div>
    </div>
  );
}
