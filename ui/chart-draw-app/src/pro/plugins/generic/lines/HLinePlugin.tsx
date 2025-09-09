/* eslint-disable @typescript-eslint/no-explicit-any */
import React from "react";
import { GenericPlugin, type ChartPoint, type ShapeId, type DragMode } from "../GenericPlugin";
import { registerPlugin } from "../../PluginRegistry";

export type LineProps = { color: string; width: number; style: "solid" | "dashed" };

// Icon: horizontal line
const hlineIcon = () =>
  React.createElement(
    "svg",
    { width: 16, height: 16, viewBox: "0 0 20 20" },
    React.createElement("path", {
      d: "M2 10 L18 10",
      stroke: "#333",
      fill: "none",
      strokeWidth: 2,
    })
  );

export class HLinePlugin extends GenericPlugin<LineProps> {
  constructor(params: { chart: any; series: any; container: HTMLElement }) {
    super(params, {
      minPoints: 1,
      maxPoints: 1,
      anchorRadiusPx: 4,
      hitTolerancePx: 6,
      showAnchorsWhenSelected: true,
      defaultProps: { color: "#1976d2", width: 1, style: "solid" },
    });
  }

  // Catalog metadata
  getToolGroup(): string {
    return "Lines";
  }
  getToolSubgroup(): string | undefined {
    return "Generic";
  }
  getToolIcon(): () => any {
    return hlineIcon;
  }

  protected drawShapePx(pointsPx: Array<{ x: number; y: number }>, props: LineProps, selected: boolean): void {
    if (pointsPx.length < 1) return;
    const y = pointsPx[0].y;
    const ctx = this.ctx;
    ctx.save();
    ctx.strokeStyle = props.color;
    ctx.lineWidth = Math.max(1, props.width);
    if (props.style === "dashed") ctx.setLineDash([6, 4]);

    // Draw across entire canvas width
    ctx.beginPath();
    ctx.moveTo(0.5, y + 0.5);
    ctx.lineTo(this.canvas.width - 0.5, y + 0.5);
    ctx.stroke();
    ctx.setLineDash([]);
    ctx.restore();
  }

  protected drawPreviewPx(pointsPx: Array<{ x: number; y: number }>, props: LineProps) {
    this.drawShapePx(pointsPx, { ...props, width: Math.max(1, props.width) }, false);
  }

  // Custom hit test so user can drag anywhere near the horizontal line
  protected hitTest(pt: { x: number; y: number }): { id: ShapeId; mode: DragMode; anchorIndex?: number } | null {
    // First, anchor hit on the single point (re-use default behavior)
    if (this.shapes.length) {
      const s = this.shapes[this.shapes.length - 1];
      const ptsPx = this.toPxPoints(s.points);
      if (ptsPx[0] && this.dist(pt, ptsPx[0]) <= this.cfg.anchorRadiusPx + 2) {
        return { id: s.id, mode: "anchor", anchorIndex: 0 };
      }
    }
    // Then, near the horizontal line (any x; y within tolerance)
    for (let i = this.shapes.length - 1; i >= 0; i--) {
      const s = this.shapes[i];
      const ptsPx = this.toPxPoints(s.points);
      if (!ptsPx[0]) continue;
      const y = ptsPx[0].y;
      if (Math.abs(pt.y - y) <= this.cfg.hitTolerancePx) {
        return { id: s.id, mode: "move" };
      }
    }
    return null;
  }
}

// Register with registry
registerPlugin({
  key: "hline",
  title: "Horizontal Line",
  group: "Lines",
  subgroup: "Generic",
  icon: hlineIcon,
  ctor: HLinePlugin,
});
