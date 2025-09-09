/* eslint-disable @typescript-eslint/no-explicit-any */
import React from "react";
import { GenericPlugin } from "../GenericPlugin";
import { registerPlugin } from "../../PluginRegistry";

export type LineProps = { color: string; width: number; style: "solid" | "dashed" };

// Icon: multi-zig-zag polyline
const wxyxzIcon = () =>
  React.createElement(
    "svg",
    { width: 16, height: 16, viewBox: "0 0 20 20" },
    React.createElement("polyline", {
      points: "2,14 6,6 10,10 14,4 18,12",
      stroke: "#333",
      fill: "none",
      strokeWidth: 2,
    })
  );

export class WXYXZPlugin extends GenericPlugin<LineProps> {
  constructor(params: { chart: any; series: any; container: HTMLElement }) {
    super(params, {
      minPoints: 6,
      maxPoints: 6,
      anchorRadiusPx: 4,
      hitTolerancePx: 6,
      showAnchorsWhenSelected: true,
      defaultProps: { color: "#1976d2", width: 1, style: "solid" },
    });
  }

  // Catalog metadata
  getToolGroup(): string {
    return "Elliott";
  }
  getToolSubgroup(): string | undefined {
    return "Waves";
  }
  getToolIcon(): () => any {
    return wxyxzIcon;
  }

  protected drawShapePx(pointsPx: Array<{ x: number; y: number }>, props: LineProps, selected: boolean): void {
    if (pointsPx.length < 2) return;
    const ctx = this.ctx;
    ctx.save();
    ctx.strokeStyle = props.color;
    ctx.lineWidth = Math.max(1, props.width);
    if (props.style === "dashed") ctx.setLineDash([6, 4]);

    ctx.beginPath();
    ctx.moveTo(pointsPx[0].x + 0.5, pointsPx[0].y + 0.5);
    for (let i = 1; i < pointsPx.length; i++) {
      ctx.lineTo(pointsPx[i].x + 0.5, pointsPx[i].y + 0.5);
    }
    ctx.stroke();
    ctx.setLineDash([]);
    ctx.restore();

    // Labels 0, W, X, Y, X, Z
    const labels = ["0", "W", "X", "Y", "X", "Z"];
    for (let i = 0; i < Math.min(pointsPx.length, labels.length); i++) {
      const p = pointsPx[i];
      this.drawLabelPx(p.x, p.y, labels[i], {
        color: "#333",
        offsetX: 6,
        offsetY: -6,
      });
    }
  }

  protected drawPreviewPx(pointsPx: Array<{ x: number; y: number }>, props: LineProps) {
    // Dashed preview polyline for in-progress points
    const ctx = this.ctx;
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

    // When all points placed, render final
    if (pointsPx.length >= 6) {
      this.drawShapePx(pointsPx.slice(0, 6), { ...props, width: Math.max(1, props.width) }, false);
    }
  }
}

// Register
registerPlugin({
  key: "elliott-wxyxz",
  title: "Elliott 0-W-X-Y-X-Z",
  group: "Elliott",
  subgroup: "Waves",
  icon: wxyxzIcon,
  ctor: WXYXZPlugin,
});
