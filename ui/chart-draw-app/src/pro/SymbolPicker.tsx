import React, { useEffect, useMemo, useState } from "react";

export type SymbolItem = {
  tradingsymbol: string;
  name?: string;
  lastPrice?: number | null;
  expiry?: string | null;
  strike?: string | null;
  instrumentType?: string | null;
  segment?: string | null;
  exchange?: string | null;
  lotSize?: number | null;
  tickSize?: number | null;
};

type Props = {
  open: boolean;
  value: string;
  onClose: () => void;
  onSelect: (symbol: string) => void;
  fetchSymbols: (query: string) => Promise<SymbolItem[]>;
};

export default function SymbolPicker({ open, value, onClose, onSelect, fetchSymbols }: Props) {
  const [query, setQuery] = useState(value);
  const [items, setItems] = useState<SymbolItem[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!open) return;
    setQuery(value);
    // Initial load
    let cancelled = false;
    (async () => {
      try {
        setLoading(true);
        const res = await fetchSymbols(value);
        if (!cancelled) setItems(res);
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const handle = setTimeout(async () => {
      setLoading(true);
      try {
        const res = await fetchSymbols(query);
        setItems(res);
      } finally {
        setLoading(false);
      }
    }, 200);
    return () => clearTimeout(handle);
  }, [open, query, fetchSymbols]);

  if (!open) return null;

  return (
    <div
      style={{
        position: "absolute",
        inset: 0,
        background: "rgba(0,0,0,0.25)",
        zIndex: 2000,
        display: "flex",
        alignItems: "flex-start",
        justifyContent: "flex-start",
      }}
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div
        style={{
          margin: 12,
          background: "#fff",
          border: "1px solid #e0e0e0",
          borderRadius: 8,
          width: 320,
          maxHeight: 420,
          boxShadow: "0 10px 30px rgba(0,0,0,0.15)",
          overflow: "hidden",
        }}
        onMouseDown={(e) => e.stopPropagation()}
      >
        <div style={{ padding: 10, borderBottom: "1px solid #eee" }}>
          <input
            autoFocus
            placeholder="Type a symbol..."
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            style={{
              width: "100%",
              padding: "8px 10px",
              borderRadius: 6,
              border: "1px solid #ddd",
              outline: "none",
            }}
          />
        </div>
        <div style={{ maxHeight: 360, overflowY: "auto" }}>
          {loading && <div style={{ padding: 12, color: "#666" }}>Loading...</div>}
          {!loading && items.length === 0 && <div style={{ padding: 12, color: "#666" }}>No matches</div>}
          {!loading &&
            items.map((s) => (
              <button
                key={s.tradingsymbol}
                onClick={() => {
                  onSelect(s.tradingsymbol);
                  onClose();
                }}
                style={{
                  width: "100%",
                  textAlign: "left",
                  padding: "8px 12px",
                  border: "none",
                  borderBottom: "1px solid #f5f5f5",
                  background: "#fff",
                  cursor: "pointer",
                  display: "flex",
                  flexDirection: "column",
                  alignItems: "flex-start",
                }}
              >
                <div style={{ fontWeight: 700, color: "#222" }}>{s.tradingsymbol}</div>
                <div style={{ fontSize: 12, color: "#666", marginTop: 2, display: "flex", gap: 8 }}>
                  <span>{s.tradingsymbol ?? ""}</span>
                  {typeof s.lastPrice === "number" && <span>· ₹{s.lastPrice.toFixed(2)}</span>}
                  {s.expiry && <span>· exp: {new Date(s.expiry).toLocaleDateString()}</span>}
                </div>
              </button>
            ))}
        </div>
        <div style={{ padding: 8, display: "flex", justifyContent: "flex-end", borderTop: "1px solid #eee" }}>
          <button
            onClick={onClose}
            style={{
              padding: "6px 10px",
              borderRadius: 6,
              border: "1px solid #ddd",
              background: "#fff",
              cursor: "pointer",
            }}
          >
            Close
          </button>
        </div>
      </div>
    </div>
  );
}
