/* eslint-disable @typescript-eslint/no-explicit-any */
import React from "react";
import { GenericPlugin } from "./GenericPlugin";
import { registerPlugin } from "../PluginRegistry";

export type LineProps = { color: string; width: number; style: "solid" | "dashed" };

// Reusable icon for registry and instance method
const lineIcon = () =>
  React.createElement(
    "svg",
    { width: 16, height: 16, viewBox: "0 0 20 20" },
    React.createElement("polyline", {
      points: "2,16 7,10 12,14 18,4",
      stroke: "#333",
      fill: "none",
      strokeWidth: 2,
    })
  );

export class MultiPointLinePlugin extends GenericPlugin<LineProps> {
  constructor(params: { chart: any; series: any; container: HTMLElement }) {
    super(params, {
      minPoints: 2,
      maxPoints: Number.POSITIVE_INFINITY,
      anchorRadiusPx: 4,
      hitTolerancePx: 6,
      showAnchorsWhenSelected: true,
      defaultProps: { color: "#1976d2", width: 2, style: "solid" },
    });
  }

  // ---- Catalog metadata (used by flyout) ----
  getToolGroup(): string {
    return "Lines";
  }
  getToolSubgroup(): string | undefined {
    return "Generic";
  }
  getToolIcon(): () => any {
    return lineIcon;
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
  }

  protected drawPreviewPx(pointsPx: Array<{ x: number; y: number }>, props: LineProps) {
    // If only one point so far, draw an anchor at that point for immediate feedback
    if (pointsPx.length === 1) {
      const p = pointsPx[0];
      const ctx = this.ctx;
      ctx.save();
      ctx.fillStyle = props.color;
      ctx.strokeStyle = "#ffffff";
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.arc(p.x, p.y, 3, 0, Math.PI * 2);
      ctx.fill();
      ctx.stroke();
      ctx.restore();
      return;
    }
    const previewProps: LineProps = {
      color: props.color,
      width: Math.max(1, props.width),
      style: props.style,
    };
    this.drawShapePx(pointsPx, previewProps, false);
  }
}

// Self-register with the plugin registry so ProApp can list it in the flyout
registerPlugin({
  key: "multi-point-line",
  title: "Multi‑Point Line",
  group: "Lines",
  subgroup: "Generic",
  icon: lineIcon,
  ctor: MultiPointLinePlugin,
});
