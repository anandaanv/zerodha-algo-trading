import React, { useMemo, useRef, useState } from "react";
import type { Tool, DrawingType } from "../types";

type Props = {
  width: number;
  height: number;
  tool: Tool;
  onComplete: (type: DrawingType, payload: any) => void;
  existing: { type: DrawingType; payload: any }[];
};

export default function DrawLayer({ width, height, tool, onComplete, existing }: Props) {
  const [points, setPoints] = useState<{ x: number; y: number }[]>([]);
  const svgRef = useRef<SVGSVGElement>(null);

  function toLocal(e: React.MouseEvent) {
    const rect = (e.target as Element).getBoundingClientRect();
    return { x: e.clientX - rect.left, y: e.clientY - rect.top };
  }

  function handleClick(e: React.MouseEvent) {
    if (tool.kind === "cursor") return;
    const p = toLocal(e);
    let needed = 0;
    let type: DrawingType | null = null;

    if (tool.kind === "line") {
      needed = 2; type = "LINE";
    } else if (tool.kind === "channel") {
      needed = 4; type = "CHANNEL";
    } else if (tool.kind === "triangle") {
      needed = 3; type = "TRIANGLE";
    } else if (tool.kind === "fib-retracement") {
      needed = 2; type = "FIB_RETRACEMENT";
    } else if (tool.kind === "fib-extension") {
      needed = 3; type = "FIB_EXTENSION";
    }

    const nxt = [...points, p];
    setPoints(nxt);
    if (type && nxt.length >= needed) {
      const payload = buildPayload(type, nxt);
      onComplete(type, payload);
      setPoints([]);
    }
  }

  function buildPayload(type: DrawingType, pts: { x: number; y: number }[]) {
    switch (type) {
      case "LINE":
        return { points: pts.slice(0, 2) };
      case "CHANNEL":
        return { a: pts.slice(0, 2), b: pts.slice(2, 4) };
      case "TRIANGLE":
        return { points: pts.slice(0, 3) };
      case "FIB_RETRACEMENT":
        return { p1: pts[0], p2: pts[1] };
      case "FIB_EXTENSION":
        return { p1: pts[0], p2: pts[1], p3: pts[2] };
    }
  }

  const fibLevels = useMemo(() => [0, 0.236, 0.382, 0.5, 0.618, 0.786, 1], []);
  const extLevels = useMemo(() => [1.272, 1.618, 2.0, 2.618], []);

  function renderExisting() {
    return existing.map((d, idx) => {
      if (d.type === "LINE") {
        const pts = d.payload.points as { x: number; y: number }[];
        if (!pts || pts.length < 2) return null;
        return <line key={idx} x1={pts[0].x} y1={pts[0].y} x2={pts[1].x} y2={pts[1].y} stroke="#2e7d32" strokeWidth={2} />;
      }
      if (d.type === "CHANNEL") {
        const a = d.payload.a as { x: number; y: number }[];
        const b = d.payload.b as { x: number; y: number }[];
        if (!a || !b || a.length < 2 || b.length < 2) return null;
        return (
          <g key={idx} stroke="#6a1b9a" strokeWidth={2} opacity={0.9}>
            <line x1={a[0].x} y1={a[0].y} x2={a[1].x} y2={a[1].y} />
            <line x1={b[0].x} y1={b[0].y} x2={b[1].x} y2={b[1].y} />
          </g>
        );
      }
      if (d.type === "TRIANGLE") {
        const pts = d.payload.points as { x: number; y: number }[];
        if (!pts || pts.length < 3) return null;
        const path = `M ${pts[0].x},${pts[0].y} L ${pts[1].x},${pts[1].y} L ${pts[2].x},${pts[2].y} Z`;
        return <path key={idx} d={path} stroke="#ef6c00" fill="rgba(239,108,0,0.1)" strokeWidth={2} />;
      }
      if (d.type === "FIB_RETRACEMENT") {
        const { p1, p2 } = d.payload;
        const y1 = p1.y, y2 = p2.y;
        const xLeft = Math.min(p1.x, p2.x);
        const xRight = Math.max(p1.x, p2.x);
        const height = y2 - y1;
        return (
          <g key={idx} stroke="#1565c0" opacity={0.9}>
            {fibLevels.map((lvl) => {
              const y = y1 + height * lvl;
              return <line key={lvl} x1={xLeft} y1={y} x2={xRight} y2={y} strokeWidth={1} />;
            })}
          </g>
        );
      }
      if (d.type === "FIB_EXTENSION") {
        const { p1, p2, p3 } = d.payload;
        const base = p2.y - p1.y;
        const xLeft = Math.min(p1.x, p3.x);
        const xRight = Math.max(p1.x, p3.x);
        return (
          <g key={idx} stroke="#ad1457" opacity={0.9}>
            {extLevels.map((lvl) => {
              const y = p3.y + base * (lvl - 1); // simple extension visualization
              return <line key={lvl} x1={xLeft} y1={y} x2={xRight} y2={y} strokeWidth={1} />;
            })}
          </g>
        );
      }
      return null;
    });
  }

  function renderInProgress() {
    if (points.length === 0) return null;
    return (
      <g stroke="#000" strokeDasharray="4 4" className="interactive">
        {points.map((p, i) => (
          <circle key={i} cx={p.x} cy={p.y} r={3} fill="#000" />
        ))}
        {points.length >= 2 && <line x1={points[0].x} y1={points[0].y} x2={points[points.length - 1].x} y2={points[points.length - 1].y} />}
      </g>
    );
  }

  return (
    <svg ref={svgRef} className="overlay interactive" width={width} height={height} onClick={handleClick}>
      {renderExisting()}
      {renderInProgress()}
    </svg>
  );
}
