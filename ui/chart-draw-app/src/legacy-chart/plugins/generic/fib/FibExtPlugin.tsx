/* eslint-disable @typescript-eslint/no-explicit-any */
import React from "react";
import { GenericPlugin, type ShapeId, type DragMode } from "../GenericPlugin";
import { registerPlugin } from "../../PluginRegistry";

export type LineProps = { color: string; width: number; style: "solid" | "dashed" };

// Icon: extended horizontal lines
const fibExtIcon = () =>
  React.createElement(
    "svg",
    { width: 16, height: 16, viewBox: "0 0 20 20" },
    React.createElement("path", { d: "M2 5 L18 5", stroke: "#333", strokeWidth: 2, fill: "none" }),
    React.createElement("path", { d: "M2 10 L18 10", stroke: "#333", strokeWidth: 2, fill: "none" }),
    React.createElement("path", { d: "M2 15 L18 15", stroke: "#333", strokeWidth: 2, fill: "none" }),
  );

export class FibExtPlugin extends GenericPlugin<LineProps> {
  // include base and extension levels (beyond 100%)
  private readonly levels: number[] = [0, 1, 1.272, 1.414, 1.618, 2.0];

  constructor(params: { chart: any; series: any; container: HTMLElement }) {
    super(params, {
      minPoints: 3,
      maxPoints: 3,
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
    return fibExtIcon;
  }

  protected drawShapePx(pointsPx: Array<{ x: number; y: number }>, props: LineProps, selected: boolean): void {
    if (pointsPx.length < 3) return;
    const [a, b, c] = pointsPx;
    const y1 = a.y;
    const y2 = b.y;
    const y3 = c.y;
    const delta = y2 - y1;
    const xStart = Math.min(a.x, c.x);
    const xEnd = Math.max(a.x, c.x);
    const ctx = this.ctx;

    for (const lvl of this.levels) {
      const y = y3 + delta * lvl;
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

      const price = this.yToPrice(y);
      const label = `${(lvl * 100).toFixed(1)}% ${price != null ? Number(price).toFixed(2) : ""}`;
      this.drawLabelPx(xEnd, y, label, {
        offsetX: -4,
        offsetY: 0,
        color: "#333",
      });
    }
  }

  protected drawPreviewPx(pointsPx: Array<{ x: number; y: number }>, props: LineProps) {
    const ctx = this.ctx;

    // Dashed baseline through placed points (first->second->third or towards mouse)
    if (pointsPx.length >= 1) {
      ctx.save();
      ctx.strokeStyle = props.color;
      ctx.lineWidth = Math.max(1, props.width);
      ctx.setLineDash([4, 4]);
      ctx.beginPath();
      ctx.moveTo(pointsPx[0].x + 0.5, pointsPx[0].y + 0.5);
      for (let i = 1; i < pointsPx.length; i++) {
        ctx.lineTo(pointsPx[i].x + 0.5, pointsPx[i].y + 0.5);
      }
      ctx.stroke();
      ctx.setLineDash([]);
      ctx.restore();
    }

    // When we have all 3 points, render levels
    if (pointsPx.length >= 3) {
      this.drawShapePx(pointsPx.slice(0, 3), { ...props, width: Math.max(1, props.width) }, false);
    }
  }

  // Hit-test any horizontal extension level to allow selection/move from any line
  protected hitTest(pt: { x: number; y: number }): { id: ShapeId; mode: DragMode; anchorIndex?: number } | null {
    // Anchors first
    for (let i = this.shapes.length - 1; i >= 0; i--) {
      const s = this.shapes[i];
      const pts = this.toPxPoints(s.points);
      for (let a = 0; a < pts.length; a++) {
        if (this.dist(pt, pts[a]) <= this.cfg.anchorRadiusPx + 2) {
          return { id: s.id, mode: "anchor", anchorIndex: a };
        }
      }
    }
    // Levels within x span from first to third point
    for (let i = this.shapes.length - 1; i >= 0; i--) {
      const s = this.shapes[i];
      const pts = this.toPxPoints(s.points);
      if (pts.length < 3) continue;
      const xStart = Math.min(pts[0].x, pts[2].x);
      const xEnd = Math.max(pts[0].x, pts[2].x);
      if (pt.x < xStart - this.cfg.hitTolerancePx || pt.x > xEnd + this.cfg.hitTolerancePx) continue;

      const y1 = pts[0].y;
      const y2 = pts[1].y;
      const y3 = pts[2].y;
      const delta = y2 - y1;
      for (const lvl of this.levels) {
        const y = y3 + delta * lvl;
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
  key: "fib-ext",
  title: "Fibonacci Extensions",
  group: "Fibonacci",
  subgroup: "Generic",
  icon: fibExtIcon,
  ctor: FibExtPlugin,
});
