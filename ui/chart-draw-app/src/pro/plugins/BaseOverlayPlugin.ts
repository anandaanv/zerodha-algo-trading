/* eslint-disable @typescript-eslint/no-explicit-any */
import type { IChartApi, Time } from "lightweight-charts";

export abstract class BaseOverlayPlugin {
  protected chart: IChartApi;
  protected series: any;
  protected container: HTMLElement;
  protected canvas: HTMLCanvasElement;
  protected ctx: CanvasRenderingContext2D;
  protected ro: ResizeObserver | null = null;

  constructor(params: { chart: IChartApi; series: any; container: HTMLElement }) {
    this.chart = params.chart;
    this.series = params.series;
    this.container = params.container;

    // overlay canvas
    this.canvas = document.createElement("canvas");
    this.canvas.style.position = "absolute";
    this.canvas.style.inset = "0";
    // Do not intercept pointer events by default; let the chart handle panning/zoom
    this.canvas.style.pointerEvents = "none";
    this.canvas.style.zIndex = "5";
    this.container.appendChild(this.canvas);
    const ctx = this.canvas.getContext("2d");
    if (!ctx) {
      throw new Error("2d context not available");
    }
    this.ctx = ctx;

    // subscriptions
    this.resizeCanvas();
    const onVisible = () => this.render();
    const onResize = () => this.resizeCanvas();
    this.chart.timeScale().subscribeVisibleTimeRangeChange(onVisible);
    (this as any)._onVisible = onVisible;
    (this as any)._onResize = onResize;
    if (typeof ResizeObserver !== "undefined") {
      this.ro = new ResizeObserver(() => onResize());
      this.ro.observe(this.container);
    }
    window.addEventListener("resize", onResize);
  }

  destroy() {
    try {
      this.chart.timeScale().unsubscribeVisibleTimeRangeChange((this as any)._onVisible);
    } catch {}
    try {
      window.removeEventListener("resize", (this as any)._onResize);
    } catch {}
    try {
      this.ro?.disconnect();
    } catch {}
    try {
      this.container.removeChild(this.canvas);
    } catch {}
  }

  protected resizeCanvas() {
    const rect = this.container.getBoundingClientRect();
    const dpr = window.devicePixelRatio || 1;
    this.canvas.width = Math.max(1, Math.floor(rect.width * dpr));
    this.canvas.height = Math.max(1, Math.floor(rect.height * dpr));
    this.canvas.style.width = `${Math.floor(rect.width)}px`;
    this.canvas.style.height = `${Math.floor(rect.height)}px`;
    this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    // Intentionally do not call render() here.
    // Subclasses should call render() after their own fields are initialized.
  }

  protected clearCanvas() {
    const rect = this.container.getBoundingClientRect();
    this.ctx.clearRect(0, 0, rect.width, rect.height);
  }

  protected priceScale() {
    return this.series?.priceScale?.() ?? this.series;
  }

  protected priceToY(price: number) {
    return (this.series?.priceToCoordinate?.(price) ?? this.priceScale()?.priceToCoordinate?.(price)) as number | null;
  }
  protected yToPrice(y: number) {
    return (this.series?.coordinateToPrice?.(y) ?? this.priceScale()?.coordinateToPrice?.(y)) as number | null;
  }
  protected timeToX(time: Time) {
    return this.chart.timeScale().timeToCoordinate(time) as number | null;
  }
  protected xToTime(x: number) {
    return this.chart.timeScale().coordinateToTime(x) as Time | null;
  }

  protected toLocal(event: MouseEvent) {
    const rect = this.canvas.getBoundingClientRect();
    return { x: event.clientX - rect.left, y: event.clientY - rect.top };
  }

  // Bring this plugin's canvas to front when active, or restore baseline when inactive
  public setActive(active: boolean) {
    // Use a high z-index while active so the overlay is above chart interaction layers
    this.canvas.style.zIndex = active ? "1000" : "5";
  }

  abstract render(): void;
}
