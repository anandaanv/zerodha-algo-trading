/* eslint-disable @typescript-eslint/no-explicit-any */
import React from "react";
import { GenericPlugin, type ShapeId, type DragMode } from "../GenericPlugin";
import { registerPlugin } from "../../PluginRegistry";

export type LineProps = { color: string; width: number; style: "solid" | "dashed" };

// Icon: two parallel lines
const channelIcon = () =>
  React.createElement(
    "svg",
    { width: 16, height: 16, viewBox: "0 0 20 20" },
    React.createElement("path", { d: "M4 16 L16 6", stroke: "#333", strokeWidth: 2, fill: "none" }),
    React.createElement("path", { d: "M4 14 L16 4", stroke: "#333", strokeWidth: 2, fill: "none" }),
  );

export class ChannelPlugin extends GenericPlugin<LineProps> {
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
    return "Patterns";
  }
  getToolSubgroup(): string | undefined {
    return "Channels";
  }
  getToolIcon(): () => any {
    return channelIcon;
  }

  protected drawShapePx(pointsPx: Array<{ x: number; y: number }>, props: LineProps, selected: boolean): void {
    if (pointsPx.length < 2) return;
    const [a, b] = pointsPx;
    const ctx = this.ctx;

    // Draw base segment AB
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

    // Draw parallel translated AB through C:
    // Compute signed perpendicular offset from C to AB, translate A and B by that offset.
    if (pointsPx.length >= 3) {
      const c = pointsPx[2];
      const ux = b.x - a.x;
      const uy = b.y - a.y;
      const ulen = Math.hypot(ux, uy) || 1;
      const U = { x: ux / ulen, y: uy / ulen };
      const N = { x: -U.y, y: U.x }; // left-hand perpendicular
      const AC = { x: c.x - a.x, y: c.y - a.y };
      const offset = AC.x * N.x + AC.y * N.y; // signed distance along N
      const T = { x: N.x * offset, y: N.y * offset };

      const a2 = { x: a.x + T.x, y: a.y + T.y };
      const b2 = { x: b.x + T.x, y: b.y + T.y };

      ctx.save();
      ctx.strokeStyle = props.color;
      ctx.lineWidth = Math.max(1, props.width);
      if (props.style === "dashed") ctx.setLineDash([6, 4]);
      ctx.beginPath();
      ctx.moveTo(a2.x + 0.5, a2.y + 0.5);
      ctx.lineTo(b2.x + 0.5, b2.y + 0.5);
      ctx.stroke();
      ctx.setLineDash([]);
      ctx.restore();
    }
  }

  protected drawPreviewPx(pointsPx: Array<{ x: number; y: number }>, props: LineProps) {
    const ctx = this.ctx;
    // Dashed preview of base AB
    if (pointsPx.length >= 2) {
      ctx.save();
      ctx.strokeStyle = props.color;
      ctx.lineWidth = Math.max(1, props.width);
      ctx.setLineDash([4, 4]);
      ctx.beginPath();
      ctx.moveTo(pointsPx[0].x + 0.5, pointsPx[0].y + 0.5);
      ctx.lineTo(pointsPx[1].x + 0.5, pointsPx[1].y + 0.5);
      ctx.stroke();
      ctx.setLineDash([]);
      ctx.restore();
    }
    // Dashed preview of exact parallel (translate AB by perpendicular offset from current point)
    if (pointsPx.length >= 3) {
      const a = pointsPx[0];
      const b = pointsPx[1];
      const c = pointsPx[2];

      const ux = b.x - a.x;
      const uy = b.y - a.y;
      const ulen = Math.hypot(ux, uy) || 1;
      const U = { x: ux / ulen, y: uy / ulen };
      const N = { x: -U.y, y: U.x };
      const AC = { x: c.x - a.x, y: c.y - a.y };
      const offset = AC.x * N.x + AC.y * N.y;
      const T = { x: N.x * offset, y: N.y * offset };
      const a2 = { x: a.x + T.x, y: a.y + T.y };
      const b2 = { x: b.x + T.x, y: b.y + T.y };

      ctx.save();
      ctx.strokeStyle = props.color;
      ctx.lineWidth = Math.max(1, props.width);
      ctx.setLineDash([4, 4]);
      ctx.beginPath();
      ctx.moveTo(a2.x + 0.5, a2.y + 0.5);
      ctx.lineTo(b2.x + 0.5, b2.y + 0.5);
      ctx.stroke();
      ctx.setLineDash([]);
      ctx.restore();
    }
  }

  // Hit test: near AB or parallel C->C+(B-A)
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
    // Segments AB and translated parallel AB'
    for (let i = this.shapes.length - 1; i >= 0; i--) {
      const s = this.shapes[i];
      const pts = this.toPxPoints(s.points);
      if (pts.length < 2) continue;
      const a = pts[0], b = pts[1];
      if (this.distToSegment(pt, a, b) <= this.cfg.hitTolerancePx) {
        return { id: s.id, mode: "move" };
      }
      if (pts.length >= 3) {
        const c = pts[2];
        const ux = b.x - a.x;
        const uy = b.y - a.y;
        const ulen = Math.hypot(ux, uy) || 1;
        const U = { x: ux / ulen, y: uy / ulen };
        const N = { x: -U.y, y: U.x };
        const AC = { x: c.x - a.x, y: c.y - a.y };
        const offset = AC.x * N.x + AC.y * N.y;
        const T = { x: N.x * offset, y: N.y * offset };
        const a2 = { x: a.x + T.x, y: a.y + T.y };
        const b2 = { x: b.x + T.x, y: b.y + T.y };

        if (this.distToSegment(pt, a2, b2) <= this.cfg.hitTolerancePx) {
          return { id: s.id, mode: "move" };
        }
      }
    }
    return null;
  }
}

// Register
registerPlugin({
  key: "channel",
  title: "Channel",
  group: "Patterns",
  subgroup: "Channels",
  icon: channelIcon,
  ctor: ChannelPlugin,
});
