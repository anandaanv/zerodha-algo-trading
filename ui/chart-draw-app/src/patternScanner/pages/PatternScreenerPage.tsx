import React, { useEffect, useState } from "react";
import { getNifty50, scanSymbol, PatternScanResultDto, PatternDto } from "../api";
import { fetchIntervalMapping } from "../../legacy-chart/proApi";
import PatternChartPanel from "../PatternChartPanel";

export default function PatternScreenerPage() {
  const [nifty50, setNifty50] = useState<string[]>([]);
  const [scanResults, setScanResults] = useState<Record<string, PatternScanResultDto>>({});
  const [selectedSymbol, setSelectedSymbol] = useState<string | null>(null);
  const [scanning, setScanning] = useState(false);
  const [scanProgress, setScanProgress] = useState(0);
  const [watchingTf, setWatchingTf] = useState("1h");
  const [confirmTf, setConfirmTf] = useState("15m");
  const [activeChartTab, setActiveChartTab] = useState<"watching" | "confirm">("watching");
  const [mapping, setMapping] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [selectedPattern, setSelectedPattern] = useState<PatternDto | null>(null);

  const tfOptions = ["1d", "1h", "15m", "5m"];

  useEffect(() => {
    const init = async () => {
      try {
        const symbols = await getNifty50();
        setNifty50(symbols);
        const intervalMapping = await fetchIntervalMapping();
        setMapping(intervalMapping);
      } catch (err) {
        setError(err instanceof Error ? err.message : "Failed to initialize");
      }
    };
    init();
  }, []);

  const scanSingleSymbol = async (symbol: string) => {
    try {
      const result = await scanSymbol(symbol, watchingTf, confirmTf);
      setScanResults((prev) => ({
        ...prev,
        [symbol]: result,
      }));
      return result;
    } catch (err) {
      console.error(`Failed to scan ${symbol}:`, err);
      return null;
    }
  };

  const handleScanAll = async () => {
    setScanning(true);
    setScanProgress(0);
    try {
      const results: Record<string, PatternScanResultDto> = {};
      for (let i = 0; i < nifty50.length; i++) {
        const symbol = nifty50[i];
        const result = await scanSingleSymbol(symbol);
        if (result) {
          results[symbol] = result;
        }
        setScanProgress(i + 1);
      }
      setScanResults(results);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Scan failed");
    } finally {
      setScanning(false);
    }
  };

  const handleSymbolClick = async (symbol: string) => {
    setSelectedPattern(null);
    setSelectedSymbol(symbol);
    if (!scanResults[symbol]) {
      await scanSingleSymbol(symbol);
    }
  };

  const selectedResult = selectedSymbol ? scanResults[selectedSymbol] : null;
  const patterns = selectedResult?.patterns || [];

  const getBullishDots = (symbol: string) => {
    const result = scanResults[symbol];
    if (!result) return null;
    const bullishCount = result.patterns.filter((p) => p.bullish).length;
    const bearishCount = result.patterns.filter((p) => !p.bullish).length;
    return { bullish: bullishCount, bearish: bearishCount };
  };

  return (
    <div
      style={{
        display: "flex",
        height: "100vh",
        backgroundColor: "#1a1a1a",
        color: "#fff",
        fontFamily: "system-ui, -apple-system, sans-serif",
      }}
    >
      {/* Left Panel */}
      <div
        style={{
          width: "260px",
          borderRight: "1px solid #333",
          display: "flex",
          flexDirection: "column",
          overflow: "hidden",
        }}
      >
        {/* TF Selectors */}
        <div style={{ padding: "16px", borderBottom: "1px solid #333" }}>
          <div style={{ marginBottom: "12px" }}>
            <label
              style={{
                display: "block",
                fontSize: "12px",
                marginBottom: "4px",
                color: "#90caf9",
              }}
            >
              Watching TF
            </label>
            <select
              value={watchingTf}
              onChange={(e) => setWatchingTf(e.target.value)}
              disabled={scanning}
              style={{
                width: "100%",
                padding: "6px",
                backgroundColor: "#2a2a2a",
                color: "#fff",
                border: "1px solid #333",
                borderRadius: "4px",
                cursor: scanning ? "not-allowed" : "pointer",
              }}
            >
              {tfOptions.map((tf) => (
                <option key={tf} value={tf}>
                  {tf}
                </option>
              ))}
            </select>
          </div>
          <div style={{ marginBottom: "12px" }}>
            <label
              style={{
                display: "block",
                fontSize: "12px",
                marginBottom: "4px",
                color: "#90caf9",
              }}
            >
              Confirm TF
            </label>
            <select
              value={confirmTf}
              onChange={(e) => setConfirmTf(e.target.value)}
              disabled={scanning}
              style={{
                width: "100%",
                padding: "6px",
                backgroundColor: "#2a2a2a",
                color: "#fff",
                border: "1px solid #333",
                borderRadius: "4px",
                cursor: scanning ? "not-allowed" : "pointer",
              }}
            >
              {tfOptions.map((tf) => (
                <option key={tf} value={tf}>
                  {tf}
                </option>
              ))}
            </select>
          </div>
        </div>

        {/* Scan All Button */}
        <div style={{ padding: "12px", borderBottom: "1px solid #333" }}>
          <button
            onClick={handleScanAll}
            disabled={scanning}
            style={{
              width: "100%",
              padding: "8px 12px",
              backgroundColor: scanning ? "#555" : "#90caf9",
              color: scanning ? "#999" : "#000",
              border: "none",
              borderRadius: "4px",
              fontSize: "13px",
              fontWeight: "600",
              cursor: scanning ? "not-allowed" : "pointer",
            }}
          >
            {scanning ? `Scanning ${scanProgress}/50...` : "Scan All"}
          </button>
        </div>

        {/* Symbol List */}
        <div
          style={{
            flex: 1,
            overflowY: "auto",
            padding: "8px",
          }}
        >
          {nifty50.map((symbol) => {
            const isSelected = selectedSymbol === symbol;
            const result = scanResults[symbol];
            const dots = getBullishDots(symbol);
            const patternCount = result?.patterns.length || 0;

            return (
              <div
                key={symbol}
                onClick={() => handleSymbolClick(symbol)}
                style={{
                  padding: "10px",
                  marginBottom: "4px",
                  backgroundColor: isSelected ? "#2a2a2a" : "transparent",
                  border: isSelected ? "1px solid #90caf9" : "1px solid transparent",
                  borderRadius: "4px",
                  cursor: "pointer",
                  display: "flex",
                  justifyContent: "space-between",
                  alignItems: "center",
                  fontSize: "13px",
                  transition: "all 0.2s",
                }}
                onMouseEnter={(e) => {
                  if (!isSelected) {
                    (e.currentTarget as HTMLDivElement).style.backgroundColor = "#222";
                  }
                }}
                onMouseLeave={(e) => {
                  if (!isSelected) {
                    (e.currentTarget as HTMLDivElement).style.backgroundColor =
                      "transparent";
                  }
                }}
              >
                <span>{symbol}</span>
                <div style={{ display: "flex", gap: "8px", alignItems: "center" }}>
                  {dots && (
                    <div style={{ display: "flex", gap: "4px" }}>
                      {dots.bullish > 0 && (
                        <span style={{ color: "#4caf50", fontSize: "12px" }}>
                          ● {dots.bullish}
                        </span>
                      )}
                      {dots.bearish > 0 && (
                        <span style={{ color: "#f44336", fontSize: "12px" }}>
                          ● {dots.bearish}
                        </span>
                      )}
                    </div>
                  )}
                  {scanning && !result && <span style={{ color: "#999" }}>...</span>}
                  {result && (
                    <span style={{ color: "#90caf9", fontSize: "12px" }}>
                      {patternCount}
                    </span>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      </div>

      {/* Right Panel */}
      <div
        style={{
          flex: 1,
          display: "flex",
          flexDirection: "column",
          overflow: "hidden",
        }}
      >
        {error && (
          <div
            style={{
              padding: "12px 16px",
              backgroundColor: "#c62828",
              color: "#fff",
              fontSize: "13px",
              borderBottom: "1px solid #333",
            }}
          >
            {error}
          </div>
        )}

        {selectedResult ? (
          <>
            {/* Chart Tabs */}
            <div
              style={{
                display: "flex",
                borderBottom: "1px solid #333",
                paddingLeft: "16px",
              }}
            >
              <button
                onClick={() => setActiveChartTab("watching")}
                style={{
                  padding: "12px 16px",
                  backgroundColor:
                    activeChartTab === "watching" ? "#2a2a2a" : "transparent",
                  color: activeChartTab === "watching" ? "#90caf9" : "#999",
                  border: "none",
                  borderBottom:
                    activeChartTab === "watching"
                      ? "2px solid #90caf9"
                      : "2px solid transparent",
                  cursor: "pointer",
                  fontSize: "13px",
                  fontWeight: "600",
                }}
              >
                {watchingTf} (Watching)
              </button>
              <button
                onClick={() => setActiveChartTab("confirm")}
                style={{
                  padding: "12px 16px",
                  backgroundColor:
                    activeChartTab === "confirm" ? "#2a2a2a" : "transparent",
                  color: activeChartTab === "confirm" ? "#90caf9" : "#999",
                  border: "none",
                  borderBottom:
                    activeChartTab === "confirm"
                      ? "2px solid #90caf9"
                      : "2px solid transparent",
                  cursor: "pointer",
                  fontSize: "13px",
                  fontWeight: "600",
                }}
              >
                {confirmTf} (Confirm)
              </button>
            </div>

            {/* Chart Area */}
            <div
              style={{
                flex: "0 0 420px",
                borderBottom: "1px solid #333",
                overflow: "hidden",
              }}
            >
              {mapping && Object.keys(mapping).length > 0 ? (
                <PatternChartPanel
                  symbol={selectedResult.symbol}
                  timeframe={activeChartTab === "watching" ? watchingTf : confirmTf}
                  mapping={mapping}
                  selectedPattern={selectedPattern}
                />
              ) : (
                <div
                  style={{
                    height: "100%",
                    display: "flex",
                    alignItems: "center",
                    justifyContent: "center",
                    color: "#999",
                  }}
                >
                  Loading chart...
                </div>
              )}
            </div>

            {/* Pattern List */}
            <div
              style={{
                flex: 1,
                overflowY: "auto",
                padding: "16px",
              }}
            >
              <h3 style={{ marginTop: 0, color: "#90caf9", fontSize: "14px" }}>
                Patterns ({patterns.length})
              </h3>
              {patterns.length > 0 ? (
                <table
                  style={{
                    width: "100%",
                    borderCollapse: "collapse",
                    fontSize: "12px",
                  }}
                >
                  <thead>
                    <tr
                      style={{
                        borderBottom: "1px solid #333",
                        color: "#90caf9",
                      }}
                    >
                      <th style={{ padding: "8px", textAlign: "left", fontWeight: "600" }}>Type</th>
                      <th style={{ padding: "8px", textAlign: "left", fontWeight: "600" }}>Dir</th>
                      <th style={{ padding: "8px", textAlign: "right", fontWeight: "600" }}>Entry</th>
                      <th style={{ padding: "8px", textAlign: "right", fontWeight: "600" }}>Target</th>
                      <th style={{ padding: "8px", textAlign: "right", fontWeight: "600" }}>SL</th>
                      <th style={{ padding: "8px", textAlign: "right", fontWeight: "600" }}>ATR</th>
                      <th style={{ padding: "8px", textAlign: "right", fontWeight: "600" }}>RSI@P1</th>
                      <th style={{ padding: "8px", textAlign: "right", fontWeight: "600" }}>RSI@P2</th>
                    </tr>
                  </thead>
                  <tbody>
                    {patterns.map((pattern, idx) => (
                      <tr
                        key={idx}
                        onClick={() => setSelectedPattern(pattern)}
                        style={{
                          borderBottom: "1px solid #333",
                          color: pattern.bullish ? "#4caf50" : "#f44336",
                          backgroundColor:
                            selectedPattern === pattern ? "#1e3a5f" : "transparent",
                          borderLeft:
                            selectedPattern === pattern
                              ? "3px solid #90caf9"
                              : "3px solid transparent",
                          cursor: "pointer",
                          transition: "background-color 0.15s",
                        }}
                      >
                        <td style={{ padding: "8px" }}>{pattern.patternType}</td>
                        <td style={{ padding: "8px" }}>
                          {pattern.bullish ? "LONG" : "SHORT"}
                        </td>
                        <td style={{ padding: "8px", textAlign: "right" }}>
                          {pattern.keyLevel.toFixed(2)}
                        </td>
                        <td style={{ padding: "8px", textAlign: "right" }}>
                          {pattern.target.toFixed(2)}
                        </td>
                        <td style={{ padding: "8px", textAlign: "right", color: "#ef5350" }}>
                          {(pattern.bullish
                            ? pattern.keyLevel - 2 * pattern.atr
                            : pattern.keyLevel + 2 * pattern.atr
                          ).toFixed(2)}
                        </td>
                        <td style={{ padding: "8px", textAlign: "right", color: "#888" }}>
                          {pattern.atr.toFixed(2)}
                        </td>
                        <td style={{ padding: "8px", textAlign: "right" }}>
                          {pattern.rsiAtP1.toFixed(1)}
                        </td>
                        <td style={{ padding: "8px", textAlign: "right" }}>
                          {pattern.rsiAtP2.toFixed(1)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              ) : (
                <div style={{ color: "#999", fontSize: "13px" }}>
                  No patterns found.
                </div>
              )}
            </div>
          </>
        ) : (
          <div
            style={{
              flex: 1,
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              color: "#999",
            }}
          >
            {nifty50.length > 0
              ? "Select a symbol to view patterns"
              : "Loading symbols..."}
          </div>
        )}
      </div>
    </div>
  );
}
