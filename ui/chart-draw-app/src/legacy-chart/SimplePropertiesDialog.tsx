import React, { useEffect, useState } from "react";

export type SimpleStyle = {
  color: string;
  width: number;
  style: "solid" | "dashed";
};

type Props = {
  open: boolean;
  initial: SimpleStyle;
  onApply: (s: SimpleStyle) => void;
  onClose: () => void;
};

export default function SimplePropertiesDialog({ open, initial, onApply, onClose }: Props) {
  const [draft, setDraft] = useState<SimpleStyle>(initial);

  useEffect(() => {
    setDraft(initial);
  }, [initial]);

  if (!open) return null;

  return (
    <div
      role="dialog"
      aria-modal="true"
      style={{
        position: "absolute",
        inset: 0,
        zIndex: 2000,
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        background: "rgba(0,0,0,0.35)",
      }}
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div
        style={{
          width: 300,
          maxWidth: "95%",
          background: "#fff",
          borderRadius: 10,
          boxShadow: "0 12px 30px rgba(0,0,0,0.25)",
          padding: 16,
        }}
        onMouseDown={(e) => e.stopPropagation()}
      >
        <div style={{ fontWeight: 600, marginBottom: 12 }}>Properties</div>

        <div style={{ display: "grid", gridTemplateColumns: "110px 1fr", rowGap: 10, columnGap: 12 }}>
          <label style={{ alignSelf: "center" }}>Color</label>
          <input
            type="color"
            value={draft.color}
            onChange={(e) => setDraft((d) => ({ ...d, color: e.target.value }))}
            style={{ width: 48, height: 28, border: "1px solid #ddd", borderRadius: 4, padding: 0 }}
          />

          <label style={{ alignSelf: "center" }}>Width</label>
          <input
            type="number"
            min={1}
            max={10}
            step={0.5}
            value={draft.width}
            onChange={(e) => setDraft((d) => ({ ...d, width: Number(e.target.value) }))}
            style={{ width: 100 }}
          />

          <label style={{ alignSelf: "center" }}>Style</label>
          <select
            value={draft.style}
            onChange={(e) => setDraft((d) => ({ ...d, style: e.target.value as "solid" | "dashed" }))}
          >
            <option value="solid">Solid</option>
            <option value="dashed">Dashed</option>
          </select>
        </div>

        <div style={{ marginTop: 16, display: "flex", justifyContent: "flex-end", gap: 8 }}>
          <button
            onClick={onClose}
            style={{ padding: "6px 10px", borderRadius: 6, border: "1px solid #ddd", background: "#fff", cursor: "pointer" }}
          >
            Cancel
          </button>
          <button
            onClick={() => onApply(draft)}
            style={{
              padding: "6px 10px",
              borderRadius: 6,
              border: "1px solid #1976d2",
              background: "#1976d2",
              color: "#fff",
              cursor: "pointer",
            }}
          >
            Apply
          </button>
        </div>
      </div>
    </div>
  );
}
