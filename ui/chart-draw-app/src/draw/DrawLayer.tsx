import React, { useMemo, useRef, useState } from "react";
import type { Tool, DrawingType } from "../types";

type Props = {
  width: number;
  height: number;
  tool: Tool;
  onComplete: (type: DrawingType, payload: any) => void;
  onUpdate: (id: number, payload: any) => void;
  onDelete: (id: number) => void;
  onSelect: (id: number | null) => void;
  selectedId: number | null;
  existing: { id: number; type: DrawingType; payload: any }[];
  project: (pt: { time: number; price: number }) => { x: number; y: number };
  unproject: (p: { x: number; y: number }) => { time: number; price: number };
  // when viewport changes (pan/zoom), we want a re-render; changing this value triggers it
  viewportVersion?: number;
};

type Px = { x: number; y: number };
type Tp = { time: number; price: number };

export default function DrawLayer({
  width,
  height,
  tool,
  onComplete,
  onUpdate,
  onDelete,
  onSelect,
  selectedId,
  existing,
  project,
  unproject,
}: Props) {
  const [points, setPoints] = useState<Tp[]>([]);
  const [hover, setHover] = useState<Tp | null>(null);
  const [editing, setEditing] = useState<{ id: number; update: (tp: Tp) => any } | null>(null);
  const [draft, setDraft] = useState<{ id: number; payload: any } | null>(null);
  const svgRef = useRef<SVGSVGElement>(null);

  // Refs to avoid stale closures on global mouseup
  const hoverRef = useRef<Tp | null>(null);
  const draftRef = useRef<{ id: number; payload: any } | null>(null);
  const editingRef = useRef<{ id: number; update: (tp: Tp) => any } | null>(null);
  const creatingRef = useRef<{ type: DrawingType; start: Tp } | null>(null);

  // Start a global drag for a specific handle (editing existing)
  function startDrag(id: number, update: (tp: Tp) => any, e: React.MouseEvent) {
    e.stopPropagation();
    e.preventDefault();
    setEditing({ id, update });
    editingRef.current = { id, update };

    const onMove = (ev: MouseEvent) => {
      const svg = svgRef.current;
      if (!svg) return;
      const rect = svg.getBoundingClientRect();
      const local = { x: ev.clientX - rect.left, y: ev.clientY - rect.top };
      const tp = unproject(local);
      const newPayload = update(tp);
      const d = { id, payload: newPayload };
      setDraft(d);
      draftRef.current = d;
    };

    const onUp = () => {
      const d = draftRef.current;
      if (d && d.id === id) {
        onUpdate(id, d.payload);
      }
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", onUp);
      setEditing(null);
      setDraft(null);
      editingRef.current = null;
      draftRef.current = null;
    };

    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
  }

  // Drag a temporary point while drawing (updates local points[] only)
  function startDragTempPoint(index: number, e: React.MouseEvent) {
    e.stopPropagation();
    e.preventDefault();

    const onMove = (ev: MouseEvent) => {
      const svg = svgRef.current;
      if (!svg) return;
      const rect = svg.getBoundingClientRect();
      const local = { x: ev.clientX - rect.left, y: ev.clientY - rect.top };
      const tp = unproject(local);
      setPoints((prev) => {
        const copy = prev.slice();
        copy[index] = tp;
        return copy;
      });
      setHover(null);
      hoverRef.current = null;
    };

    const onUp = () => {
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", onUp);
    };

    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
  }

  function toLocal(e: React.MouseEvent): Px {
    const rect = (e.currentTarget as Element).getBoundingClientRect();
    return { x: e.clientX - rect.left, y: e.clientY - rect.top };
  }

  // Geometry helpers
  function distToSegment(p: Px, a: Px, b: Px): number {
    const vx = b.x - a.x, vy = b.y - a.y;
    const wx = p.x - a.x, wy = p.y - a.y;
    const c1 = vx * wx + vy * wy;
    if (c1 <= 0) return Math.hypot(p.x - a.x, p.y - a.y);
    const c2 = vx * vx + vy * vy;
    if (c2 <= c1) return Math.hypot(p.x - b.x, p.y - b.y);
    const t = c1 / c2;
    const proj = { x: a.x + t * vx, y: a.y + t * vy };
    return Math.hypot(p.x - proj.x, p.y - proj.y);
  }

  function hitTest(pt: Px): number | null {
    const T = 6; // px tolerance
    for (let i = existing.length - 1; i >= 0; i--) {
      const d = existing[i];
      const payload = d.payload;

      // convenience
      const pxpt = (p: any) => toPxPoint(p);

      if (d.type === "LINE" || d.type === "RAY" || d.type === "EXTENDED_LINE") {
        const pts = payload.points;
        if (!pts || pts.length < 2) continue;
        const p1 = pxpt(pts[0]), p2 = pxpt(pts[1]);
        if (distToSegment(pt, p1, p2) <= T) return d.id;
      } else if (d.type === "HLINE") {
        const price = payload.price;
        if (typeof price === "number") {
          const y = project({ time: 0, price }).y;
          if (Math.abs(pt.y - y) <= T) return d.id;
        }
      } else if (d.type === "VLINE") {
        const time = payload.time;
        if (typeof time === "number") {
          const x = project({ time, price: 0 }).x;
          if (Math.abs(pt.x - x) <= T) return d.id;
        }
      } else if (d.type === "RECT") {
        const a = pxpt(payload.p1), b = pxpt(payload.p2);
        const x1 = Math.min(a.x, b.x), x2 = Math.max(a.x, b.x);
        const y1 = Math.min(a.y, b.y), y2 = Math.max(a.y, b.y);
        if (pt.x >= x1 && pt.x <= x2 && pt.y >= y1 && pt.y <= y2) return d.id;
      } else if (d.type === "TRIANGLE") {
        const p0 = pxpt(payload.points[0]), p1 = pxpt(payload.points[1]), p2 = pxpt(payload.points[2]);
        const area = (a: Px, b: Px, c: Px) => Math.abs((a.x*(b.y-c.y)+b.x*(c.y-a.y)+c.x*(a.y-b.y))/2);
        const A = area(p0,p1,p2), A1 = area(pt,p1,p2), A2 = area(p0,pt,p2), A3 = area(p0,p1,pt);
        if (Math.abs(A - (A1+A2+A3)) < 1) return d.id;
      } else if (d.type === "CHANNEL") {
        const a = payload.a, b = payload.b;
        if (a?.length >= 2 && b?.length >= 2) {
          const a1 = pxpt(a[0]), a2 = pxpt(a[1]);
          const b1 = pxpt(b[0]), b2 = pxpt(b[1]);
          if (distToSegment(pt, a1, a2) <= T || distToSegment(pt, b1, b2) <= T) return d.id;
        }
      } else if (d.type === "TEXT") {
        const p = pxpt(payload.at);
        if (Math.hypot(pt.x - p.x, pt.y - p.y) <= 8) return d.id;
      } else if (d.type === "FIB_RETRACEMENT") {
        const a = pxpt(payload.p1), b = pxpt(payload.p2);
        const x1 = Math.min(a.x, b.x), x2 = Math.max(a.x, b.x);
        const y1 = a.y, y2 = b.y;
        const h = y2 - y1;
        for (const lvl of fibLevels) {
          const y = y1 + h * lvl;
          if (pt.x >= x1 - 8 && pt.x <= x2 + 8 && Math.abs(pt.y - y) <= T) return d.id;
        }
      } else if (d.type === "FIB_EXTENSION") {
        const a = pxpt(payload.p1), b = pxpt(payload.p2), c = pxpt(payload.p3);
        const base = b.y - a.y;
        const x1 = Math.min(a.x, c.x), x2 = Math.max(a.x, c.x);
        // baseline selectable
        if (distToSegment(pt, a, b) <= T) return d.id;
        for (const lvl of extLevels) {
          const y = c.y + base * (lvl - 1);
          if (pt.x >= x1 - 8 && pt.x <= x2 + 8 && Math.abs(pt.y - y) <= T) return d.id;
        }
      }
    }
    return null;
  }

  function handleMouseMove(e: React.MouseEvent) {
    const pLocal = toLocal(e);
    const tp = unproject(pLocal);

    if (tool.kind !== "cursor") {
      setHover(tp);
      hoverRef.current = tp;
    }
    // editing drags and creation drags use global listeners
  }

  function handleMouseLeave() {
    setHover(null);
    hoverRef.current = null;
  }

  function handleMouseUp() {
    // No-op; global mouseup handles finalization for edit and create
  }

  // Start drag-to-create for 2-point tools; use click-flow for multi-point tools
  function handleMouseDownRect(e: React.MouseEvent) {
    if (tool.kind === "cursor") return;

    const pLocal = toLocal(e);
    const start = unproject(pLocal);

    // Map tools
    const dragKinds = new Set([
      "line",
      "ray",
      "extended-line",
      "rectangle",
      "fib-retracement",
      "horizontal-line",
      "vertical-line",
    ] as const);

    const kindToType: Record<string, DrawingType> = {
      "line": "LINE",
      "ray": "RAY",
      "extended-line": "EXTENDED_LINE",
      "rectangle": "RECT",
      "fib-retracement": "FIB_RETRACEMENT",
      "horizontal-line": "HLINE",
      "vertical-line": "VLINE",
    };

    if (dragKinds.has(tool.kind as any)) {
      const type = kindToType[tool.kind];
      creatingRef.current = { type, start };
      setPoints([start]);
      setHover(start);
      hoverRef.current = start;

      const onMove = (ev: MouseEvent) => {
        const svg = svgRef.current;
        if (!svg) return;
        const rect = svg.getBoundingClientRect();
        const local = { x: ev.clientX - rect.left, y: ev.clientY - rect.top };
        const tp = unproject(local);
        setHover(tp);
        hoverRef.current = tp;
      };

      const onUp = () => {
        const c = creatingRef.current;
        const end = hoverRef.current || start;
        if (c && end) {
          const payload = buildPayload(c.type, [start, end]);
          onComplete(c.type, payload);
        }
        window.removeEventListener("mousemove", onMove);
        window.removeEventListener("mouseup", onUp);
        creatingRef.current = null;
        setPoints([]);
        setHover(null);
        hoverRef.current = null;
      };

      window.addEventListener("mousemove", onMove);
      window.addEventListener("mouseup", onUp);
      return;
    }

    // Click-flow tools (multi-point): triangle, channel, fib-extension, text (still one click)
    let needed = 0;
    let type: DrawingType | null = null;

    switch (tool.kind) {
      case "channel":
        needed = 4; type = "CHANNEL"; break;
      case "triangle":
        needed = 3; type = "TRIANGLE"; break;
      case "fib-extension":
        needed = 3; type = "FIB_EXTENSION"; break;
      case "text":
        needed = 1; type = "TEXT"; break;
      default:
        break;
    }

    if (!type) return;

    const nxt = [...points, start];
    setPoints(nxt);
    if (nxt.length >= needed) {
      const payload = buildPayload(type, nxt);
      onComplete(type, payload);
      setPoints([]);
      setHover(null);
      hoverRef.current = null;
    }
  }

  function buildPayload(type: DrawingType, pts: Tp[]) {
    switch (type) {
      case "LINE":
        return { points: pts.slice(0, 2) };
      case "RAY":
        return { points: pts.slice(0, 2) };
      case "EXTENDED_LINE":
        return { points: pts.slice(0, 2) };
      case "HLINE":
        return { price: pts[0].price };
      case "VLINE":
        return { time: pts[0].time };
      case "CHANNEL":
        return { a: pts.slice(0, 2), b: pts.slice(2, 4) };
      case "RECT":
        return { p1: pts[0], p2: pts[1] };
      case "TRIANGLE":
        return { points: pts.slice(0, 3) };
      case "TEXT":
        return { at: pts[0], text: (tool.kind === "text" && tool.defaultText) || "Text" };
      case "FIB_RETRACEMENT":
        return { p1: pts[0], p2: pts[1] };
      case "FIB_EXTENSION":
        return { p1: pts[0], p2: pts[1], p3: pts[2] };
    }
  }

  const fibLevels = useMemo(() => [0, 0.236, 0.382, 0.5, 0.618, 0.786, 1], []);
  const extLevels = useMemo(() => [1.272, 1.618, 2.0, 2.618], []);

  // Helpers to normalize existing payloads (legacy px or logical tp)
  function isPx(obj: any): obj is Px {
    return obj && typeof obj.x === "number" && typeof obj.y === "number";
  }
  function isTp(obj: any): obj is Tp {
    return obj && typeof obj.time === "number" && typeof obj.price === "number";
  }
  function toPxPoint(p: any): Px {
    if (isPx(p)) return p;
    if (isTp(p)) return project(p);
    return { x: 0, y: 0 };
    }

  function renderExisting() {
    return existing.map((d) => {
      const effPayload = draft && draft.id === d.id ? draft.payload : d.payload;

      let elem: JSX.Element | null = null;

      if (d.type === "LINE") {
        const ptsRaw = effPayload.points;
        if (!ptsRaw || ptsRaw.length < 2) return null;
        const p1 = toPxPoint(ptsRaw[0]);
        const p2 = toPxPoint(ptsRaw[1]);
        elem = <line x1={p1.x} y1={p1.y} x2={p2.x} y2={p2.y} stroke="#2e7d32" strokeWidth={2} />;
      } else if (d.type === "RAY") {
        const ptsRaw = effPayload.points;
        if (!ptsRaw || ptsRaw.length < 2) return null;
        const p1 = toPxPoint(ptsRaw[0]);
        const p2 = toPxPoint(ptsRaw[1]);
        const dx = p2.x - p1.x;
        const dy = p2.y - p1.y;
        if (Math.abs(dx) < 1e-6 && Math.abs(dy) < 1e-6) return null;
        const t = (width - p1.x) / (dx || 1e-6);
        const xr = width;
        const yr = p1.y + dy * t;
        elem = <line x1={p1.x} y1={p1.y} x2={xr} y2={yr} stroke="#1e88e5" strokeWidth={2} />;
      } else if (d.type === "EXTENDED_LINE") {
        const ptsRaw = effPayload.points;
        if (!ptsRaw || ptsRaw.length < 2) return null;
        const p1 = toPxPoint(ptsRaw[0]);
        const p2 = toPxPoint(ptsRaw[1]);
        const dx = p2.x - p1.x;
        const dy = p2.y - p1.y;
        if (Math.abs(dx) < 1e-6 && Math.abs(dy) < 1e-6) return null;
        const t1 = -p1.x / (dx || 1e-6);
        const xl = 0;
        const yl = p1.y + dy * t1;
        const t2 = (width - p1.x) / (dx || 1e-6);
        const xr = width;
        const yr = p1.y + dy * t2;
        elem = <line x1={xl} y1={yl} x2={xr} y2={yr} stroke="#5e35b1" strokeWidth={2} />;
      } else if (d.type === "HLINE") {
        const price = effPayload.price;
        if (typeof price !== "number") return null;
        const y = project({ time: 0, price }).y;
        elem = <line x1={0} y1={y} x2={width} y2={y} stroke="#00897b" strokeDasharray="6 4" strokeWidth={1.5} />;
      } else if (d.type === "VLINE") {
        const time = effPayload.time;
        if (typeof time !== "number") return null;
        const x = project({ time, price: 0 }).x;
        elem = <line x1={x} y1={0} x2={x} y2={height} stroke="#455a64" strokeDasharray="6 4" strokeWidth={1.5} />;
      } else if (d.type === "CHANNEL") {
        const a = effPayload.a;
        const b = effPayload.b;
        if (!a || !b || a.length < 2 || b.length < 2) return null;
        const a1 = toPxPoint(a[0]);
        const a2 = toPxPoint(a[1]);
        const b1 = toPxPoint(b[0]);
        const b2 = toPxPoint(b[1]);

        const xr = width - 1;
        const yOnLine = (p1: Px, p2: Px, x: number) => {
          const dx = p2.x - p1.x;
          if (Math.abs(dx) < 1e-6) return p2.y;
          const t = (x - p1.x) / dx;
          return p1.y + (p2.y - p1.y) * t;
        };
        const ya = yOnLine(a1, a2, xr);
        const yb = yOnLine(b1, b2, xr);
        const midY = (ya + yb) / 2;

        const priceA = unproject({ x: xr, y: ya }).price;
        const priceB = unproject({ x: xr, y: yb }).price;
        const delta = priceA - priceB;
        const base = Math.max(1e-9, Math.min(Math.abs(priceA), Math.abs(priceB)));
        const pct = (Math.abs(delta) / base) * 100;

        const label = (y: number, txt: string, key: string) => {
          const pad = 4;
          const w = txt.length * 7 + pad * 2;
          const x = Math.max(0, xr - w - 2);
          return (
            <g key={key}>
              <rect x={x} y={y - 10} width={w} height={16} fill="rgba(255,255,255,0.85)" stroke="#e0e0e0" rx={3} />
              <text x={x + pad} y={y + 2} fontSize="11" fill="#333" fontFamily="sans-serif">
                {txt}
              </text>
            </g>
          );
        };

        elem = (
          <g stroke="#6a1b9a" strokeWidth={2} opacity={0.95}>
            <line x1={a1.x} y1={a1.y} x2={a2.x} y2={a2.y} />
            <line x1={b1.x} y1={b1.y} x2={b2.x} y2={b2.y} />
            {label(ya, priceA.toFixed(2), "pa")}
            {label(yb, priceB.toFixed(2), "pb")}
            {label(midY, `Δ ${Math.abs(delta).toFixed(2)} (${pct.toFixed(2)}%)`, "pdelta")}
          </g>
        );
      } else if (d.type === "RECT") {
        const p1 = effPayload.p1;
        const p2 = effPayload.p2;
        if (!p1 || !p2) return null;
        const a = toPxPoint(p1);
        const b = toPxPoint(p2);
        const x = Math.min(a.x, b.x);
        const y = Math.min(a.y, b.y);
        const w = Math.abs(a.x - b.x);
        const h = Math.abs(a.y - b.y);
        elem = <rect x={x} y={y} width={w} height={h} fill="rgba(33,150,243,0.08)" stroke="#2196f3" strokeWidth={2} />;
      } else if (d.type === "TRIANGLE") {
        const pts = effPayload.points as any[];
        if (!pts || pts.length < 3) return null;
        const p0 = toPxPoint(pts[0]);
        const p1 = toPxPoint(pts[1]);
        const p2 = toPxPoint(pts[2]);
        const path = `M ${p0.x},${p0.y} L ${p1.x},${p1.y} L ${p2.x},${p2.y} Z`;
        elem = <path d={path} stroke="#ef6c00" fill="rgba(239,108,0,0.1)" strokeWidth={2} />;
      } else if (d.type === "TEXT") {
        const at = effPayload.at;
        if (!at) return null;
        const p = toPxPoint(at);
        const text = d.payload.text || "Text";
        elem = (
          <g transform={`translate(${p.x}, ${p.y})`}>
            <rect x={-4} y={-16} width={text.length * 7 + 8} height={18} fill="rgba(0,0,0,0.6)" rx={3} />
            <text x={0} y={-3} fill="#fff" fontSize="12" fontFamily="sans-serif">
              {text}
            </text>
          </g>
        );
      } else if (d.type === "FIB_RETRACEMENT") {
        const { p1, p2 } = effPayload;
        if (!p1 || !p2) return null;
        const a = toPxPoint(p1);
        const b = toPxPoint(p2);
        const y1 = a.y, y2 = b.y;
        const xLeft = Math.min(a.x, b.x);
        const xRight = Math.max(a.x, b.x);
        const heightPx = y2 - y1;

        const label = (xBase: number, y: number, pct: number, price: number, key: string) => {
          const pad = 4;
          const txt = `${pct.toFixed(1)}%  ${price.toFixed(2)}`;
          const w = txt.length * 7 + pad * 2;
          const x = Math.min(width - w - 2, xBase + 6);
          return (
            <g key={key}>
              <rect x={x} y={y - 10} width={w} height={16} fill="rgba(255,255,255,0.85)" stroke="#e0e0e0" rx={3} />
              <text x={x + pad} y={y + 2} fontSize="11" fill="#1565c0" fontFamily="sans-serif">
                {txt}
              </text>
            </g>
          );
        };

        elem = (
          <g stroke="#1565c0" opacity={0.95}>
            {fibLevels.map((lvl) => {
              const y = y1 + heightPx * lvl;
              const price = unproject({ x: 0, y }).price;
              return (
                <g key={lvl}>
                  <line x1={xLeft} y1={y} x2={xRight} y2={y} strokeWidth={1} />
                  {label(xRight, y, lvl * 100, price, `fib-${lvl}`)}
                </g>
              );
            })}
          </g>
        );
      } else if (d.type === "FIB_EXTENSION") {
        const { p1, p2, p3 } = effPayload;
        if (!p1 || !p2 || !p3) return null;
        const a = toPxPoint(p1);
        const b = toPxPoint(p2);
        const c = toPxPoint(p3);
        const base = b.y - a.y;
        const xLeft = Math.min(a.x, c.x);
        const xRight = Math.max(a.x, c.x);

        const label = (xBase: number, y: number, lvl: number, price: number, key: string) => {
          const pad = 4;
          const pct = lvl * 100;
          const txt = `${pct.toFixed(1)}%  ${price.toFixed(2)}`;
          const w = txt.length * 7 + pad * 2;
          const x = Math.min(width - w - 2, xBase + 6);
          return (
            <g key={key}>
              <rect x={x} y={y - 10} width={w} height={16} fill="rgba(255,255,255,0.85)" stroke="#e0e0e0" rx={3} />
              <text x={x + pad} y={y + 2} fontSize="11" fill="#ad1457" fontFamily="sans-serif">
                {txt}
              </text>
            </g>
          );
        };

        elem = (
          <g stroke="#ad1457" opacity={0.95}>
            <line x1={a.x} y1={a.y} x2={b.x} y2={b.y} stroke="#ad1457" strokeDasharray="4 2" strokeWidth={1.5} />
            {extLevels.map((lvl) => {
              const y = c.y + base * (lvl - 1);
              const price = unproject({ x: 0, y }).price;
              return (
                <g key={lvl}>
                  <line x1={xLeft} y1={y} x2={xRight} y2={y} strokeWidth={1} />
                  {label(xRight, y, lvl, price, `fibx-${lvl}`)}
                </g>
              );
            })}
          </g>
        );
      }

      if (!elem) return null;

      // Wrap with a selectable group. In cursor mode, clicking selects; otherwise ignore.
      return (
        <g
          key={d.id}
          onMouseDown={(e) => {
            if (tool.kind === "cursor") {
              e.stopPropagation();
              onSelect(d.id);
            }
          }}
          style={{ pointerEvents: "visiblePainted" }}
        >
          {elem}
        </g>
      );
    });
  }

  function renderHandles() {
    // In cursor mode, render draggable handles for the selected (or currently editing) drawing
    const mid = unproject({ x: width / 2, y: height / 2 });
    const circles: JSX.Element[] = [];

    const targetIds = new Set<number>();
    if (selectedId != null) targetIds.add(selectedId);
    if (editing && editing.id != null) targetIds.add(editing.id);

    existing.forEach((d) => {
      if (!targetIds.has(d.id)) return;
      const payload = draft && draft.id === d.id ? draft.payload : d.payload;

      const addCircle = (hid: string, tp: Tp, toPayload: (ntp: Tp) => any) => {
        const p = project(tp);
        circles.push(
          <circle
            key={`${d.id}-${hid}`}
            cx={p.x}
            cy={p.y}
            r={5}
            fill="#fff"
            stroke="#1976d2"
            strokeWidth={2}
            style={{ pointerEvents: "auto", cursor: "pointer" }}
            onMouseDown={(e) => {
              // Begin global drag; base payload is current one
              setDraft({ id: d.id, payload });
              // Select the drawing being edited
              onSelect(d.id);
              startDrag(d.id, toPayload, e);
            }}
          />
        );
      };

      switch (d.type) {
        case "LINE": {
          const pts = payload.points;
          if (pts?.length >= 2) {
            addCircle("p0", pts[0], (ntp) => ({ ...payload, points: [ntp, pts[1]] }));
            addCircle("p1", pts[1], (ntp) => ({ ...payload, points: [pts[0], ntp] }));
          }
          break;
        }
        case "RAY":
        case "EXTENDED_LINE": {
          const pts = payload.points;
          if (pts?.length >= 2) {
            addCircle("p0", pts[0], (ntp) => ({ ...payload, points: [ntp, pts[1]] }));
            addCircle("p1", pts[1], (ntp) => ({ ...payload, points: [pts[0], ntp] }));
          }
          break;
        }
        case "CHANNEL": {
          const a = payload.a, b = payload.b;
          if (a?.length >= 2) {
            addCircle("a0", a[0], (ntp) => ({ ...payload, a: [ntp, a[1]], b }));
            addCircle("a1", a[1], (ntp) => ({ ...payload, a: [a[0], ntp], b }));
          }
          if (b?.length >= 2) {
            addCircle("b0", b[0], (ntp) => ({ ...payload, a, b: [ntp, b[1]] }));
            addCircle("b1", b[1], (ntp) => ({ ...payload, a, b: [b[0], ntp] }));
          }
          break;
        }
        case "RECT": {
          const { p1, p2 } = payload;
          if (p1 && p2) {
            addCircle("p1", p1, (ntp) => ({ ...payload, p1: ntp }));
            addCircle("p2", p2, (ntp) => ({ ...payload, p2: ntp }));
          }
          break;
        }
        case "TRIANGLE": {
          const pts = payload.points;
          if (pts?.length >= 3) {
            addCircle("p0", pts[0], (ntp) => ({ ...payload, points: [ntp, pts[1], pts[2]] }));
            addCircle("p1", pts[1], (ntp) => ({ ...payload, points: [pts[0], ntp, pts[2]] }));
            addCircle("p2", pts[2], (ntp) => ({ ...payload, points: [pts[0], pts[1], ntp] }));
          }
          break;
        }
        case "TEXT": {
          const { at } = payload;
          if (at) {
            addCircle("at", at, (ntp) => ({ ...payload, at: ntp }));
          }
          break;
        }
        case "HLINE": {
          const price = payload.price;
          if (typeof price === "number") {
            addCircle("price", { time: mid.time, price }, (ntp) => ({ ...payload, price: ntp.price }));
          }
          break;
        }
        case "VLINE": {
          const time = payload.time;
          if (typeof time === "number") {
            addCircle("time", { time, price: mid.price }, (ntp) => ({ ...payload, time: ntp.time }));
          }
          break;
        }
        case "FIB_RETRACEMENT": {
          const { p1, p2 } = payload;
          if (p1 && p2) {
            addCircle("p1", p1, (ntp) => ({ ...payload, p1: ntp }));
            addCircle("p2", p2, (ntp) => ({ ...payload, p2: ntp }));
          }
          break;
        }
        case "FIB_EXTENSION": {
          const { p1, p2, p3 } = payload;
          if (p1 && p2 && p3) {
            addCircle("p1", p1, (ntp) => ({ ...payload, p1: ntp }));
            addCircle("p2", p2, (ntp) => ({ ...payload, p2: ntp }));
            addCircle("p3", p3, (ntp) => ({ ...payload, p3: ntp }));
          }
          break;
        }
      }
    });

    return <g className="handles">{circles}</g>;
  }

  function renderInProgress() {
    if (points.length === 0) return null;

    // Project current logical points for preview
    const pxPts = points.map(project);

    return (
      <g stroke="#000" strokeDasharray="4 4" className="interactive">
        {pxPts.map((p, i) => (
          <circle key={i} cx={p.x} cy={p.y} r={3} fill="#000" />
        ))}
        {pxPts.length >= 2 && (
          <line
            x1={pxPts[0].x}
            y1={pxPts[0].y}
            x2={pxPts[pxPts.length - 1].x}
            y2={pxPts[pxPts.length - 1].y}
          />
        )}
      </g>
    );
  }

  // Route clicks: select in cursor mode, draw otherwise
  function handleSvgMouseDown(e: React.MouseEvent) {
    if (tool.kind === "cursor") {
      const rect = (e.currentTarget as Element).getBoundingClientRect();
      const pt = { x: e.clientX - rect.left, y: e.clientY - rect.top };
      const id = hitTest(pt);
      onSelect(id);
      return;
    }
    handleMouseDownRect(e);
  }

  return (
    <svg
      ref={svgRef}
      className="interactive"
      width={width}
      height={height}
      onMouseDown={handleSvgMouseDown}
      onMouseMove={handleMouseMove}
      onMouseUp={handleMouseUp}
      // In cursor mode, only painted parts receive events; empty space passes through to the chart.
      style={{ pointerEvents: tool.kind === "cursor" ? "visiblePainted" : "auto" }}
    >
      {renderExisting()}
      {renderHandles()}
      {renderInProgress()}
    </svg>
  );
}
