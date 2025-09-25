import React, { useEffect, useMemo, useRef, useState } from "react";

type Suggestion = {
  label: string;
  insertText: string;
  description?: string;
  type?: "function" | "keyword" | "alias";
};

type Props = {
  value: string;
  onChange: (next: string) => void;
  placeholder?: string;
  aliasNames?: string[];
  rows?: number;
  style?: React.CSSProperties;
};

const KOTLIN_KEYWORDS: Suggestion[] = [
  "fun","val","var","if","else","when","return","true","false","null","class","object","interface","import","package","for","while","do","try","catch","finally","throw","in","is"
].map((k) => ({ label: k, insertText: k, type: "keyword" }));

// Minimal KDsl function list with common helpers and snippets
const KDSL_FUNCTIONS: Suggestion[] = [
  { label: "output", insertText: "output(passed = true, debug = mapOf())", description: "Emit ScreenerOutput from DSL", type: "function" },
  { label: "exitIf", insertText: "exitIf(condition = true, reason = \"\")", description: "Early exit when condition matches", type: "function" },
  { label: "enterIf", insertText: "enterIf(condition = true, reason = \"\")", description: "Mark entry when condition matches", type: "function" },
  { label: "crossUp", insertText: "crossUp(seriesA, seriesB)", description: "Cross up condition", type: "function" },
  { label: "crossDown", insertText: "crossDown(seriesA, seriesB)", description: "Cross down condition", type: "function" },
  { label: "sma", insertText: "sma(series, length = 20)", description: "Simple moving average", type: "function" },
  { label: "ema", insertText: "ema(series, length = 20)", description: "Exponential moving average", type: "function" },
  { label: "rsi", insertText: "rsi(series, length = 14)", description: "Relative strength index", type: "function" },
  { label: "macd", insertText: "macd(series, fast = 12, slow = 26, signal = 9)", description: "MACD indicator", type: "function" },
  { label: "hhv", insertText: "hhv(series, length = 20)", description: "Highest high value over length", type: "function" },
  { label: "llv", insertText: "llv(series, length = 20)", description: "Lowest low value over length", type: "function" },
  { label: "plot", insertText: "plot(series, name = \"series\", color = 0xFFAA00)", description: "Plot series for debugging", type: "function" },
  { label: "debug", insertText: "debug(name = \"key\", value = 0)", description: "Attach debug key/value", type: "function" },
  { label: "series", insertText: "series(alias)", description: "Get series by alias", type: "function" },
  { label: "ref", insertText: "ref(alias)", description: "Reference alias helper", type: "function" },
  { label: "valueAt", insertText: "valueAt(series, index = 0)", description: "Get value by index", type: "function" },
].map((s) => ({ ...s, description: s.description ?? "", type: "function" as const }));

function getCurrentToken(text: string, caret: number): { token: string; start: number; end: number } {
  const left = text.slice(0, caret);
  const right = text.slice(caret);
  const leftMatch = left.match(/[A-Za-z_][A-Za-z0-9_]*$/);
  const rightMatch = right.match(/^[A-Za-z0-9_]*/);
  const start = leftMatch ? caret - leftMatch[0].length : caret;
  const end = caret + (rightMatch ? rightMatch[0].length : 0);
  const token = text.slice(start, end);
  return { token, start, end };
}

export default function KotlinEditor({ value, onChange, placeholder, aliasNames, rows = 12, style }: Props) {
  const taRef = useRef<HTMLTextAreaElement | null>(null);
  const [open, setOpen] = useState(false);
  const [items, setItems] = useState<Suggestion[]>([]);
  const [selected, setSelected] = useState(0);
  const [anchorBottom, setAnchorBottom] = useState(true);

  const aliasItems = useMemo<Suggestion[]>(
    () => (aliasNames || []).map((a) => ({ label: a, insertText: a, description: "Alias", type: "alias" })),
    [aliasNames]
  );

  const allItems = useMemo(() => [...KOTLIN_KEYWORDS, ...KDSL_FUNCTIONS, ...aliasItems], [aliasItems]);

  function openSuggestions(forceAll = false) {
    const el = taRef.current;
    if (!el) return;
    const caret = el.selectionStart ?? 0;
    const { token } = getCurrentToken(value, caret);
    const q = forceAll ? "" : token;
    const filtered = allItems
      .filter((s) => (q ? s.label.toLowerCase().startsWith(q.toLowerCase()) : true))
      .slice(0, 20);
    setItems(filtered);
    setSelected(0);
    setOpen(filtered.length > 0);
  }

  function closeSuggestions() {
    setOpen(false);
  }

  function insertSuggestion(s: Suggestion) {
    const el = taRef.current;
    if (!el) return;
    const caret = el.selectionStart ?? 0;
    const { start, end } = getCurrentToken(value, caret);
    const before = value.slice(0, start);
    const after = value.slice(end);
    const next = before + s.insertText + after;
    const newCaret = (before + s.insertText).length;
    onChange(next);
    // restore caret after React updates
    setTimeout(() => {
      if (taRef.current) {
        taRef.current.focus();
        taRef.current.selectionStart = newCaret;
        taRef.current.selectionEnd = newCaret;
      }
    }, 0);
    closeSuggestions();
  }

  function onKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (e.ctrlKey && e.code === "Space") {
      e.preventDefault();
      openSuggestions(true);
      return;
    }
    if (!open) return;

    if (e.key === "ArrowDown") {
      e.preventDefault();
      setSelected((s) => Math.min(s + 1, Math.max(items.length - 1, 0)));
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setSelected((s) => Math.max(s - 1, 0));
    } else if (e.key === "Enter" || e.key === "Tab") {
      e.preventDefault();
      const s = items[selected];
      if (s) insertSuggestion(s);
    } else if (e.key === "Escape") {
      e.preventDefault();
      closeSuggestions();
    }
  }

  function onInput(e: React.ChangeEvent<HTMLTextAreaElement>) {
    const next = e.target.value;
    onChange(next);
    // Open or update suggestions as the user types an identifier
    const el = e.target;
    const caret = el.selectionStart ?? 0;
    const { token } = getCurrentToken(next, caret);
    const isIdent = /^[A-Za-z_][A-Za-z0-9_]*$/.test(token);
    if (isIdent && token.length >= 1) {
      openSuggestions(false);
    } else {
      closeSuggestions();
    }
  }

  useEffect(() => {
    const el = taRef.current;
    if (!el) return;
    function onScrollOrResize() {
      // Keep dropdown visible; switch to top if near bottom
      const rect = el.getBoundingClientRect();
      const spaceBelow = window.innerHeight - rect.bottom;
      setAnchorBottom(spaceBelow > 240);
    }
    onScrollOrResize();
    window.addEventListener("resize", onScrollOrResize);
    return () => window.removeEventListener("resize", onScrollOrResize);
  }, []);

  return (
    <div style={{ position: "relative", width: "100%" }}>
      <textarea
        ref={taRef}
        spellCheck={false}
        rows={rows}
        value={value}
        placeholder={placeholder || "// Kotlin screener script here. Press Ctrl+Space for suggestions."}
        onChange={onInput}
        onKeyDown={onKeyDown}
        onBlur={() => {
          // keep open only if suggestions are hovered; simple close after blur
          setTimeout(() => closeSuggestions(), 150);
        }}
        style={{
          width: "100%",
          fontFamily: "ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', 'Courier New', monospace",
          fontSize: 13,
          lineHeight: "1.5",
          padding: 12,
          borderRadius: 6,
          border: "1px solid #ddd",
          outline: "none",
          resize: "vertical",
          ...style,
        }}
      />
      {open && items.length > 0 && (
        <div
          style={{
            position: "absolute",
            left: 8,
            [anchorBottom ? "bottom" : "top"]: 8,
            zIndex: 10,
            width: "min(640px, 92%)",
            maxHeight: 240,
            overflowY: "auto",
            background: "white",
            border: "1px solid #ddd",
            borderRadius: 6,
            boxShadow: "0 8px 24px rgba(0,0,0,0.12)",
          } as React.CSSProperties}
          onMouseDown={(e) => e.preventDefault()}
        >
          {items.map((it, idx) => (
            <div
              key={it.label + ":" + idx}
              onMouseEnter={() => setSelected(idx)}
              onClick={() => insertSuggestion(it)}
              style={{
                display: "grid",
                gridTemplateColumns: "160px 1fr",
                gap: 8,
                padding: "8px 10px",
                cursor: "pointer",
                background: idx === selected ? "#f0f7ff" : "transparent",
                borderBottom: "1px solid #f6f6f6",
              }}
            >
              <div style={{ fontWeight: 600, color: it.type === "function" ? "#2962FF" : it.type === "alias" ? "#2E7D32" : "#555" }}>
                {it.label}
              </div>
              <div style={{ color: "#555" }}>{it.description || it.insertText}</div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
