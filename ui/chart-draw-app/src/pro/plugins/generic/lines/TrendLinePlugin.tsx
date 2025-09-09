/* eslint-disable @typescript-eslint/no-explicit-any */
import React from "react";
import { GenericPlugin } from "../GenericPlugin";
import { registerPlugin } from "../../PluginRegistry";

export type LineProps = { color: string; width: number; style: "solid" | "dashed" };

// Simple diagonal line icon
const trendIcon = () =>
  React.createElement(
    "svg",
    { width: 16, height: 16, viewBox: "0 0 20 20" },
    React.createElement("path", {
      d: "M3 17 L17 3",
      stroke: "#333",
      fill: "none",
      strokeWidth: 2,
    })
  );

export class TrendLinePlugin extends GenericPlugin<LineProps> {
  constructor(params: { chart: any; series: any; container: HTMLElement }) {
    super(params, {
      minPoints: 2,
      maxPoints: 2,
      anchorRadiusPx: 4,
      hitTolerancePx: 6,
      showAnchorsWhenSelected: true,
      defaultProps: { color: "#1976d2", width: 2, style: "solid" },
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
    return trendIcon;
  }

  protected drawShapePx(pointsPx: Array<{ x: number; y: number }>, props: LineProps, selected: boolean): void {
    if (pointsPx.length < 2) return;
    const [a, b] = pointsPx;
    const ctx = this.ctx;
    ctx.save();
    ctx.strokeStyle = props.color;
    ctx.lineWidth = Math.max(1, props.width);
    if (props.style === "dashed") ctx.setLineDash([6, 4]);
    ctx.beginPath();
    ctx.moveTo(a.x + 0.5, a.y + 0.5);
    ctx.lineTo(b.x + 0.5, b.y + 0.5);
    ctx.stroke();
    ctx.setLineDash([]);
    ctx.restore();
  }

  protected drawPreviewPx(pointsPx: Array<{ x: number; y: number }>, props: LineProps) {
    this.drawShapePx(pointsPx, { ...props, width: Math.max(1, props.width) }, false);
  }
}

// Register with registry
registerPlugin({
  key: "trendline",
  title: "Trend Line",
  group: "Lines",
  subgroup: "Generic",
  icon: trendIcon,
  ctor: TrendLinePlugin,
});
