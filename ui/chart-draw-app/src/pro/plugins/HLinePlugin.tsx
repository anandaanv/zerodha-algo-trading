/* eslint-disable @typescript-eslint/no-explicit-any */
import { BaseOverlayPlugin } from "./BaseOverlayPlugin";
import type { Time } from "lightweight-charts";
import { registerPlugin } from "./PluginRegistry";

type DragMode = "none" | "move";
type SimpleStyle = { color: string; width: number; style: "solid" | "dashed" };

export class HLinePlugin extends BaseOverlayPlugin {
  private drawing = false;
  private yPreview: number | null = null;

  private uid = () => Math.random().toString(36).slice(2, 10);
  private lines: { id: string; price: number; timeRef: Time | null }[] = [];
  private selectedId: string | null = null;
  private dragMode: DragMode = "none";
  private dragStartY: number | null = null;

  private selectionStyles = new Map<string, SimpleStyle>();
  private defaultStyle: SimpleStyle = { color: "#1976d2", width: 2, style: "solid" };

  constructor(params: { chart: any; series: any; container: HTMLElement }) {
    super(params);
    // pointer events off unless drawing; central selection handles clicks
    this.canvas.style.pointerEvents = "none";

    const move = (e: MouseEvent) => {
      if (!this.drawing) return;
      const rect = this.canvas.getBoundingClientRect();
      const y = e.clientY - rect.top;
      this.yPreview = y;
      this.render();
    };
    const down = (e: MouseEvent) => {
      if (!this.drawing || e.button !== 0) return;
      const rect = this.canvas.getBoundingClientRect();
      const y = e.clientY - rect.top;
      const price = this.yToPrice(y);
      if (price != null) {
        const id = this.uid();
        this.lines.push({ id, price, timeRef: null });
        this.selectedId = id;
      }
      this.cancel();
      this.render();
    };
    this.canvas.addEventListener("mousemove", move);
    this.canvas.addEventListener("mousedown", down);
    (this as any)._move = move;
    (this as any)._down = down;

    // start inactive
    this.setActive(false);
    this.render();
  }

  startDrawing() {
    this.drawing = true;
    this.setActive(true);
    this.canvas.style.pointerEvents = "auto";
    this.canvas.style.cursor = "crosshair";
    this.render();
  }

  cancel() {
    if (!this.drawing) return;
    this.drawing = false;
    this.canvas.style.pointerEvents = "none";
    this.canvas.style.cursor = "default";
    this.yPreview = null;
  }

  destroy() {
    try {
      this.canvas.removeEventListener("mousemove", (this as any)._move);
      this.canvas.removeEventListener("mousedown", (this as any)._down);
    } catch {}
    super.destroy();
  }

  clearSelection() {
    this.selectedId = null;
    this.render();
  }

  hasSelection() {
    return !!this.selectedId;
  }

  getSelectedStyle(): SimpleStyle | null {
    if (!this.selectedId) return null;
    return this.selectionStyles.get(this.selectedId) ?? { ...this.defaultStyle };
  }

  applySelectedStyle(s: SimpleStyle) {
    if (!this.selectedId) return false;
    this.selectionStyles.set(this.selectedId, { ...s });
    this.render();
    return true;
  }

  trySelectAt(clientX: number, clientY: number): boolean {
    const rect = this.canvas.getBoundingClientRect();
    const pt = { x: clientX - rect.left, y: clientY - rect.top };
    const T = 6;
    for (let i = this.lines.length - 1; i >= 0; i--) {
      const ln = this.lines[i];
      const y = this.priceToY(ln.price);
      if (y != null && Math.abs(pt.y - y) <= T) {
        this.selectedId = ln.id;
        this.render();
        return true;
      }
    }
    return false;
  }

  // persistence
  exportAll() {
    return this.lines.map((ln) => {
      const style = this.selectionStyles.get(ln.id) ?? this.defaultStyle;
      return { price: ln.price, style };
    });
  }

  importAll(items: Array<{ price: number; style?: SimpleStyle }>) {
    this.lines.splice(0, this.lines.length);
    this.selectionStyles.clear();
    for (const it of items || []) {
      const id = this.uid();
      const style = it.style ?? this.defaultStyle;
      this.lines.push({ id, price: it.price, timeRef: null });
      this.selectionStyles.set(id, style);
    }
    this.render();
  }

  render(): void {
    this.clearCanvas();
    const rect = this.container.getBoundingClientRect();

    // draw saved lines
    for (const ln of this.lines) {
      const y = this.priceToY(ln.price);
      if (y == null) continue;
      const style = this.selectionStyles.get(ln.id) ?? this.defaultStyle;
      this.drawHorz(0, rect.width, y, style);
      if (this.selectedId === ln.id) {
        this.drawAnchor(8, y);
      }
    }

    // preview
    if (this.drawing && this.yPreview != null) {
      this.drawHorz(0, rect.width, this.yPreview, { color: "#455a64", width: 1.5, style: "solid" });
    }
  }

  private drawHorz(x1: number, x2: number, y: number, style: SimpleStyle) {
    const ctx = this.ctx;
    ctx.save();
    ctx.strokeStyle = style.color;
    ctx.lineWidth = Math.max(1, style.width);
    if (style.style === "dashed") ctx.setLineDash([6, 4]);
    ctx.beginPath();
    ctx.moveTo(x1 + 0.5, y + 0.5);
    ctx.lineTo(x2 + 0.5, y + 0.5);
    ctx.stroke();
    ctx.setLineDash([]);
    ctx.restore();
  }

  private drawAnchor(x: number, y: number) {
    const ctx = this.ctx;
    ctx.save();
    ctx.fillStyle = "#1976d2";
    ctx.strokeStyle = "#ffffff";
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.arc(x, y, 4, 0, Math.PI * 2);
    ctx.fill();
    ctx.stroke();
    ctx.restore();
  }
}

registerPlugin({
  key: "hline",
  title: "Horizontal Line",
  group: "Lines",
  icon: () => (
    <svg width="16" height="16" viewBox="0 0 20 20">
      <path d="M3 10 H17" stroke="#333" strokeWidth="2" />
    </svg>
  ),
  ctor: HLinePlugin,
});
