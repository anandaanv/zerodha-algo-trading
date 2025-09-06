import React from "react";
type Props = {
  onCreate: (id: string) => void;
  onClear: () => void;
};

const groups: { title: string; items: { id: string; label: string }[] }[] = [
  {
    title: "Lines",
    items: [
      { id: "straightLine", label: "Line" },
      { id: "rayLine", label: "Ray" },
      { id: "segment", label: "Segment" },
      { id: "parallelStraightLine", label: "Channel" },
      { id: "horizontalStraightLine", label: "H-Line" },
      { id: "verticalStraightLine", label: "V-Line" },
    ],
  },
  {
    title: "Shapes",
    items: [
      { id: "rect", label: "Rect" },
      { id: "circle", label: "Circle" },
      { id: "triangle", label: "Triangle" },
      { id: "diamond", label: "Diamond" },
    ],
  },
  {
    title: "Fibonacci",
    items: [
      { id: "fibonacciRetracement", label: "Retr" },
      { id: "fibonacciExtension", label: "Ext" },
    ],
  },
];

export default function OverlayToolbar({ onCreate, onClear }: Props) {
  return (
    <div className="kline-toolbar">
      {groups.map((g) => (
        <div key={g.title} className="kline-toolbar-group">
          <div className="kline-toolbar-title">{g.title}</div>
          <div className="kline-toolbar-grid">
            {g.items.map((it) => (
              <button key={it.id} className="kline-tool-btn" onClick={() => onCreate(it.id)} title={it.id}>
                {it.label}
              </button>
            ))}
          </div>
        </div>
      ))}
      <div className="kline-toolbar-footer">
        <button className="kline-tool-btn danger" onClick={onClear} title="Remove all">
          Clear
        </button>
      </div>
    </div>
  );
}
