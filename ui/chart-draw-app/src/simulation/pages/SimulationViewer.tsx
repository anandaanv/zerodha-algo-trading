import React, { useState, useEffect, useRef, useMemo } from "react";
import { getApiUrl } from "../../config/api";
import { withAuth } from "../../utils/apiHelper";
import TVChartContainer from "../../tradingview/TVChartContainer";
// Daily MACD HTF approach: now using native MACD with 6× periods (72/156/54) instead of
// the custom-indicator route. Keeping the imports commented for reference.
// import { createDailyMacdIndicator, DailyMacdMap } from "../../tradingview/dailyMacdIndicator";
// import { fetchOHLC } from "../../tradingview/tvApi";
// import { macd } from "../indicators";

interface RunSummary {
  run_id: string;
  strategy_name: string;
  timeframe: string;
  stocks_count: number;
  total_trades: number;
  wins: number;
  losses: number;
  total_pnl_pct: number;
  win_rate_pct?: number;
  created_at: string;
}

interface IndexData {
  source: string;
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

interface TriggerMeta {
  trigger_macd_cross_date_daily?: string;
  trigger_stochrsi_sat_time_hourly?: string;
  hourly_bars_from_trigger_to_candle?: number;
  hourly_bars_from_candle_to_entry?: number;
}

interface Trade {
  id?: string;
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
  trigger_meta?: TriggerMeta;
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
}

interface TradesPage {
  content: Trade[];
  number: number;
  size: number;
  totalPages: number;
  source: string;
}

type FilterType = "all" | "winners" | "losers" | "stop" | "target" | "timeout";
type SortType = "pnl" | "chronological";

export default function SimulationViewer() {
  const [runs, setRuns] = useState<RunSummary[]>([]);
  const [selectedRun, setSelectedRun] = useState<RunData | null>(null);
  const [filteredTrades, setFilteredTrades] = useState<Trade[]>([]);
  const [selectedTrade, setSelectedTrade] = useState<Trade | null>(null);
  const [selectedTradeLoading, setSelectedTradeLoading] = useState(false);

  const [filterType, setFilterType] = useState<FilterType>("all");
  const [selectedSymbol, setSelectedSymbol] = useState<string>("");
  const [sortType, setSortType] = useState<SortType>("pnl");

  // Pagination state
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [pageSize] = useState(100);
  const [tradesLoading, setTradesLoading] = useState(false);

  // (Daily MACD HTF state removed — using native MACD(84,182,63) on hourly chart)


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

  // Fetch selected run metadata
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
        setPage(0);
        setTotalPages(0);
        setFilteredTrades([]);
        // Fetch first page of trades
        fetchTradesPage(run.run_id, 0);
      }
    } catch (e) {
      console.error("Failed to fetch run metadata:", e);
    }
  };

  // Fetch paginated trades for the selected run
  const fetchTradesPage = async (runId: string, pageNum: number) => {
    setTradesLoading(true);
    try {
      const res = await fetch(
        getApiUrl(`/api/simulation-results/${runId}/trades?page=${pageNum}&size=${pageSize}`).toString(),
        withAuth()
      );
      if (res.ok) {
        const data: TradesPage = await res.json();
        setFilteredTrades(data.content);
        setTotalPages(data.totalPages);
        setPage(pageNum);
      }
    } catch (e) {
      console.error("Failed to fetch trades page:", e);
    } finally {
      setTradesLoading(false);
    }
  };

  // Fetch full trade with bars_around when clicked
  const handleSelectTrade = async (trade: Trade) => {
    if (!selectedRun) return;
    setSelectedTradeLoading(true);
    try {
      const res = await fetch(
        getApiUrl(`/api/simulation-results/${selectedRun.run_id}/trades/${trade.id}`).toString(),
        withAuth()
      );
      if (res.ok) {
        const fullTrade: Trade = await res.json();
        setSelectedTrade(fullTrade);
      }
    } catch (e) {
      console.error("Failed to fetch trade details:", e);
    } finally {
      setSelectedTradeLoading(false);
    }
  };

  // Handle pagination changes
  const handlePageChange = (newPage: number) => {
    if (selectedRun && newPage >= 0 && newPage < totalPages) {
      fetchTradesPage(selectedRun.run_id, newPage);
    }
  };

  // Daily-equivalent MACD now uses native MACD(72, 156, 54) on hourly chart
  // (= 12/26/9 × 6 hourly bars per trading day). No daily fetch / custom indicator needed.
  const customIndicators = useMemo(() => [], []);

  // Compute chart props when trade is selected
  const getChartProps = () => {
    if (!selectedTrade) {
      return { tradeMarkers: undefined, visibleRange: undefined, autoStudies: [] };
    }

    const entrySec = Math.floor(new Date(selectedTrade.entry_time).getTime() / 1000);
    const exitSec = Math.floor(new Date(selectedTrade.exit_time).getTime() / 1000);

    const tradeMarkers = {
      entryTime: entrySec,
      entryPrice: selectedTrade.entry_price,
      direction: selectedTrade.direction,
      stopPrice: selectedTrade.stop_initial,
      targetPrice: selectedTrade.target_initial,
      exitTime: exitSec,
      exitProfitable: selectedTrade.pnl_pct > 0,
    };

    // Place entry at ~25% from left with 100 prior hourly bars visible (~14 trading days).
    // Window total ≈ 56 calendar days so post-trade context is also generous.
    // TradingView's datafeed lazy-loads beyond this range as the user scrolls.
    const DAY_SEC = 24 * 60 * 60;
    const visibleRange = {
      from: entrySec - 14 * DAY_SEC,
      to: entrySec + 42 * DAY_SEC,
    };

    // Use default inputs (just length where needed). Passing wrong input format
    // makes TradingView silently reject the study — defaults always work.
    const autoStudies = [
      // EMA's length input is keyed as "length" (per discovery log)
      { name: "Moving Average Exponential", inputs: { length: 10 } as any },
      { name: "Moving Average Exponential", inputs: { length: 50 } as any },
      { name: "Moving Average Exponential", inputs: { length: 100 } as any },
      { name: "Bollinger Bands" },
      { name: "MACD" },
      // Daily-equivalent MACD (84/182/63). TradingView input IDs:
      //   in_0 = Fast Length, in_1 = Slow Length, in_2 = Signal Smoothing,
      //   in_3 = Source, oscillatorMAType/signalLineMAType for MA types.
      { name: "MACD", inputs: { in_0: 84, in_1: 182, in_2: 63 } as any },
      { name: "Relative Strength Index" },
      { name: "Stochastic RSI" },
    ];

    return { tradeMarkers, visibleRange, autoStudies };
  };

  const { tradeMarkers, visibleRange, autoStudies } = getChartProps();


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
                <div>Win Rate: {(run.total_trades > 0 ? (run.wins / run.total_trades) * 100 : 0).toFixed(1)}%</div>
              </div>
            ))}
          </div>
        )}

        {selectedRun && (
          <div style={{ marginTop: "2rem" }}>
            <h3>Trades (Page {page + 1} of {totalPages})</h3>

            {tradesLoading ? (
              <p style={{ color: "#999" }}>Loading trades...</p>
            ) : (
              <>
                <div style={{ maxHeight: "400px", overflowY: "auto" }}>
                  {filteredTrades.map((trade) => (
                    <div
                      key={trade.id}
                      onClick={() => handleSelectTrade(trade)}
                      style={{
                        padding: "0.75rem",
                        margin: "0.5rem 0",
                        background:
                          selectedTrade?.id === trade.id ? "#fff3cd" : "#f9f9f9",
                        border: "1px solid #ddd",
                        borderRadius: "4px",
                        cursor: selectedTradeLoading ? "wait" : "pointer",
                        fontSize: "0.8rem",
                        opacity: selectedTradeLoading && selectedTrade?.id === trade.id ? 0.6 : 1,
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

                {/* Pagination controls */}
                <div style={{ marginTop: "1rem", display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                  <button
                    onClick={() => handlePageChange(page - 1)}
                    disabled={page === 0}
                    style={{
                      padding: "0.5rem 1rem",
                      borderRadius: "4px",
                      border: "1px solid #ddd",
                      background: page === 0 ? "#f0f0f0" : "#fff",
                      cursor: page === 0 ? "default" : "pointer",
                      color: page === 0 ? "#999" : "#000",
                    }}
                  >
                    Prev
                  </button>
                  <span style={{ fontSize: "0.9rem", color: "#666" }}>
                    Page {page + 1} of {totalPages}
                  </span>
                  <button
                    onClick={() => handlePageChange(page + 1)}
                    disabled={page >= totalPages - 1}
                    style={{
                      padding: "0.5rem 1rem",
                      borderRadius: "4px",
                      border: "1px solid #ddd",
                      background: page >= totalPages - 1 ? "#f0f0f0" : "#fff",
                      cursor: page >= totalPages - 1 ? "default" : "pointer",
                      color: page >= totalPages - 1 ? "#999" : "#000",
                    }}
                  >
                    Next
                  </button>
                </div>
              </>
            )}
          </div>
        )}
      </div>

      {/* Right Panel - Trade Detail & Chart */}
      <div style={{ width: "75%", padding: "1.5rem", overflowY: "auto" }}>
        {selectedTradeLoading ? (
          <div
            style={{
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              height: "100%",
              color: "#999",
            }}
          >
            Loading trade details...
          </div>
        ) : selectedTrade ? (
          <div style={{ display: "flex", flexDirection: "column", height: "100%" }}>
            {/* Single-line header: symbol + summary inline so chart owns the remaining height */}
            <div
              style={{
                display: "flex",
                alignItems: "baseline",
                gap: "0.75rem",
                flexWrap: "wrap",
                padding: "0.4rem 0.6rem",
                background: "#f9f9f9",
                borderRadius: "4px",
                marginBottom: "0.4rem",
                fontSize: "0.85rem",
              }}
            >
              <span style={{ fontWeight: 700, fontSize: "1.05rem" }}>{selectedTrade.symbol}</span>
              <span style={{ color: "#666" }}>{selectedTrade.pattern_type} · {selectedTrade.direction}</span>
              <span>
                <strong>Entry</strong> {selectedTrade.entry_price.toFixed(2)}
                <span style={{ color: "#666", fontSize: "0.78rem", marginLeft: 4 }}>
                  ({new Date(selectedTrade.entry_time).toLocaleString("en-IN", {
                    year: "numeric", month: "short", day: "2-digit",
                    hour: "2-digit", minute: "2-digit", hour12: false
                  })})
                </span>
              </span>
              <span>
                <strong>Exit</strong> {selectedTrade.exit_price.toFixed(2)}
                <span style={{ color: "#666", fontSize: "0.78rem", marginLeft: 4 }}>
                  ({new Date(selectedTrade.exit_time).toLocaleString("en-IN", {
                    year: "numeric", month: "short", day: "2-digit",
                    hour: "2-digit", minute: "2-digit", hour12: false
                  })})
                </span>
              </span>
              <span style={{ color: "#e53935" }}><strong>SL</strong> {selectedTrade.stop_initial.toFixed(2)}</span>
              <span style={{ color: "#43a047" }}><strong>TP</strong> {selectedTrade.target_initial.toFixed(2)}</span>
              <span
                style={{
                  fontWeight: 700,
                  color: selectedTrade.pnl_pct > 0 ? "#43a047" : "#e53935",
                }}
              >
                {selectedTrade.pnl_pct > 0 ? "+" : ""}{selectedTrade.pnl_pct.toFixed(2)}%
              </span>
              <span style={{ color: "#666", fontSize: "0.78rem" }}>
                {selectedTrade.exit_reason} · {selectedTrade.holding_bars}b
              </span>
              {(() => {
                // Build a TradingView URL with as many time hints as possible.
                // None of these are officially documented but they're commonly attempted:
                //   `time` (unix seconds), `goto` (yyyy-mm-dd), `date` (yyyy-mm-dd).
                // If TradingView ignores them, the user can still Alt+G in the chart.
                const entryDate = new Date(selectedTrade.entry_time);
                const unixSec = Math.floor(entryDate.getTime() / 1000);
                const ymd = entryDate.toISOString().split('T')[0];
                const ymdCompact = ymd.replace(/-/g, '');
                const sym = encodeURIComponent(`NSE:${selectedTrade.symbol}`);
                const tvUrl =
                  `https://www.tradingview.com/chart/?symbol=${sym}` +
                  `&interval=60` +
                  `&time=${unixSec}` +
                  `&goto=${ymd}` +
                  `&date=${ymd}` +
                  `&fromdate=${ymdCompact}T0000` +
                  `&todate=${ymdCompact}T2359`;
                const dateLabel = entryDate.toLocaleString('en-IN', {
                  year: 'numeric', month: 'short', day: '2-digit',
                  hour: '2-digit', minute: '2-digit', hour12: false,
                });
                // TradingView's Go-to-date dialog accepts only the date (YYYY-MM-DD).
                // Timestamp is a separate field, so we copy just the date.
                const clipboardText = ymd;
                const handleClick = () => {
                  if (navigator.clipboard?.writeText) {
                    navigator.clipboard.writeText(clipboardText).catch(() => {});
                  }
                };
                return (
                  <a
                    href={tvUrl}
                    target="_blank"
                    rel="noopener noreferrer"
                    onClick={handleClick}
                    style={{
                      marginLeft: 'auto',
                      color: '#1e88e5',
                      fontSize: '0.8rem',
                      textDecoration: 'none',
                      padding: '0.15rem 0.5rem',
                      border: '1px solid #1e88e5',
                      borderRadius: '3px',
                      whiteSpace: 'nowrap',
                    }}
                    title={`Opens TradingView and copies "${clipboardText}" to clipboard. Press Alt+G in TradingView and paste. Entry: ${dateLabel}`}
                  >
                    Open in TradingView ↗ ({ymd})
                  </a>
                );
              })()}
            </div>

            <div style={{ flex: 1, background: "#fff", borderRadius: "4px", minHeight: 0 }}>
              <TVChartContainer
                symbol={selectedTrade.symbol}
                timeframe="OneHour"
                tradeMarkers={tradeMarkers}
                visibleRange={visibleRange}
                autoStudies={autoStudies}
                customIndicators={customIndicators}
              />
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
