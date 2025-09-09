/* eslint-disable @typescript-eslint/no-explicit-any */
import React from "react";
import { GenericPlugin, type ShapeId, type DragMode } from "../GenericPlugin";
import { registerPlugin } from "../../PluginRegistry";

export type LineProps = { color: string; width: number; style: "solid" | "dashed" };

// Icon: stacked horizontal lines
const fibIcon = () =>
  React.createElement(
    "svg",
    { width: 16, height: 16, viewBox: "0 0 20 20" },
    React.createElement("path", { d: "M3 4 L17 4", stroke: "#333", strokeWidth: 2, fill: "none" }),
    React.createElement("path", { d: "M3 8 L17 8", stroke: "#333", strokeWidth: 2, fill: "none" }),
    React.createElement("path", { d: "M3 12 L17 12", stroke: "#333", strokeWidth: 2, fill: "none" }),
    React.createElement("path", { d: "M3 16 L17 16", stroke: "#333", strokeWidth: 2, fill: "none" }),
  );

export class FibPlugin extends GenericPlugin<LineProps> {
  private readonly levels: number[] = [0, 0.236, 0.382, 0.5, 0.618, 0.786, 1];

  constructor(params: { chart: any; series: any; container: HTMLElement }) {
    super(params, {
      minPoints: 2,
      maxPoints: 2,
      anchorRadiusPx: 4,
      hitTolerancePx: 6,
      showAnchorsWhenSelected: true,
      defaultProps: { color: "#1976d2", width: 1, style: "solid" },
    });
  }

  // Catalog metadata
  getToolGroup(): string {
    return "Fibonacci";
  }
  getToolSubgroup(): string | undefined {
    return "Generic";
  }
  getToolIcon(): () => any {
    return fibIcon;
  }

  protected drawShapePx(pointsPx: Array<{ x: number; y: number }>, props: LineProps, selected: boolean): void {
    if (pointsPx.length < 2) return;
    const [a, b] = pointsPx;
    const y1 = a.y;
    const y2 = b.y;
    const xStart = Math.min(a.x, b.x);
    const xEnd = Math.max(a.x, b.x);
    const ctx = this.ctx;

    for (const lvl of this.levels) {
      const y = y1 + (y2 - y1) * lvl;
      ctx.save();
      ctx.strokeStyle = props.color;
      ctx.lineWidth = Math.max(1, props.width);
      if (props.style === "dashed") ctx.setLineDash([6, 4]);

      ctx.beginPath();
      ctx.moveTo(xStart + 0.5, y + 0.5);
      ctx.lineTo(xEnd - 0.5, y + 0.5);
      ctx.stroke();
      ctx.setLineDash([]);
      ctx.restore();

      // Label near xEnd: percent + price
      const price = this.yToPrice(y);
      const label = `${(lvl * 100).toFixed(1)}% ${price != null ? Number(price).toFixed(2) : ""}`;
      this.drawLabelPx(xEnd, y, label, {
        offsetX: -4, // draw slightly inside the segment
        offsetY: 0,
        color: "#333",
      });
    }
  }

  protected drawPreviewPx(pointsPx: Array<{ x: number; y: number }>, props: LineProps) {
    const ctx = this.ctx;

    // Always draw a dotted baseline between first and current point(s)
    if (pointsPx.length >= 1) {
      ctx.save();
      ctx.strokeStyle = props.color;
      ctx.lineWidth = Math.max(1, props.width);
      ctx.setLineDash([4, 4]);
      const a = pointsPx[0];
      const b = pointsPx[pointsPx.length - 1];
      ctx.beginPath();
      ctx.moveTo(a.x + 0.5, a.y + 0.5);
      ctx.lineTo(b.x + 0.5, b.y + 0.5);
      ctx.stroke();
      ctx.setLineDash([]);
      ctx.restore();
    }

    // When we have the required two points, also render the levels
    if (pointsPx.length >= 2) {
      this.drawShapePx(pointsPx.slice(0, 2), { ...props, width: Math.max(1, props.width) }, false);
    }
  }

  // Hit-test any horizontal level to allow selection/move from any line
  protected hitTest(pt: { x: number; y: number }): { id: ShapeId; mode: DragMode; anchorIndex?: number } | null {
    // First, anchors (reuse base behavior)
    for (let i = this.shapes.length - 1; i >= 0; i--) {
      const s = this.shapes[i];
      const pts = this.toPxPoints(s.points);
      for (let a = 0; a < pts.length; a++) {
        if (this.dist(pt, pts[a]) <= this.cfg.anchorRadiusPx + 2) {
          return { id: s.id, mode: "anchor", anchorIndex: a };
        }
      }
    }
    // Next, any level line within the x span of the tool
    for (let i = this.shapes.length - 1; i >= 0; i--) {
      const s = this.shapes[i];
      const pts = this.toPxPoints(s.points);
      if (pts.length < 2) continue;
      const xStart = Math.min(pts[0].x, pts[1].x);
      const xEnd = Math.max(pts[0].x, pts[1].x);
      if (pt.x < xStart - this.cfg.hitTolerancePx || pt.x > xEnd + this.cfg.hitTolerancePx) continue;
      const y1 = pts[0].y;
      const y2 = pts[1].y;
      for (const lvl of this.levels) {
        const y = y1 + (y2 - y1) * lvl;
        if (Math.abs(pt.y - y) <= this.cfg.hitTolerancePx) {
          return { id: s.id, mode: "move" };
        }
      }
    }
    return null;
  }
}

// Register with registry
registerPlugin({
  key: "fib",
  title: "Fibonacci Retracement",
  group: "Fibonacci",
  subgroup: "Generic",
  icon: fibIcon,
  ctor: FibPlugin,
});
