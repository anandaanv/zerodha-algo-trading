import React from "react";

type Props = {
  data: unknown | string;
  className?: string;
  style?: React.CSSProperties;
};

function safeFormat(value: unknown | string): string {
  try {
    if (typeof value === "string") {
      const trimmed = value.trim();
      const looksLikeJson =
        (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
        (trimmed.startsWith("[") && trimmed.endsWith("]"));
      if (looksLikeJson) {
        const parsed = JSON.parse(trimmed);
        return JSON.stringify(parsed, null, 2);
      }
      return value;
    }
    return JSON.stringify(value ?? {}, null, 2);
  } catch {
    try {
      return JSON.stringify(value, null, 2);
    } catch {
      return String(value);
    }
  }
}

export const PrettyJson: React.FC<Props> = ({ data, className, style }) => {
  const formatted = safeFormat(data);
  return (
    <pre
      className={className}
      style={{
        whiteSpace: "pre-wrap",
        background: "#f3f4f6",
        color: "#111827",
        padding: 12,
        borderRadius: 6,
        overflowX: "auto",
        fontFamily:
          "ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', 'Courier New', monospace",
        fontSize: 12,
        ...style,
      }}
    >
{formatted}
    </pre>
  );
};
