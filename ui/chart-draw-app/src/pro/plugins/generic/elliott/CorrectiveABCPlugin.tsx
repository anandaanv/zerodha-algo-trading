/* eslint-disable @typescript-eslint/no-explicit-any */
import React from "react";
import { GenericPlugin } from "../GenericPlugin";
import { registerPlugin } from "../../PluginRegistry";

export type LineProps = { color: string; width: number; style: "solid" | "dashed" };

// Icon: simple V/W like
const abcIcon = () =>
  React.createElement(
    "svg",
    { width: 16, height: 16, viewBox: "0 0 20 20" },
    React.createElement("polyline", {
      points: "2,12 10,6 18,12",
      stroke: "#333",
      fill: "none",
      strokeWidth: 2,
    })
  );

export class CorrectiveABCPlugin extends GenericPlugin<LineProps> {
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

  // Catalog
  getToolGroup(): string {
    return "Elliott";
  }
  getToolSubgroup(): string | undefined {
    return "Waves";
  }
  getToolIcon(): () => any {
    return abcIcon;
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

    // Labels A,B,C
    const labels = ["A", "B", "C"];
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

    if (pointsPx.length >= 3) {
      this.drawShapePx(pointsPx.slice(0, 3), { ...props, width: Math.max(1, props.width) }, false);
    }
  }
}

// Register
registerPlugin({
  key: "elliott-abc",
  title: "Elliott ABC",
  group: "Elliott",
  subgroup: "Waves",
  icon: abcIcon,
  ctor: CorrectiveABCPlugin,
});
