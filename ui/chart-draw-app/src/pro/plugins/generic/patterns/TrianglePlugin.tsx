/* eslint-disable @typescript-eslint/no-explicit-any */
import React from "react";
import { GenericPlugin, type ShapeId, type DragMode } from "../GenericPlugin";
import { registerPlugin } from "../../PluginRegistry";

export type LineProps = { color: string; width: number; style: "solid" | "dashed" };

// Icon: two converging lines
const triangleIcon = () =>
  React.createElement(
    "svg",
    { width: 16, height: 16, viewBox: "0 0 20 20" },
    React.createElement("path", { d: "M3 16 L11 4", stroke: "#333", strokeWidth: 2, fill: "none" }),
    React.createElement("path", { d: "M17 16 L9 4", stroke: "#333", strokeWidth: 2, fill: "none" }),
  );

export class TrianglePlugin extends GenericPlugin<LineProps> {
  constructor(params: { chart: any; series: any; container: HTMLElement }) {
    super(params, {
      minPoints: 4,
      maxPoints: 4,
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
    return "Triangles";
  }
  getToolIcon(): () => any {
    return triangleIcon;
  }

  protected drawShapePx(pointsPx: Array<{ x: number; y: number }>, props: LineProps, selected: boolean): void {
    if (pointsPx.length < 2) return;
    const ctx = this.ctx;
    const a = pointsPx[0], b = pointsPx[1];
    let ab1 = a, ab2 = b;

    if (pointsPx.length >= 4) {
      const c = pointsPx[2], d = pointsPx[3];
      const ext = this.extendToIntersectionOrLimit(a, b, c, d);

      ab1 = ext.ab1; ab2 = ext.ab2;
      const cd1 = ext.cd1, cd2 = ext.cd2;

      // Draw AB extended
      this.strokeSeg(ab1, ab2, props);
      // Draw CD extended
      this.strokeSeg(cd1, cd2, props);
      return;
    }

    // Only AB so far
    this.strokeSeg(ab1, ab2, props);
  }

  protected drawPreviewPx(pointsPx: Array<{ x: number; y: number }>, props: LineProps) {
    const ctx = this.ctx;
    // Dashed currently placed polylines
    if (pointsPx.length >= 2) {
      ctx.save();
      ctx.strokeStyle = props.color;
      ctx.lineWidth = Math.max(1, props.width);
      ctx.setLineDash([4, 4]);
      ctx.beginPath();
      ctx.moveTo(pointsPx[0].x + 0.5, pointsPx[0].y + 0.5);
      for (let i = 1; i < Math.min(pointsPx.length, 2); i++) {
        ctx.lineTo(pointsPx[i].x + 0.5, pointsPx[i].y + 0.5);
      }
      ctx.stroke();
      ctx.setLineDash([]);
      ctx.restore();
    }
    if (pointsPx.length >= 3) {
      ctx.save();
      ctx.strokeStyle = props.color;
      ctx.lineWidth = Math.max(1, props.width);
      ctx.setLineDash([4, 4]);
      ctx.beginPath();
      ctx.moveTo(pointsPx[2].x + 0.5, pointsPx[2].y + 0.5);
      if (pointsPx[3]) ctx.lineTo(pointsPx[3].x + 0.5, pointsPx[3].y + 0.5);
      ctx.stroke();
      ctx.setLineDash([]);
      ctx.restore();
    }

    // When all 4 points placed, render final
    if (pointsPx.length >= 4) {
      this.drawShapePx(pointsPx.slice(0, 4), { ...props, width: Math.max(1, props.width) }, false);
    }
  }

  private strokeSeg(p: { x: number; y: number }, q: { x: number; y: number }, props: LineProps) {
    const ctx = this.ctx;
    ctx.save();
    ctx.strokeStyle = props.color;
    ctx.lineWidth = Math.max(1, props.width);
    if (props.style === "dashed") ctx.setLineDash([6, 4]);
    ctx.beginPath();
    ctx.moveTo(p.x + 0.5, p.y + 0.5);
    ctx.lineTo(q.x + 0.5, q.y + 0.5);
    ctx.stroke();
    ctx.setLineDash([]);
    ctx.restore();
  }

  // Compute extended segments: extend AB and CD in their directions,
  // until intersection or until 50% beyond original segment length (1.5x total).
  private extendToIntersectionOrLimit(
    a: { x: number; y: number },
    b: { x: number; y: number },
    c: { x: number; y: number },
    d: { x: number; y: number }
  ): { ab1: { x: number; y: number }; ab2: { x: number; y: number }; cd1: { x: number; y: number }; cd2: { x: number; y: number } } {
    const v1 = { x: b.x - a.x, y: b.y - a.y };
    const v2 = { x: d.x - c.x, y: d.y - c.y };

    // Solve a + v1*t = c + v2*u; t,u are dimensionless (t=1 at b, u=1 at d)
    const det = v1.x * (-v2.y) - v1.y * (-v2.x); // v1 cross (-v2)
    let t = Infinity, u = Infinity;

    if (Math.abs(det) > 1e-6) {
      const rhs = { x: c.x - a.x, y: c.y - a.y };
      t = (rhs.x * (-v2.y) - rhs.y * (-v2.x)) / det;
      u = (v1.x * rhs.y - v1.y * rhs.x) / det;
    }

    // Limit to 1.5x original length (i.e., 50% beyond)
    const limit = 1.5;

    const tClamped = Math.max(0, Math.min(t, limit));
    const uClamped = Math.max(0, Math.min(u, limit));

    const ab2 = isFinite(t) ? { x: a.x + v1.x * tClamped, y: a.y + v1.y * tClamped } : { x: a.x + v1.x * limit, y: a.y + v1.y * limit };
    const cd2 = isFinite(u) ? { x: c.x + v2.x * uClamped, y: c.y + v2.y * uClamped } : { x: c.x + v2.x * limit, y: c.y + v2.y * limit };

    return { ab1: a, ab2, cd1: c, cd2 };
  }

  // Hit test near either extended segment
  protected hitTest(pt: { x: number; y: number }): { id: ShapeId; mode: DragMode; anchorIndex?: number } | null {
    // Anchor hit
    for (let i = this.shapes.length - 1; i >= 0; i--) {
      const s = this.shapes[i];
      const pts = this.toPxPoints(s.points);
      for (let a = 0; a < pts.length; a++) {
        if (this.dist(pt, pts[a]) <= this.cfg.anchorRadiusPx + 2) {
          return { id: s.id, mode: "anchor", anchorIndex: a };
        }
      }
    }
    // Segment hit
    for (let i = this.shapes.length - 1; i >= 0; i--) {
      const s = this.shapes[i];
      const pts = this.toPxPoints(s.points);
      if (pts.length < 4) continue;
      const ext = this.extendToIntersectionOrLimit(pts[0], pts[1], pts[2], pts[3]);
      if (this.distToSegment(pt, ext.ab1, ext.ab2) <= this.cfg.hitTolerancePx) return { id: s.id, mode: "move" };
      if (this.distToSegment(pt, ext.cd1, ext.cd2) <= this.cfg.hitTolerancePx) return { id: s.id, mode: "move" };
    }
    return null;
  }
}

// Register
registerPlugin({
  key: "triangle",
  title: "Triangle (AB vs CD)",
  group: "Patterns",
  subgroup: "Triangles",
  icon: triangleIcon,
  ctor: TrianglePlugin,
});
