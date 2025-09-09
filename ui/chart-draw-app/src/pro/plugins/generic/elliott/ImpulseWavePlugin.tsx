/* eslint-disable @typescript-eslint/no-explicit-any */
import React from "react";
import { GenericPlugin } from "../GenericPlugin";
import { registerPlugin } from "../../PluginRegistry";

export type LineProps = { color: string; width: number; style: "solid" | "dashed" };

// Icon: zig-zag polyline
const impulseIcon = () =>
  React.createElement(
    "svg",
    { width: 16, height: 16, viewBox: "0 0 20 20" },
    React.createElement("polyline", {
      points: "2,16 6,8 10,12 14,4 18,10",
      stroke: "#333",
      fill: "none",
      strokeWidth: 2,
    })
  );

export class ImpulseWavePlugin extends GenericPlugin<LineProps> {
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

  // Catalog
  getToolGroup(): string {
    return "Elliott";
  }
  getToolSubgroup(): string | undefined {
    return "Waves";
  }
  getToolIcon(): () => any {
    return impulseIcon;
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

    // Labels 0..5
    const labels = ["0", "1", "2", "3", "4", "5"];
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

    // Live measurement overlays for Elliott 0-5 while placing points
    const n = pointsPx.length;
    if (n >= 2) {
      const last = pointsPx[n - 1];
      const p0 = pointsPx[0];
      const p1 = pointsPx[1];

      // Helper to format percent
      const pct = (v: number) => `${(v * 100).toFixed(1)}%`;

      if (n === 3) {
        // Selecting point #2 (index 2): retracement of 0-1 measured at current
        const base = Math.abs(p1.y - p0.y) || 1;
        const cur = pointsPx[2];
        const ret = Math.abs(cur.y - p1.y) / base;
        this.drawLabelPx(cur.x, cur.y, `Ret ${pct(ret)}`, { color: "#333", offsetX: 8, offsetY: -8 });
      } else if (n === 4) {
        // Selecting point #3 (index 3): extension of 0-1 from point #2
        const base = Math.abs(p1.y - p0.y) || 1;
        const p2 = pointsPx[2];
        const cur = pointsPx[3];
        const ext = Math.abs(cur.y - p2.y) / base;
        this.drawLabelPx(cur.x, cur.y, `Ext ${pct(ext)}`, { color: "#333", offsetX: 8, offsetY: -8 });
      } else if (n === 5) {
        // Selecting point #4 (index 4): retracement of 1-3 measured at current
        const p3 = pointsPx[3];
        const base13 = Math.abs(p3.y - p1.y) || 1;
        const cur = pointsPx[4];
        const ret = Math.abs(cur.y - p3.y) / base13;
        this.drawLabelPx(cur.x, cur.y, `Ret ${pct(ret)} of 1-3`, { color: "#333", offsetX: 8, offsetY: -8 });
      } else if (n >= 6) {
        // Selecting point #5 (index 5): extension of 1-3 from point #4
        const p3 = pointsPx[3];
        const p4 = pointsPx[4];
        const base13 = Math.abs(p3.y - p1.y) || 1;
        const cur = pointsPx[5];
        const ext = Math.abs(cur.y - p4.y) / base13;
        this.drawLabelPx(cur.x, cur.y, `Ext ${pct(ext)} of 1-3`, { color: "#333", offsetX: 8, offsetY: -8 });
      }
    }

    // If all points placed, render final shape
    if (pointsPx.length >= 6) {
      this.drawShapePx(pointsPx.slice(0, 6), { ...props, width: Math.max(1, props.width) }, false);
    }
  }
}

// Register
registerPlugin({
  key: "elliott-impulse",
  title: "Elliott Impulse 1-2-3-4-5",
  group: "Elliott",
  subgroup: "Waves",
  icon: impulseIcon,
  ctor: ImpulseWavePlugin,
});
