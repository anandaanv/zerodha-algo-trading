import React, { useMemo } from "react";
import type { OhlcBar, Timeframe } from "../types";

type Props = {
  symbol: string;
  timeframe: Timeframe;
  bars: OhlcBar[];
};

export default function Legend({ symbol, timeframe, bars }: Props) {
  const last = bars.length > 0 ? bars[bars.length - 1] : undefined;
  const prev = bars.length > 1 ? bars[bars.length - 2] : undefined;
  const closeColor = useMemo(() => {
    if (!last || !prev) return "#333";
    return last.close >= prev.close ? "#26a69a" : "#ef5350";
  }, [last, prev]);

  return (
    <div className="legend">
      <div className="legend-title">
        <span className="legend-symbol">{symbol}</span>
        <span className="legend-sep">•</span>
        <span className="legend-tf">{timeframe}</span>
      </div>
      {last && (
        <div className="legend-ohlc">
          <span>O {last.open.toFixed(2)}</span>
          <span>H {last.high.toFixed(2)}</span>
          <span>L {last.low.toFixed(2)}</span>
          <span style={{ color: closeColor }}>C {last.close.toFixed(2)}</span>
          <span>V {formatVol(last.volume)}</span>
        </div>
      )}
    </div>
  );
}

function formatVol(v: number) {
  if (v >= 1_000_000) return (v / 1_000_000).toFixed(2) + "M";
  if (v >= 1_000) return (v / 1_000).toFixed(2) + "K";
  return String(v);
}
