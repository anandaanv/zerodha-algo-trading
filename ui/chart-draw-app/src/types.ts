export type Timeframe =
  | "1m"
  | "3m"
  | "5m"
  | "15m"
  | "30m"
  | "1h"
  | "4h"
  | "1d"
  | "1w";

export type OhlcBar = {
  time: number; // epoch seconds
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
};

// Core drawing types, expanded to cover more TradingView-like tools
export type DrawingType =
  | "LINE"
  | "RAY"
  | "EXTENDED_LINE"
  | "HLINE"
  | "VLINE"
  | "CHANNEL"
  | "RECT"
  | "TRIANGLE"
  | "TEXT"
  | "FIB_RETRACEMENT"
  | "FIB_EXTENSION";

export type Drawing = {
  id?: number;
  symbol: string;
  timeframe: string;
  userId?: string;
  type: DrawingType;
  name?: string;
  payloadJson: string; // serialized payload
  createdAt?: string;
  updatedAt?: string;
};

// Legacy pixel-based payloads (kept for backward compatibility)
export type LinePayload = { points: { x: number; y: number }[] };
export type ChannelPayload = { a: { x: number; y: number }[]; b: { x: number; y: number }[] };
export type TrianglePayload = { points: { x: number; y: number }[] };
export type FibRetracementPayload = { p1: { x: number; y: number }; p2: { x: number; y: number } };
export type FibExtensionPayload = { p1: { x: number; y: number }; p2: { x: number; y: number }; p3: { x: number; y: number } };

// Preferred logical coordinate payloads (time/price)
export type LogicalPoint = { time: number; price: number };

export type LinePayloadLogical = { points: LogicalPoint[] };
export type RayPayloadLogical = { points: LogicalPoint[] }; // 2 points
export type ExtendedLinePayloadLogical = { points: LogicalPoint[] }; // 2 points
export type HLinePayloadLogical = { price: number };
export type VLinePayloadLogical = { time: number };
export type ChannelPayloadLogical = { a: LogicalPoint[]; b: LogicalPoint[] }; // two lines
export type RectPayloadLogical = { p1: LogicalPoint; p2: LogicalPoint };
export type TrianglePayloadLogical = { points: LogicalPoint[] };
export type TextPayloadLogical = { at: LogicalPoint; text: string };
export type FibRetracementPayloadLogical = { p1: LogicalPoint; p2: LogicalPoint };
export type FibExtensionPayloadLogical = { p1: LogicalPoint; p2: LogicalPoint; p3: LogicalPoint };

export type Tool =
  | { kind: "cursor" }
  | { kind: "line" }
  | { kind: "ray" }
  | { kind: "extended-line" }
  | { kind: "horizontal-line" }
  | { kind: "vertical-line" }
  | { kind: "channel" }
  | { kind: "rectangle" }
  | { kind: "triangle" }
  | { kind: "text"; defaultText?: string }
  | { kind: "fib-retracement" }
  | { kind: "fib-extension" };
