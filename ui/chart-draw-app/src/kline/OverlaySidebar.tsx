import React, { useEffect, useMemo, useRef, useState } from "react";

type Props = {
  onCreate: (id: string) => void;
  onClear: () => void;
};

// Inline SVG icons to avoid network fetch and keep it instant
function Icon({ id }: { id: string }) {
  const stroke = "#444";
  const fill = "none";
  const thin = 1.5;
  const small = { width: 20, height: 20, viewBox: "0 0 20 20" };
  switch (id) {
    // Lines
    case "straightLine":
      return <svg {...small}><path d="M2 16 L18 4" stroke={stroke} strokeWidth={thin} fill={fill} /></svg>;
    case "rayLine":
      return <svg {...small}><path d="M2 16 L18 6" stroke={stroke} strokeWidth={thin} fill={fill} /><path d="M18 6 L19 5" stroke={stroke} strokeWidth={thin} /></svg>;
    case "segment":
      return <svg {...small}><path d="M4 15 L16 5" stroke={stroke} strokeWidth={thin} /></svg>;
    case "parallelStraightLine":
      return <svg {...small}><path d="M3 15 L17 5" stroke={stroke} strokeWidth={thin} /><path d="M3 17 L17 7" stroke={stroke} strokeWidth={thin} /></svg>;
    case "horizontalStraightLine":
      return <svg {...small}><path d="M3 10 H17" stroke={stroke} strokeWidth={thin} /></svg>;
    case "verticalStraightLine":
      return <svg {...small}><path d="M10 3 V17" stroke={stroke} strokeWidth={thin} /></svg>;
    // Shapes
    case "rect":
      return <svg {...small}><rect x="4" y="4" width="12" height="12" stroke={stroke} strokeWidth={thin} fill="rgba(68,68,68,0.05)" /></svg>;
    case "circle":
      return <svg {...small}><circle cx="10" cy="10" r="6" stroke={stroke} strokeWidth={thin} fill="rgba(68,68,68,0.05)" /></svg>;
    case "triangle":
      return <svg {...small}><path d="M10 4 L16 16 L4 16 Z" stroke={stroke} strokeWidth={thin} fill="rgba(68,68,68,0.05)" /></svg>;
    case "diamond":
      return <svg {...small}><path d="M10 3 L16 10 L10 17 L4 10 Z" stroke={stroke} strokeWidth={thin} fill="rgba(68,68,68,0.05)" /></svg>;
    // Fibonacci
    case "fibonacciRetracement":
      return <svg {...small}><path d="M5 5 H15" stroke={stroke} strokeWidth={thin} /><path d="M5 8 H15" stroke={stroke} strokeWidth={thin} opacity="0.8" /><path d="M5 11 H15" stroke={stroke} strokeWidth={thin} opacity="0.6" /><path d="M5 14 H15" stroke={stroke} strokeWidth={thin} opacity="0.4" /></svg>;
    case "fibonacciExtension":
      return <svg {...small}><path d="M5 15 L10 10 L15 5" stroke={stroke} strokeWidth={thin} /><path d="M12 8 H16" stroke={stroke} strokeWidth={thin} opacity="0.6" /><path d="M8 12 H12" stroke={stroke} strokeWidth={thin} opacity="0.6" /></svg>;
    // Elliott (icons are schematic)
    case "elliottImpulse":
      return <svg {...small}><path d="M3 15 L6 9 L9 12 L12 6 L17 10" stroke={stroke} strokeWidth={thin} fill="none" /></svg>;
    case "elliottABC":
      return <svg {...small}><path d="M3 15 L8 9 L15 13" stroke={stroke} strokeWidth={thin} fill="none" /></svg>;
    case "elliottTriangle":
      return <svg {...small}><path d="M3 14 L8 8 L12 12 L16 9 L18 11" stroke={stroke} strokeWidth={thin} fill="none" /></svg>;
    case "elliottWXY":
      return <svg {...small}><path d="M3 15 L9 9 L13 13 L17 7" stroke={stroke} strokeWidth={thin} fill="none" /></svg>;
    default:
      return <svg {...small}><rect x="4" y="4" width="12" height="12" stroke={stroke} strokeWidth={thin} fill={fill} /></svg>;
  }
}

// Group icons (compact)
function GroupIcon({ group }: { group: string }) {
  const stroke = "#333";
  const thin = 1.8;
  switch (group) {
    case "Lines":
      return <svg width="20" height="20" viewBox="0 0 20 20"><path d="M3 16 L17 4" stroke={stroke} strokeWidth={thin} /><path d="M3 14 L17 2" stroke={stroke} strokeWidth={0.9} opacity="0.6" /></svg>;
    case "Shapes":
      return <svg width="20" height="20" viewBox="0 0 20 20"><rect x="3" y="3" width="7" height="7" stroke={stroke} fill="rgba(0,0,0,0.05)" /><circle cx="14.5" cy="6" r="3.5" stroke={stroke} fill="rgba(0,0,0,0.05)" /></svg>;
    case "Fibonacci":
      return <svg width="20" height="20" viewBox="0 0 20 20"><path d="M4 5 H16" stroke={stroke} strokeWidth={thin} /><path d="M4 9 H16" stroke={stroke} strokeWidth={thin} opacity="0.7" /><path d="M4 13 H16" stroke={stroke} strokeWidth={thin} opacity="0.5" /></svg>;
    case "Elliott":
      return <svg width="20" height="20" viewBox="0 0 20 20"><path d="M2 16 L6 9 L10 13 L14 6 L18 10" stroke={stroke} strokeWidth={thin} fill="none" /></svg>;
    default:
      return <svg width="20" height="20" viewBox="0 0 20 20"><rect x="4" y="4" width="12" height="12" stroke={stroke} fill="none" /></svg>;
  }
}

const ALL_ITEMS: { id: string; label: string; group: string }[] = [
  // Lines
  { id: "straightLine", label: "Trend Line", group: "Lines" },
  { id: "rayLine", label: "Ray", group: "Lines" },
  { id: "segment", label: "Segment", group: "Lines" },
  { id: "parallelStraightLine", label: "Parallel Channel", group: "Lines" },
  { id: "horizontalStraightLine", label: "Horizontal Line", group: "Lines" },
  { id: "verticalStraightLine", label: "Vertical Line", group: "Lines" },
  // Shapes
  { id: "rect", label: "Rectangle", group: "Shapes" },
  { id: "circle", label: "Circle", group: "Shapes" },
  { id: "triangle", label: "Triangle", group: "Shapes" },
  { id: "diamond", label: "Diamond", group: "Shapes" },
  // Fibonacci
  { id: "fibonacciRetracement", label: "Fib Retracement", group: "Fibonacci" },
  { id: "fibonacciExtension", label: "Fib Extension", group: "Fibonacci" },
  // Elliott
  { id: "elliottImpulse", label: "Impulse (1-2-3-4-5)", group: "Elliott" },
  { id: "elliottABC", label: "ABC", group: "Elliott" },
  { id: "elliottTriangle", label: "Triangle (ABCDE)", group: "Elliott" },
  { id: "elliottWXY", label: "W-X-Y", group: "Elliott" },
];

const GROUP_ORDER = ["Lines", "Shapes", "Fibonacci", "Elliott"] as const;
type GroupKey = typeof GROUP_ORDER[number];

export default function OverlaySidebar({ onCreate, onClear }: Props) {
  const railRef = useRef<HTMLDivElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const [activeGroup, setActiveGroup] = useState<GroupKey | null>(null);
  const [selectedByGroup, setSelectedByGroup] = useState<Partial<Record<GroupKey, string>>>({});

  const GROUPS = useMemo(
    () =>
      GROUP_ORDER.map((key) => ({
        key,
        items: ALL_ITEMS.filter((i) => i.group === key),
      })),
    []
  );

  // Close on outside click or Esc
  useEffect(() => {
    const onDown = (e: MouseEvent) => {
      const rail = railRef.current;
      const panel = panelRef.current;
      const t = e.target as Node;
      if (panel && panel.contains(t)) return;
      if (rail && rail.contains(t)) return;
      setActiveGroup(null);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setActiveGroup(null);
    };
    window.addEventListener("mousedown", onDown);
    window.addEventListener("keydown", onKey);
    return () => {
      window.removeEventListener("mousedown", onDown);
      window.removeEventListener("keydown", onKey);
    };
  }, []);

  // Layout constants
  const BTN = 40;
  const GAP = 8;

  const getGroupTop = (g: GroupKey) => {
    const idx = GROUP_ORDER.indexOf(g);
    return 8 + idx * (BTN + GAP);
  };

  return (
    <div
      style={{
        position: "absolute",
        top: 0,
        left: 0,
        padding: 8,
        zIndex: 10,
        pointerEvents: "none",
      }}
    >
      {/* Rail (collapsed by default) */}
      <div
        ref={railRef}
        style={{
          width: 44,
          display: "flex",
          flexDirection: "column",
          gap: GAP,
          pointerEvents: "auto",
        }}
      >
        {GROUPS.map((g) => (
          <button
            key={g.key}
            onClick={() => setActiveGroup((prev) => (prev === g.key ? null : g.key))}
            aria-label={g.key}
            title={g.key}
            style={{
              width: 44,
              height: BTN,
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              borderRadius: 8,
              border: "1px solid #e6e6e6",
              background: "#fff",
              cursor: "pointer",
              boxShadow: "0 2px 6px rgba(0,0,0,0.08)",
              position: "relative",
            }}
          >
            {selectedByGroup[g.key] ? <Icon id={selectedByGroup[g.key]!} /> : <GroupIcon group={g.key} />}
            <span
              style={{
                position: "absolute",
                right: 4,
                top: "50%",
                transform: `translateY(-50%) rotate(${activeGroup === g.key ? 90 : 0}deg)`,
                color: "#888",
                fontSize: 10,
                lineHeight: 1,
                userSelect: "none",
              }}
            >
              ▶
            </span>
          </button>
        ))}
      </div>

      {/* Flyout panel */}
      {activeGroup && (
        <div
          ref={panelRef}
          style={{
            position: "absolute",
            left: 52,
            top: getGroupTop(activeGroup),
            width: 240,
            maxHeight: 280,
            overflow: "auto",
            background: "#fff",
            border: "1px solid #e0e0e0",
            borderRadius: 8,
            boxShadow: "0 8px 20px rgba(0,0,0,0.15)",
            padding: 8,
            pointerEvents: "auto",
          }}
        >
          {/* Grid of overlay icons */}
          <div style={{ display: "grid", gap: 8, gridTemplateColumns: "repeat(3, 1fr)" }}>
            {GROUPS.find((g) => g.key === activeGroup)!.items.map((it) => (
              <button
                key={it.id}
                onClick={() => {
                  onCreate(it.id);
                  if (activeGroup) {
                    setSelectedByGroup((prev) => ({ ...prev, [activeGroup]: it.id }));
                    setActiveGroup(null);
                  }
                }}
                aria-label={it.label}
                title={it.label}
                style={{
                  height: 44,
                  display: "flex",
                  alignItems: "center",
                  justifyContent: "center",
                  borderRadius: 8,
                  border: "1px solid #eee",
                  background: "#fff",
                  cursor: "pointer",
                }}
              >
                <Icon id={it.id} />
              </button>
            ))}
          </div>

          {/* Footer */}
          <div style={{ display: "flex", gap: 8, marginTop: 8 }}>
            <button
              onClick={onClear}
              title="Remove all overlays"
              style={{
                flex: 1,
                padding: "6px 8px",
                borderRadius: 6,
                border: "1px solid #ddd",
                background: "#fafafa",
                cursor: "pointer",
              }}
            >
              Clear
            </button>
            <button
              onClick={() => setActiveGroup(null)}
              title="Close"
              style={{
                padding: "6px 8px",
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
      )}
    </div>
  );
}
