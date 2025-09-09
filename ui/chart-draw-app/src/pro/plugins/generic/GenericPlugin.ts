/* eslint-disable @typescript-eslint/no-explicit-any */
import type { Time } from "lightweight-charts";
import { BaseOverlayPlugin } from "../BaseOverlayPlugin";

export type ChartPoint = { time: Time; price: number };
export type DragMode = "none" | "move" | "anchor";
export type ShapeId = string;

export interface Shape<TProps = any> {
  id: ShapeId;
  points: ChartPoint[];
  props: TProps;
}

export interface GenericPluginConfig<TProps = any> {
  // How many points are required to complete a shape
  minPoints?: number; // default 2
  maxPoints?: number; // default 2
  // UI/UX knobs
  anchorRadiusPx?: number; // default 4
  hitTolerancePx?: number; // default 6
  showAnchorsWhenSelected?: boolean; // default true
  // Default visual props the tool will use when none provided
  defaultProps?: TProps;
}

// Optional catalog metadata that tools can provide for UI flyouts
export type ToolMeta = {
  key: string;
  title: string;
  group: string;
  subgroup?: string;
  icon: () => JSX.Element;
};

/**
 * GenericPlugin is a reusable, multi-point, overlay plugin base.
 * It centralizes:
 *  - Drawing lifecycle (start/cancel/preview/finalize)
 *  - Selection and hit-testing (anchors and segments)
 *  - Dragging (move whole shape or drag single anchor)
 *  - Rendering pipeline and anchor drawing
 *  - Persistence (export/import)
 *
 * Implementers typically only need to override drawShapePx to render their shapes.
 */
export abstract class GenericPlugin<
  TProps = { color: string; width: number; style: "solid" | "dashed" },
  TShape extends Shape<TProps> = Shape<TProps>
> extends BaseOverlayPlugin {
  protected shapes: TShape[] = [];
  protected selectedId: ShapeId | null = null;

  // Drawing state
  protected drawing = false;
  protected drawingPtsPx: Array<{ x: number; y: number }> = [];
  protected mousePx: { x: number; y: number } | null = null;

  // Dragging state
  protected dragMode: DragMode = "none";
  protected dragStartPx: { x: number; y: number } | null = null;
  protected originalShapePx: { pointsPx: Array<{ x: number; y: number }>; points: ChartPoint[] } | null = null;

  protected readonly cfg: Required<GenericPluginConfig<TProps>>;

  private uid = () => Math.random().toString(36).slice(2, 10);

  constructor(params: { chart: any; series: any; container: HTMLElement }, config?: GenericPluginConfig<TProps>) {
    super(params);
    this.cfg = {
      minPoints: config?.minPoints ?? 2,
      maxPoints: config?.maxPoints ?? 2,
      anchorRadiusPx: config?.anchorRadiusPx ?? 4,
      hitTolerancePx: config?.hitTolerancePx ?? 6,
      defaultProps: (config?.defaultProps ??
        ({ color: "#1976d2", width: 2, style: "solid" } as unknown as TProps)),
      showAnchorsWhenSelected: config?.showAnchorsWhenSelected ?? true,
    };

    this.attachEvents();

    // Start inactive; wrapper generally controls activation/selection
    this.setActive(false);
    this.canvas.style.pointerEvents = "none";

    this.render();
  }

  // ------------------- Public API -------------------

  startDrawing() {
    this.drawing = true;
    this.drawingPtsPx = [];
    this.mousePx = null;
    this.clearSelection();
    this.setActive(true);
    this.canvas.style.pointerEvents = "auto";
    this.canvas.style.cursor = "crosshair";
    this.render();
  }

  // Generic UI alias
  start() {
    this.startDrawing();
  }

  cancelDrawing() {
    if (!this.drawing) return;
    this.drawing = false;
    this.drawingPtsPx = [];
    this.mousePx = null;
    this.canvas.style.pointerEvents = this.selectedId ? "auto" : "none";
    this.canvas.style.cursor = "default";
    this.render();
  }

  // Generic UI alias
  cancel() {
    this.cancelDrawing();
  }

  hasSelection(): boolean {
    return !!this.selectedId;
  }

  clearSelection() {
    this.selectedId = null;
    this.dragMode = "none";
       this.dragStartPx = null;
    this.originalShapePx = null;
    (this as any)._tempAnchorIndex = undefined;
    this.canvas.style.pointerEvents = "none";
    this.setActive(false);
    this.render();
  }

  trySelectAt(clientX: number, clientY: number): boolean {
    const rect = this.canvas.getBoundingClientRect();
    const pt = { x: clientX - rect.left, y: clientY - rect.top };
    const hit = this.hitTest(pt);
    if (hit) {
      this.selectedId = hit.id;
      this.canvas.style.pointerEvents = "auto";
      this.setActive(true);
      this.render();
      return true;
    }
    return false;
  }

  deleteSelected(): boolean {
    if (!this.selectedId) return false;
    const idx = this.shapes.findIndex((s) => s.id === this.selectedId);
    if (idx >= 0) {
      this.shapes.splice(idx, 1);
      this.clearSelection();
      this.render();
      return true;
    }
    return false;
  }

  getAll(): ReadonlyArray<TShape> {
    return [...this.shapes];
  }

  exportAll(): Array<{ points: ChartPoint[]; props: TProps }> {
    return this.shapes.map((s) => ({
      points: s.points.map((p) => ({ time: p.time, price: p.price })),
      props: { ...(s.props as any) },
    }));
  }

  importAll(items: Array<{ points: ChartPoint[]; props?: TProps }>) {
    this.shapes.splice(0, this.shapes.length);
    const normalizeTime = (t: any) => {
      if (typeof t === "number" && t > 1e12) return Math.floor(t / 1000);
      return t;
    };
    for (const it of items || []) {
      const id = this.uid();
      const pts = (it.points || []).map((p) => ({ time: normalizeTime(p.time) as Time, price: p.price }));
      const props = (it.props ?? this.cfg.defaultProps) as TProps;
      this.shapes.push({ id, points: pts, props } as TShape);
    }
    this.render();
  }

  destroy() {
    try {
      this.detachEvents();
    } catch {}
    super.destroy();
  }

  // ------------- Catalog metadata -------------

  /**
   * Returns the UI group for this tool (for flyout organization).
   * Defaults to reading static meta on the constructor or "Misc".
   */
  getToolGroup(): string {
    const ctor: any = this.constructor as any;
    return ctor?.meta?.group ?? "Misc";
  }

  /**
   * Returns optional subgroup for this tool.
   */
  getToolSubgroup(): string | undefined {
    const ctor: any = this.constructor as any;
    return ctor?.meta?.subgroup;
  }

  /**
   * Returns the icon renderer for this tool (used in flyout).
   * Subclasses should either override this or set a static meta.icon.
   */
  getToolIcon(): () => any {
    const ctor: any = this.constructor as any;
    return ctor?.meta?.icon ?? (() => null);
  }

  /**
   * Optional convenience getters for key/title if subclasses define static meta.
   */
  getToolKey(): string | undefined {
    const ctor: any = this.constructor as any;
    return ctor?.meta?.key;
  }

  getToolTitle(): string | undefined {
    const ctor: any = this.constructor as any;
    return ctor?.meta?.title;
  }

  // ------------------- Rendering -------------------

  render() {
    this.clearCanvas();

    // Persisted shapes
    for (const s of this.shapes) {
      const isSel = s.id === this.selectedId;
      const ptsPx = this.toPxPoints(s.points);
      this.drawShapePx(ptsPx, s.props, isSel);
      if (isSel && this.cfg.showAnchorsWhenSelected) {
        for (const p of ptsPx) this.drawAnchorPx(p.x, p.y);
      }
    }

    // In-progress preview
    if (this.drawing && this.drawingPtsPx.length > 0) {
      const previewPts = [...this.drawingPtsPx];
      if (this.mousePx) previewPts.push(this.mousePx);
      this.drawPreviewPx(previewPts, this.cfg.defaultProps);
    }
  }

  /**
   * Required: draw the shape using pixel-space points and props.
   * Implementers can draw lines, polygons, labels, etc.
   */
  protected abstract drawShapePx(
    pointsPx: Array<{ x: number; y: number }>,
    props: TProps,
    selected: boolean
  ): void;

  /**
   * Optional: draw preview while placing points.
   * Defaults to delegating to drawShapePx.
   */
  protected drawPreviewPx(pointsPx: Array<{ x: number; y: number }>, props: TProps) {
    this.drawShapePx(pointsPx, props, false);
  }

  /**
   * Optional: override to customize hit testing.
   * Default behavior:
   *  - Anchors (points) have priority over segments
   *  - Segments are any consecutive pair of points
   */
  protected hitTest(pt: { x: number; y: number }): { id: ShapeId; mode: DragMode; anchorIndex?: number } | null {
    // anchors first (search from top-most)
    for (let i = this.shapes.length - 1; i >= 0; i--) {
      const s = this.shapes[i];
      const pts = this.toPxPoints(s.points);
      for (let a = 0; a < pts.length; a++) {
        if (this.dist(pt, pts[a]) <= this.cfg.anchorRadiusPx + 2) {
          return { id: s.id, mode: "anchor", anchorIndex: a };
        }
      }
    }
    // segments next
    for (let i = this.shapes.length - 1; i >= 0; i--) {
      const s = this.shapes[i];
      const pts = this.toPxPoints(s.points);
      for (let j = 0; j < pts.length - 1; j++) {
        if (this.distToSegment(pt, pts[j], pts[j + 1]) <= this.cfg.hitTolerancePx) {
          return { id: s.id, mode: "move" };
        }
      }
    }
    return null;
  }

  // ------------------- Events -------------------

  private handleMove = (e: MouseEvent) => {
    const pt = this.toLocal(e);

    if (this.drawing) {
      this.mousePx = pt;
      this.render();
      return;
    }

    if (this.dragMode !== "none" && this.selectedId && this.dragStartPx && this.originalShapePx) {
      const dx = pt.x - this.dragStartPx.x;
      const dy = pt.y - this.dragStartPx.y;
      const idx = this.shapes.findIndex((s) => s.id === this.selectedId);
      if (idx >= 0) {
        const s = this.shapes[idx];
        const toPoint = (px: { x: number; y: number }) => {
          const t = this.xToTime(px.x);
          const p = this.yToPrice(px.y);
          return t != null && p != null ? ({ time: t, price: p } as ChartPoint) : null;
        };

        if (this.dragMode === "move") {
          const newPts: ChartPoint[] = [];
          for (let i = 0; i < this.originalShapePx.pointsPx.length; i++) {
            const px = this.originalShapePx.pointsPx[i];
            const np = toPoint({ x: px.x + dx, y: px.y + dy });
            if (np) newPts.push(np);
          }
          if (newPts.length === this.originalShapePx.pointsPx.length) s.points = newPts;
        } else if (this.dragMode === "anchor") {
          const anchorIdx = (this as any)._tempAnchorIndex as number | undefined;
          if (anchorIdx != null) {
            const origPx = this.originalShapePx.pointsPx[anchorIdx];
            const np = toPoint({ x: origPx.x + dx, y: origPx.y + dy });
            if (np) s.points[anchorIdx] = np;
          }
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
      // Ensure nothing else in the page consumes this click
      try {
        e.preventDefault();
        e.stopPropagation();
      } catch {}
      // Record the point and initialize preview anchor
      this.drawingPtsPx.push(pt);
      this.mousePx = pt;
      const required = this.cfg.maxPoints;
      if (this.drawingPtsPx.length >= required) {
        this.finalizeDrawing();
      } else {
        this.render();
      }
      return;
    }

    const hit = this.hitTest(pt);
    if (hit) {
      this.selectedId = hit.id;
      this.dragMode = hit.mode;
      this.dragStartPx = pt;
      const s = this.shapes.find((sh) => sh.id === this.selectedId)!;
      const ptsPx = this.toPxPoints(s.points);
      this.originalShapePx = { pointsPx: ptsPx.map((p) => ({ ...p })), points: s.points.map((p) => ({ ...p })) };
      if (hit.anchorIndex != null) (this as any)._tempAnchorIndex = hit.anchorIndex;
      this.render();
    } else {
      this.clearSelection();
    }
  };

  private handleUp = () => {
    if (this.dragMode !== "none") {
      this.dragMode = "none";
      this.dragStartPx = null;
      this.originalShapePx = null;
      (this as any)._tempAnchorIndex = undefined;
      this.render();
    }
  };

  private handleDoubleClick = (e: MouseEvent) => {
    if (!this.drawing) return;
    e.preventDefault();
    e.stopPropagation();
    if (this.drawingPtsPx.length >= this.cfg.minPoints) {
      this.finalizeDrawing();
    } else {
      this.cancelDrawing();
    }
  };

  // ------------------- Helpers -------------------

  protected finalizeDrawing() {
    const pts = this.drawingPtsPx.map((p) => this.pxToPoint(p)).filter(Boolean) as ChartPoint[];
    if (pts.length === this.drawingPtsPx.length && pts.length >= this.cfg.minPoints) {
      const id = this.uid();
      const shape = { id, points: pts, props: this.cloneProps(this.cfg.defaultProps) } as TShape;
      this.shapes.push(shape);
      this.selectedId = id;
    }
    this.cancelDrawing();
  }

  protected drawAnchorPx(x: number, y: number) {
    const ctx = this.ctx;
    ctx.save();
    ctx.fillStyle = "#1976d2";
    ctx.strokeStyle = "#ffffff";
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.arc(x, y, this.cfg.anchorRadiusPx, 0, Math.PI * 2);
    ctx.fill();
    ctx.stroke();
    ctx.restore();
  }

  /**
   * Draw a small label near a point in pixel space.
   */
  protected drawLabelPx(
    x: number,
    y: number,
    text: string,
    opts?: { color?: string; bg?: string; font?: string; paddingX?: number; paddingY?: number; offsetX?: number; offsetY?: number }
  ) {
    const ctx = this.ctx;
    const color = opts?.color ?? "#333333";
    const bg = opts?.bg ?? "rgba(255,255,255,0.85)";
    const font = opts?.font ?? "12px system-ui, -apple-system, Segoe UI, Roboto, Helvetica, Arial, sans-serif";
    const paddingX = opts?.paddingX ?? 4;
    const paddingY = opts?.paddingY ?? 2;
    const offsetX = opts?.offsetX ?? 8;
    const offsetY = opts?.offsetY ?? -8;

    ctx.save();
    ctx.font = font;
    const metrics = ctx.measureText(String(text));
    const w = Math.ceil(metrics.width) + paddingX * 2;
    const h = 14 + paddingY * 2; // approximate height for 12px font

    const rx = x + offsetX;
    const ry = y + offsetY - h;

    // background
    ctx.fillStyle = bg;
    ctx.fillRect(rx, ry, w, h);

    // border
    ctx.strokeStyle = "rgba(0,0,0,0.15)";
    ctx.lineWidth = 1;
    ctx.strokeRect(rx + 0.5, ry + 0.5, w - 1, h - 1);

    // text
    ctx.fillStyle = color;
    ctx.textBaseline = "top";
    ctx.fillText(String(text), rx + paddingX, ry + paddingY);
    ctx.restore();
  }

  protected toPxPoints(points: ChartPoint[]): Array<{ x: number; y: number }> {
    const res: Array<{ x: number; y: number }> = [];
    for (const p of points) {
      const x = this.timeToX(p.time);
      const y = this.priceToY(p.price);
      if (x != null && y != null) res.push({ x, y });
    }
    return res;
  }

  protected pxToPoint(px: { x: number; y: number }): ChartPoint | null {
    const t = this.xToTime(px.x);
    const p = this.yToPrice(px.y);
    return t != null && p != null ? ({ time: t, price: p } as ChartPoint) : null;
  }

  protected dist(a: { x: number; y: number }, b: { x: number; y: number }) {
    const dx = a.x - b.x;
    const dy = a.y - b.y;
    return Math.hypot(dx, dy);
  }

  protected distToSegment(p: { x: number; y: number }, a: { x: number; y: number }, b: { x: number; y: number }) {
    const l2 = (a.x - b.x) ** 2 + (a.y - b.y) ** 2;
    if (l2 === 0) return this.dist(p, a);
    let t = ((p.x - a.x) * (b.x - a.x) + (p.y - a.y) * (b.y - a.y)) / l2;
    t = Math.max(0, Math.min(1, t));
    const proj = { x: a.x + t * (b.x - a.x), y: a.y + t * (b.y - a.y) };
    return this.dist(p, proj);
  }

  protected cloneProps(props: TProps): TProps {
    return JSON.parse(JSON.stringify(props));
  }

  private attachEvents() {
    this.canvas.addEventListener("mousemove", this.handleMove);
    this.canvas.addEventListener("mousedown", this.handleDown);
    this.canvas.addEventListener("mouseup", this.handleUp);
    this.canvas.addEventListener("dblclick", this.handleDoubleClick);
    window.addEventListener("mouseup", this.handleUp);
  }

  private detachEvents() {
    this.canvas.removeEventListener("mousemove", this.handleMove);
    this.canvas.removeEventListener("mousedown", this.handleDown);
    this.canvas.removeEventListener("mouseup", this.handleUp);
    this.canvas.removeEventListener("dblclick", this.handleDoubleClick);
    window.removeEventListener("mouseup", this.handleUp);
  }
}
