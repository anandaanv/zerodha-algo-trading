import React, { useEffect, useMemo, useRef } from "react";

// Minimal KDsl completion snippets (extend as needed)
const KDSL_SNIPPETS = [
  { label: "output", insertText: "output(passed = true, debug = mapOf())", detail: "Emit ScreenerOutput via DSL" },
  { label: "exitIf", insertText: "exitIf(condition = true, reason = \"\")", detail: "Early exit when condition matches" },
  { label: "enterIf", insertText: "enterIf(condition = true, reason = \"\")", detail: "Mark entry when condition matches" },
  { label: "crossUp", insertText: "crossUp(seriesA, seriesB)", detail: "Cross up condition" },
  { label: "crossDown", insertText: "crossDown(seriesA, seriesB)", detail: "Cross down condition" },
  { label: "sma", insertText: "sma(series, length = 20)", detail: "Simple Moving Average" },
  { label: "ema", insertText: "ema(series, length = 20)", detail: "Exponential Moving Average" },
  { label: "rsi", insertText: "rsi(series, length = 14)", detail: "Relative Strength Index" },
  { label: "macd", insertText: "macd(series, fast = 12, slow = 26, signal = 9)", detail: "MACD indicator" },
  { label: "hhv", insertText: "hhv(series, length = 20)", detail: "Highest high over length" },
  { label: "llv", insertText: "llv(series, length = 20)", detail: "Lowest low over length" },
  { label: "plot", insertText: "plot(series, name = \"series\", color = 0xFFAA00)", detail: "Plot series for debugging" },
  { label: "debug", insertText: "debug(name = \"key\", value = 0)", detail: "Attach debug key/value" },
  { label: "series", insertText: "series(alias)", detail: "Get series by alias" },
  { label: "ref", insertText: "ref(alias)", detail: "Reference alias helper" },
  { label: "valueAt", insertText: "valueAt(series, index = 0)", detail: "Get value by index" },
];

type Props = {
  value: string;
  onChange: (code: string) => void;
  aliasNames?: string[];
  placeholder?: string;
  height?: number | string;
};

declare global {
  interface Window {
    KotlinPlayground?: any;
    monaco?: any;
  }
}

function loadPlaygroundScript(): Promise<void> {
  return new Promise((resolve, reject) => {
    if (window.KotlinPlayground) return resolve();
    const s = document.createElement("script");
    s.src = "https://unpkg.com/kotlin-playground@1/dist/kotlin-playground.js";
    s.async = true;
    s.onload = () => resolve();
    s.onerror = (e) => reject(e);
    document.head.appendChild(s);
  });
}

export default function KotlinPlaygroundEditor({ value, onChange, aliasNames, placeholder, height = 320 }: Props) {
  const hostRef = useRef<HTMLDivElement | null>(null);
  const editorRef = useRef<any>(null);
  const disposeCompletionRef = useRef<(() => void) | null>(null);

  const aliasItems = useMemo(
    () => (aliasNames || []).filter(Boolean).map((a) => ({ label: a, insertText: a, detail: "Alias" })),
    [aliasNames]
  );

  useEffect(() => {
    let mounted = true;
    let playgroundInstance: any = null;

    async function init() {
      try {
        await loadPlaygroundScript();
        if (!mounted || !hostRef.current) return;

        // Clear host and inject a code element for the playground to pick up
        hostRef.current.innerHTML = "";
        const codeEl = document.createElement("code");
        codeEl.className = "kotlin";
        codeEl.setAttribute("data-highlight-only", "false");
        codeEl.setAttribute("data-theme", "idea");
        codeEl.setAttribute("data-kotlin-language", "kts"); // Kotlin script
        codeEl.textContent = value || "";
        hostRef.current.appendChild(codeEl);

        // Initialize Kotlin Playground on this specific container
        playgroundInstance = window.KotlinPlayground(hostRef.current, {
          // enable features
          autoIndent: true,
          theme: "idea",
          // onChange callback receives editor content
          onChange: (content: string) => {
            if (!mounted) return;
            onChange(content);
          },
        });

        // Try to fetch the underlying Monaco editor if exposed
        // Some playground builds expose `editor` on instance or global monaco
        setTimeout(() => {
          try {
            const monaco = window.monaco;
            if (!monaco) return;

            // Register KDsl + alias completions
            const disposable = monaco.languages.registerCompletionItemProvider("kotlin", {
              triggerCharacters: [".", "_"],
              provideCompletionItems: (model: any, position: any) => {
                const word = model.getWordUntilPosition(position);
                const range = {
                  startLineNumber: position.lineNumber,
                  endLineNumber: position.lineNumber,
                  startColumn: word.startColumn,
                  endColumn: word.endColumn,
                };
                const kdsl = KDSL_SNIPPETS.map((s) => ({
                  label: s.label,
                  kind: monaco.languages.CompletionItemKind.Function,
                  insertText: s.insertText,
                  detail: s.detail,
                  range,
                }));
                const aliases = aliasItems.map((a) => ({
                  label: a.label,
                  kind: monaco.languages.CompletionItemKind.Variable,
                  insertText: a.insertText,
                  detail: a.detail,
                  range,
                }));
                return { suggestions: [...kdsl, ...aliases] };
              },
            });

            disposeCompletionRef.current = () => {
              try {
                disposable.dispose();
              } catch {
                // ignore
              }
            };
          } catch {
            // Monaco not available or API mismatch; ignore gracefully
          }
        }, 0);
      } catch (e) {
        // If playground fails to load, degrade to a plain textarea
        if (!mounted || !hostRef.current) return;
        hostRef.current.innerHTML = "";
        const ta = document.createElement("textarea");
        ta.value = value || "";
        ta.placeholder = placeholder || "Kotlin script";
        ta.style.width = "100%";
        ta.style.height = typeof height === "number" ? `${height}px` : String(height);
        ta.style.fontFamily = "ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', 'Courier New', monospace";
        ta.style.fontSize = "13px";
        ta.oninput = () => onChange(ta.value);
        hostRef.current.appendChild(ta);
      }
    }

    init();

    return () => {
      mounted = false;
      if (disposeCompletionRef.current) {
        disposeCompletionRef.current();
        disposeCompletionRef.current = null;
      }
      // playgroundInstance may not expose a dispose; clear node on unmount
      if (hostRef.current) {
        hostRef.current.innerHTML = "";
      }
    };
  }, [value, onChange, aliasItems, placeholder, height]);

  // Keep editor content in sync if external value changes (basic heuristic)
  useEffect(() => {
    // The playground keeps internal state; re-init on value changes handled above
    // Nothing additional here to avoid cursor jumps
  }, [value]);

  return (
    <div
      ref={hostRef}
      style={{
        width: "100%",
        minHeight: typeof height === "number" ? `${height}px` : String(height),
        border: "1px solid #ddd",
        borderRadius: 6,
        overflow: "hidden",
      }}
      aria-label="Kotlin Editor"
      data-placeholder={placeholder || "// Kotlin screener script here. Press Ctrl+Space for suggestions."}
    />
  );
}
