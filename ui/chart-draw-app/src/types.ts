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

export type DrawingType =
  | "LINE"
  | "CHANNEL"
  | "TRIANGLE"
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

export type LinePayload = { points: { x: number; y: number }[] };
export type ChannelPayload = { a: { x: number; y: number }[]; b: { x: number; y: number }[] };
export type TrianglePayload = { points: { x: number; y: number }[] };
export type FibRetracementPayload = { p1: { x: number; y: number }; p2: { x: number; y: number } };
export type FibExtensionPayload = { p1: { x: number; y: number }; p2: { x: number; y: number }; p3: { x: number; y: number } };

export type Tool =
  | { kind: "cursor" }
  | { kind: "line" }
  | { kind: "channel" }
  | { kind: "triangle" }
  | { kind: "fib-retracement" }
  | { kind: "fib-extension" };
