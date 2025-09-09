/* eslint-disable @typescript-eslint/no-explicit-any */
import * as KC from "klinecharts";

let registeredOnce = false;

export function registerOverlayTemplates() {
  if (registeredOnce) return;
  registeredOnce = true;

  const reg = (tpl: any) => {
    try {
      if (typeof (KC as any).registerOverlay === "function") {
        (KC as any).registerOverlay(tpl);
      } else if (typeof (KC as any).registerGraphicMark === "function") {
        (KC as any).registerGraphicMark(tpl);
      }
    } catch {
      // ignore registration errors
    }
  };

  // helper for connected segments
  const polyline = (pts: any[]) => {
    const figs: any[] = [];
    for (let i = 1; i < pts.length; i++) {
      figs.push({ type: "line", attrs: { coordinates: [pts[i - 1], pts[i]] } });
    }
    return figs;
  };

  // IMPORTANT: do NOT override built-in tools on v9 (rect, circle, triangle, diamond,
  // fibonacciRetracement, fibonacciExtension). Only register custom Elliott tools.

  reg({
    name: "elliottImpulse",
    totalStep: 5,
    needDefaultPointFigure: true,
    createPointFigures: ({ coordinates }: any) => {
      if (coordinates.length < 5) return [];
      const pts = coordinates.slice(0, 5);
      const figs: any[] = [...polyline(pts)];
      const labels = ["1", "2", "3", "4", "5"];
      pts.forEach((p: any, i: number) => figs.push({ type: "text", attrs: { x: p.x + 4, y: p.y - 4, text: labels[i] } }));
      return figs;
    },
  });

  reg({
    name: "elliottABC",
    totalStep: 3,
    needDefaultPointFigure: true,
    createPointFigures: ({ coordinates }: any) => {
      if (coordinates.length < 3) return [];
      const pts = coordinates.slice(0, 3);
      const figs: any[] = [...polyline(pts)];
      const labels = ["A", "B", "C"];
      pts.forEach((p: any, i: number) => figs.push({ type: "text", attrs: { x: p.x + 4, y: p.y - 4, text: labels[i] } }));
      return figs;
    },
  });

  reg({
    name: "elliottTriangle",
    totalStep: 5,
    needDefaultPointFigure: true,
    createPointFigures: ({ coordinates }: any) => {
      if (coordinates.length < 5) return [];
      const pts = coordinates.slice(0, 5);
      const figs: any[] = [...polyline(pts)];
      const labels = ["A", "B", "C", "D", "E"];
      pts.forEach((p: any, i: number) => figs.push({ type: "text", attrs: { x: p.x + 4, y: p.y - 4, text: labels[i] } }));
      return figs;
    },
  });

  reg({
    name: "elliottWXY",
    totalStep: 3,
    needDefaultPointFigure: true,
    createPointFigures: ({ coordinates }: any) => {
      if (coordinates.length < 3) return [];
      const pts = coordinates.slice(0, 3);
      const figs: any[] = [...polyline(pts)];
      const labels = ["W", "X", "Y"];
      pts.forEach((p: any, i: number) => figs.push({ type: "text", attrs: { x: p.x + 4, y: p.y - 4, text: labels[i] } }));
      return figs;
    },
  });
}
