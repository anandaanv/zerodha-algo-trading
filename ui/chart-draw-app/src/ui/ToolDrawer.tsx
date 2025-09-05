import React, { useState } from "react";
import { Icons } from "./icons";
import type { Tool } from "../types";

type Props = {
  current: Tool;
  onSelect: (tool: Tool) => void;
};

type Item = { key: Tool["kind"]; title: string; icon: string; make: () => Tool };

const items: Item[] = [
  { key: "cursor", title: "Cursor", icon: Icons.cursor, make: () => ({ kind: "cursor" }) },
  { key: "line", title: "Trend Line", icon: Icons.line, make: () => ({ kind: "line" }) },
  { key: "ray", title: "Ray", icon: Icons.ray, make: () => ({ kind: "ray" }) },
  { key: "extended-line", title: "Extended Line", icon: Icons.extLine, make: () => ({ kind: "extended-line" }) },
  { key: "horizontal-line", title: "Horizontal Line", icon: Icons.hline, make: () => ({ kind: "horizontal-line" }) },
  { key: "vertical-line", title: "Vertical Line", icon: Icons.vline, make: () => ({ kind: "vertical-line" }) },
  { key: "channel", title: "Parallel Channel", icon: Icons.channel, make: () => ({ kind: "channel" }) },
  { key: "rectangle", title: "Rectangle", icon: Icons.rect, make: () => ({ kind: "rectangle" }) },
  { key: "triangle", title: "Triangle", icon: Icons.triangle, make: () => ({ kind: "triangle" }) },
  { key: "text", title: "Text", icon: Icons.text, make: () => ({ kind: "text" }) },
  { key: "fib-retracement", title: "Fib Retracement", icon: Icons.fibRetrace, make: () => ({ kind: "fib-retracement" }) },
  { key: "fib-extension", title: "Fib Extension", icon: Icons.fibExt, make: () => ({ kind: "fib-extension" }) },
];

export default function ToolDrawer({ current, onSelect }: Props) {
  const [open, setOpen] = useState(true);

  return (
    <div className={`tool-drawer ${open ? "open" : "closed"}`}>
      <button className="drawer-toggle" onClick={() => setOpen(!open)} title={open ? "Collapse" : "Expand"}>
        {open ? "⟨" : "⟩"}
      </button>
      <div className="tool-grid">
        {items.map((it) => (
          <button
            key={it.key}
            className={`tool-button ${current.kind === it.key ? "active" : ""}`}
            onClick={() => onSelect(it.make())}
            title={it.title}
          >
            <img src={it.icon} alt={it.title} />
          </button>
        ))}
      </div>
    </div>
  );
}
