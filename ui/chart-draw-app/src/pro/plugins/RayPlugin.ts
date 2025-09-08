/* eslint-disable @typescript-eslint/no-explicit-any */
import type { Time } from "lightweight-charts";
import { BaseOverlayPlugin } from "./BaseOverlayPlugin";

type TrendPoint = { time: Time; price: number };
type TrendLine = { p1: TrendPoint; p2: TrendPoint };
type DragMode = "none" | "p1" | "p2" | "move";

export class RayPlugin extends BaseOverlayPlugin {
  private drawing = false;
  private firstPoint: { x: number; y: number } | null = null;
  private mouse: { x: number; y: number } | null = null;

  private uid = () => Math.random().toString(36).slice(2, 10);
  private rays: (TrendLine & { id: string })[] = [];
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

    if (this.drawing) {
      this.mouse = pt;
      this.render();
      return;
    }

    if (this.dragMode !== "none" && this.selectedId && this.dragStart && this.originalPx) {
      const dx = pt.x - this.dragStart.x;
      const dy = pt.y - this.dragStart.y;
      const idx = this.rays.findIndex((l) => l.id === this.selectedId);
      if (idx >= 0) {
        const ln = this.rays[idx];
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
        this.rays.push({ id, p1: { time: t1, price: p1 }, p2: { time: t2, price: p2 } });
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
      const ln = this.rays.find((l) => l.id === this.selectedId)!;
      const seg = this.getRayPx(ln)!;
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

  constructor(params: { chart: any; series: any; container: HTMLElement }) {
    super(params);
    this.canvas.addEventListener("mousemove", this.handleMove);
    this.canvas.addEventListener("mousedown", this.handleDown);
    this.canvas.addEventListener("mouseup", this.handleUp);
    window.addEventListener("mouseup", this.handleUp);

    // start inactive by default
    this.setActive(false);

    // Now that subclass fields are initialized and listeners set, render once.
    this.render();
  }

  startDrawing() {
    this.drawing = true;
    this.firstPoint = null;
    this.mouse = null;
    this.setActive(true);
    this.canvas.style.cursor = "crosshair";
    this.render();
  }

  cancel() {
    if (!this.drawing) return;
    this.drawing = false;
    this.firstPoint = null;
    this.mouse = null;
    this.canvas.style.cursor = "default";
    this.render();
  }

  deleteSelected() {
    if (!this.selectedId) return false;
    const idx = this.rays.findIndex((l) => l.id === this.selectedId);
    if (idx >= 0) {
      this.rays.splice(idx, 1);
      this.selectedId = null;
      this.render();
      return true;
    }
    return false;
  }

  getLines() {
    return [...this.rays];
  }

  clear() {
    this.rays.splice(0, this.rays.length);
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

  render() {
    this.clearCanvas();

    // draw saved rays
    for (const ln of this.rays) {
      const seg = this.getRayPx(ln);
      if (!seg) continue;
      const isSel = ln.id === this.selectedId;
      this.drawLinePx(seg.a.x, seg.a.y, seg.b.x, seg.b.y, isSel ? "#1565c0" : "#1976d2");
      if (isSel) {
        this.drawAnchor(seg.a.x, seg.a.y);
        this.drawAnchor(this.timeToX(ln.p2.time)!, this.priceToY(ln.p2.price)!);
      }
    }

    // preview
    if (this.drawing && this.firstPoint && this.mouse) {
      this.drawLinePx(this.firstPoint.x, this.firstPoint.y, this.mouse.x, this.mouse.y, "#455a64");
    }
  }

  private drawLinePx(x1: number, y1: number, x2: number, y2: number, style = "#1976d2") {
    const ctx = this.ctx;
    ctx.save();
    ctx.strokeStyle = style;
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    ctx.moveTo(x1 + 0.5, y1 + 0.5);
    ctx.lineTo(x2 + 0.5, y2 + 0.5);
    ctx.stroke();
    ctx.restore();
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

  private getRayPx(ln: TrendLine) {
    const x1 = this.timeToX(ln.p1.time);
    const y1 = this.priceToY(ln.p1.price);
    const x2 = this.timeToX(ln.p2.time);
    const y2 = this.priceToY(ln.p2.price);
    if (x1 == null || y1 == null || x2 == null || y2 == null) return null;

    // Extend from p1 through p2 to the right edge
    const rect = this.container.getBoundingClientRect();
    const dx = x2 - x1;
    const dy = y2 - y1;
    const eps = 1e-6;
    if (Math.abs(dx) < eps) {
      // vertical ray
      return { a: { x: x1, y: y1 }, b: { x: x1, y: rect.height } };
    }
    const slope = dy / dx;
    const xRight = rect.width;
    const yRight = y1 + slope * (xRight - x1);
    return { a: { x: x1, y: y1 }, b: { x: xRight, y: yRight } };
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
  private hitTest(pt: { x: number; y: number }): { id: string; mode: DragMode } | null {
    const threshold = 6;
    // anchors: p1 and p2 (p2 anchor stays at exact second point, ray extends right visually)
    for (let i = this.rays.length - 1; i >= 0; i--) {
      const ln = this.rays[i];
      const a = this.timeToX(ln.p1.time);
      const ay = this.priceToY(ln.p1.price);
      const bx = this.timeToX(ln.p2.time);
      const by = this.priceToY(ln.p2.price);
      if (a != null && ay != null && this.dist(pt, { x: a, y: ay }) <= threshold) return { id: ln.id, mode: "p1" };
      if (bx != null && by != null && this.dist(pt, { x: bx, y: by }) <= threshold) return { id: ln.id, mode: "p2" };
    }
    // line body: use extended ray segment
    for (let i = this.rays.length - 1; i >= 0; i--) {
      const ln = this.rays[i];
      const seg = this.getRayPx(ln);
      if (!seg) continue;
      if (this.distToSegment(pt, seg.a, seg.b) <= threshold) {
        return { id: ln.id, mode: "move" };
      }
    }
    return null;
  }
}
