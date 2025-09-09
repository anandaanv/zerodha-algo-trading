/* eslint-disable @typescript-eslint/no-explicit-any */
import type { Time } from "lightweight-charts";
import { BaseOverlayPlugin } from "./BaseOverlayPlugin";

type TrendPoint = { time: Time; price: number };
type TrendLine = { p1: TrendPoint; p2: TrendPoint };
type DragMode = "none" | "p1" | "p2" | "move";

export class TrendlinePlugin extends BaseOverlayPlugin {
  private drawing = false;
  private firstPoint: { x: number; y: number } | null = null;
  private mouse: { x: number; y: number } | null = null;

  private uid = () => Math.random().toString(36).slice(2, 10);
  private lines: (TrendLine & { id: string })[] = [];
  private selectedId: string | null = null;
  private dragMode: DragMode = "none";
  private dragStart: { x: number; y: number } | null = null;
  private originalPx:
    | {
        p1px: { x: number; y: number };
        p2px: { x: number; y: number };
        p1: TrendPoint;
        p2: TrendPoint;
      }
    | null = null;

  private handleMove = (e: MouseEvent) => {
    const pt = this.toLocal(e);

    console.log(this.dragMode, this.drawing);
    if (this.drawing) {
      this.mouse = pt;
      this.render();
      return;
    }


    if (this.dragMode !== "none" && this.selectedId && this.dragStart && this.originalPx) {
      const dx = pt.x - this.dragStart.x;
      const dy = pt.y - this.dragStart.y;
      const idx = this.lines.findIndex((l) => l.id === this.selectedId);
      if (idx >= 0) {
        const ln = this.lines[idx];
        const toPoint = (px: { x: number; y: number }) => {
          const t = this.xToTime(px.x);
          const p = this.yToPrice(px.y);
          return t != null && p != null ? ({ time: t, price: p } as TrendPoint) : null;
        };

        if (this.dragMode === "move") {
          const np1 = toPoint({ x: this.originalPx.p1px.x + dx, y: this.originalPx.p1px.y + dy });
          const np2 = toPoint({ x: this.originalPx.p2px.x + dx, y: this.originalPx.p2px.y + dy });
          if (np1 && np2) {
            ln.p1 = np1;
            ln.p2 = np2;
          }
        } else if (this.dragMode === "p1") {
          const np1 = toPoint({ x: this.originalPx.p1px.x + dx, y: this.originalPx.p1px.y + dy });
          if (np1) ln.p1 = np1;
        } else if (this.dragMode === "p2") {
          const np2 = toPoint({ x: this.originalPx.p2px.x + dx, y: this.originalPx.p2px.y + dy });
          if (np2) ln.p2 = np2;
        }
      }
      this.render();
      return;
    }

    const hit = this.hitTest(pt);
    this.canvas.style.cursor = hit ? (hit.mode === "move" ? "move" : "pointer") : "default";
  };

  private handleDown = (e: MouseEvent) => {
    if (e.button !== 0) return;
    const pt = this.toLocal(e);

    if (this.drawing) {
      if (!this.firstPoint) {
        this.firstPoint = pt;
        this.render();
        return;
      }
      const t1 = this.xToTime(this.firstPoint.x);
      const p1 = this.yToPrice(this.firstPoint.y);
      const t2 = this.xToTime(pt.x);
      const p2 = this.yToPrice(pt.y);
      if (t1 != null && p1 != null && t2 != null && p2 != null) {
        const id = this.uid();
        this.lines.push({ id, p1: { time: t1, price: p1 }, p2: { time: t2, price: p2 } });
        this.selectedId = id;
      }
      this.cancel();
      this.render();
      return;
    }

    const hit = this.hitTest(pt);
    if (hit) {
      this.selectedId = hit.id;
      this.dragMode = hit.mode;
      this.dragStart = pt;
      const ln = this.lines.find((l) => l.id === this.selectedId)!;
      const seg = this.getLinePx(ln)!;
      this.originalPx = {
        p1px: { ...seg.a },
        p2px: { ...seg.b },
        p1: { ...ln.p1 },
        p2: { ...ln.p2 },
      };
      this.render();
    } else {
      this.selectedId = null;
      this.dragMode = "none";
      this.dragStart = null;
      this.originalPx = null;
      this.render();
    }
  };

  private handleUp = () => {
    if (this.dragMode !== "none") {
      this.dragMode = "none";
      this.dragStart = null;
      this.originalPx = null;
      this.render();
    }
  };

  // Simple styles for selection and defaults (used by properties dialog)
  private selectionStyles = new Map<string, { color: string; width: number; style: "solid" | "dashed" }>();
  private defaultStyle: { color: string; width: number; style: "solid" | "dashed" } = {
    color: "#1976d2",
    width: 2,
    style: "solid",
  };

  hasSelection() {
    return !!this.selectedId;
  }

  getSelectedStyle(): { color: string; width: number; style: "solid" | "dashed" } | null {
    if (!this.selectedId) return null;
    return this.selectionStyles.get(this.selectedId) ?? { ...this.defaultStyle };
  }

  applySelectedStyle(s: { color: string; width: number; style: "solid" | "dashed" }): boolean {
    if (!this.selectedId) return false;
    this.selectionStyles.set(this.selectedId, { ...s });
    this.render();
    return true;
  }

  constructor(params: { chart: any; series: any; container: HTMLElement }) {
    super(params);
    this.canvas.addEventListener("mousemove", this.handleMove);
    this.canvas.addEventListener("mousedown", this.handleDown);
    this.canvas.addEventListener("mouseup", this.handleUp);
    window.addEventListener("mouseup", this.handleUp);

    // start inactive by default
    this.setActive(false);
    // disable pointer events unless drawing; wrapper will handle selection
    this.canvas.style.pointerEvents = "none";

    // Now that subclass fields are initialized and listeners set, render once.
    this.render();
  }

  startDrawing() {
    this.drawing = true;
    this.firstPoint = null;
    this.mouse = null;
    this.setActive(true);
    this.canvas.style.pointerEvents = "auto";
    this.canvas.style.cursor = "crosshair";
    this.render();
  }

  cancel() {
    if (!this.drawing) return;
    this.drawing = false;
    this.firstPoint = null;
    this.mouse = null;
    this.canvas.style.pointerEvents = "none";
    this.canvas.style.cursor = "default";
    this.render();
  }

  clearSelection() {
    this.selectedId = null;
    // Return control to the chart/wrapper
    this.canvas.style.pointerEvents = "none";
    this.setActive(false);
    this.render();
  }

  trySelectAt(clientX: number, clientY: number): boolean {
    // Convert to plugin-local coords
    const rect = this.canvas.getBoundingClientRect();
    const pt = { x: clientX - rect.left, y: clientY - rect.top };
    const hit = this.hitTest(pt);
    if (hit) {
        // Pure selection: do not reset drag state here
        this.selectedId = hit.id;
      // Make this overlay interactive for the next click-and-drag
      this.canvas.style.pointerEvents = "auto";
      this.setActive(true);
      this.render();
      return true;
    }
    return false;
  }

  deleteSelected() {
    if (!this.selectedId) return false;
    const idx = this.lines.findIndex((l) => l.id === this.selectedId);
    if (idx >= 0) {
      this.lines.splice(idx, 1);
      this.selectedId = null;
      // No selection left: stop intercepting events
      this.canvas.style.pointerEvents = "none";
      this.setActive(false);
      this.render();
      return true;
    }
    return false;
  }

  getLines() {
    return [...this.lines];
  }

  clear() {
    this.lines.splice(0, this.lines.length);
    this.selectedId = null;
    this.render();
  }

  destroy() {
    try {
      this.canvas.removeEventListener("mousemove", this.handleMove);
      this.canvas.removeEventListener("mousedown", this.handleDown);
      this.canvas.removeEventListener("mouseup", this.handleUp);
      window.removeEventListener("mouseup", this.handleUp);
    } catch {}
    super.destroy();
  }

  // rendering
  render() {
    this.clearCanvas();
    // saved lines
    for (const ln of this.lines) {
      const x1 = this.timeToX(ln.p1.time);
      const y1 = ln.p1.price != null ? this.priceToY(ln.p1.price) : null;
      const x2 = this.timeToX(ln.p2.time);
      const y2 = ln.p2.price != null ? this.priceToY(ln.p2.price) : null;
      if (x1 != null && y1 != null && x2 != null && y2 != null) {
        const isSel = ln.id === this.selectedId;
        const style = this.selectionStyles.get(ln.id) ?? this.defaultStyle;
        this.drawLinePx(x1, y1, x2, y2, style);
        if (isSel) {
          this.drawAnchor(x1, y1);
          this.drawAnchor(x2, y2);
        }
      }
    }
    // preview
    if (this.drawing && this.firstPoint && this.mouse) {
      this.drawLinePx(this.firstPoint.x, this.firstPoint.y, this.mouse.x, this.mouse.y, {
        color: this.defaultStyle.color,
        width: Math.max(1, this.defaultStyle.width),
        style: this.defaultStyle.style,
      });
    }
  }

  private drawLinePx(
    x1: number,
    y1: number,
    x2: number,
    y2: number,
    style: { color: string; width: number; style: "solid" | "dashed" }
  ) {
    const ctx = this.ctx;
    ctx.save();
    ctx.strokeStyle = style.color;
    ctx.lineWidth = Math.max(1, style.width);
    if (style.style === "dashed") ctx.setLineDash([6, 4]);
    ctx.beginPath();
    ctx.moveTo(x1 + 0.5, y1 + 0.5);
    ctx.lineTo(x2 + 0.5, y2 + 0.5);
    ctx.stroke();
    ctx.setLineDash([]);
    ctx.restore();
  }

  // ---- Persistence API ----
  exportAll(): Array<{ p1: { time: any; price: number }; p2: { time: any; price: number }; style: { color: string; width: number; style: "solid" | "dashed" } }> {
    return this.lines.map((ln) => {
      const style = this.selectionStyles.get(ln.id) ?? this.defaultStyle;
      return {
        p1: { time: ln.p1.time, price: ln.p1.price },
        p2: { time: ln.p2.time, price: ln.p2.price },
        style: { color: style.color, width: style.width, style: style.style },
      };
    });
  }

  importAll(items: Array<{ p1: { time: any; price: number }; p2: { time: any; price: number }; style?: { color: string; width: number; style: "solid" | "dashed" } }>) {
    // clear existing
    this.lines.splice(0, this.lines.length);
    this.selectionStyles.clear();

    const normalizeTime = (t: any) => {
      if (typeof t === "number" && t > 1e12) {
        // looks like milliseconds, convert to seconds for lightweight-charts
        return Math.floor(t / 1000);
      }
      return t;
    };

    for (const it of items || []) {
      const id = this.uid();
      const style = it.style ?? this.defaultStyle;
      this.lines.push({
        id,
        p1: { time: normalizeTime(it.p1.time), price: it.p1.price },
        p2: { time: normalizeTime(it.p2.time), price: it.p2.price },
        props: {
          color: style.color ?? "#1976d2",
          width: style.width ?? 2,
          style: (style.style as "solid" | "dashed") ?? "solid",
          text: "",
          textColor: "#333333",
          textPos: "mid",
        },
      });
      this.selectionStyles.set(id, { color: style.color, width: style.width, style: style.style });
    }
    this.render();
  }

  private drawAnchor(x: number, y: number) {
    const ctx = this.ctx;
    ctx.save();
    ctx.fillStyle = "#1976d2";
    ctx.strokeStyle = "#ffffff";
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.arc(x, y, 4, 0, Math.PI * 2);
    ctx.fill();
    ctx.stroke();
    ctx.restore();
  }

  private dist(a: { x: number; y: number }, b: { x: number; y: number }) {
    const dx = a.x - b.x;
    const dy = a.y - b.y;
    return Math.hypot(dx, dy);
  }
  private distToSegment(p: { x: number; y: number }, a: { x: number; y: number }, b: { x: number; y: number }) {
    const l2 = (a.x - b.x) ** 2 + (a.y - b.y) ** 2;
    if (l2 === 0) return this.dist(p, a);
    let t = ((p.x - a.x) * (b.x - a.x) + (p.y - a.y) * (b.y - a.y)) / l2;
    t = Math.max(0, Math.min(1, t));
    const proj = { x: a.x + t * (b.x - a.x), y: a.y + t * (b.y - a.y) };
    return this.dist(p, proj);
  }
  private getLinePx(ln: TrendLine) {
    const x1 = this.timeToX(ln.p1.time);
    const y1 = ln.p1.price != null ? this.priceToY(ln.p1.price) : null;
    const x2 = this.timeToX(ln.p2.time);
    const y2 = ln.p2.price != null ? this.priceToY(ln.p2.price) : null;
    if (x1 == null || y1 == null || x2 == null || y2 == null) return null;
    return { a: { x: x1, y: y1 }, b: { x: x2, y: y2 } };
  }
  private hitTest(pt: { x: number; y: number }): { id: string; mode: DragMode } | null {
    const threshold = 6;
    for (let i = this.lines.length - 1; i >= 0; i--) {
      const ln = this.lines[i];
      const seg = this.getLinePx(ln);
      if (!seg) continue;
      if (this.dist(pt, seg.a) <= threshold) return { id: ln.id, mode: "p1" };
      if (this.dist(pt, seg.b) <= threshold) return { id: ln.id, mode: "p2" };
    }
    for (let i = this.lines.length - 1; i >= 0; i--) {
      const ln = this.lines[i];
      const seg = this.getLinePx(ln);
      if (!seg) continue;
      if (this.distToSegment(pt, seg.a, seg.b) <= threshold) {
        return { id: ln.id, mode: "move" };
      }
    }
    return null;
  }
}
