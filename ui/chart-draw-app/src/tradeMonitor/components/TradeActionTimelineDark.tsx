import { useEffect, useState } from "react";
import { TradeAction, fetchTradeActions } from "../../trades/tradesApi";

export function TradeActionTimelineDark({ signalId }: { signalId: number }) {
  const [actions, setActions] = useState<TradeAction[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetchTradeActions(signalId).then(data => {
      setActions(data);
      setLoading(false);
    });
  }, [signalId]);

  if (loading) return <div style={{ padding: "10px", color: "#ddd" }}>Loading...</div>;
  if (actions.length === 0) return <div style={{ padding: "10px", color: "#999" }}>No action history</div>;

  return (
    <div style={{ padding: "10px 20px", background: "#2a2a2a", borderLeft: "3px solid #ff6b35" }}>
      <div style={{ fontWeight: 600, marginBottom: "8px", fontSize: "13px", color: "#ddd" }}>
        Trade Action History
      </div>
      <table style={{ width: "100%", fontSize: "12px", borderCollapse: "collapse" }}>
        <thead>
          <tr style={{ borderBottom: "1px solid #333", color: "#888" }}>
            <th style={{ textAlign: "left", padding: "4px 8px" }}>Time</th>
            <th style={{ textAlign: "left", padding: "4px 8px" }}>Action</th>
            <th style={{ textAlign: "right", padding: "4px 8px" }}>Price</th>
            <th style={{ textAlign: "right", padding: "4px 8px" }}>Stop</th>
            <th style={{ textAlign: "center", padding: "4px 8px" }}>Slab</th>
            <th style={{ textAlign: "right", padding: "4px 8px" }}>P&amp;L</th>
            <th style={{ textAlign: "left", padding: "4px 8px" }}>Detail</th>
          </tr>
        </thead>
        <tbody>
          {actions.map((a) => (
            <tr key={a.id} style={{ borderBottom: "1px solid #333" }}>
              <td style={{ padding: "4px 8px", whiteSpace: "nowrap", color: "#ddd" }}>
                {new Date(a.timestamp).toLocaleString("en-IN", { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" })}
              </td>
              <td style={{ padding: "4px 8px" }}>
                <ActionBadge action={a.action} />
              </td>
              <td style={{ textAlign: "right", padding: "4px 8px", color: "#ddd" }}>
                {a.price?.toFixed(2) ?? "—"}
              </td>
              <td style={{ textAlign: "right", padding: "4px 8px", color: "#ddd" }}>
                {a.stopLevel?.toFixed(2) ?? "—"}
              </td>
              <td style={{ textAlign: "center", padding: "4px 8px", color: "#ddd" }}>
                {a.slabIndex != null && a.slabIndex >= 0 ? a.slabIndex : "—"}
              </td>
              <td style={{ textAlign: "right", padding: "4px 8px", color: a.pnlPct && a.pnlPct > 0 ? "#4caf50" : a.pnlPct && a.pnlPct < 0 ? "#ef5350" : "#ddd" }}>
                {a.pnlPct != null ? `${a.pnlPct > 0 ? "+" : ""}${a.pnlPct.toFixed(2)}%` : "—"}
              </td>
              <td style={{ padding: "4px 8px", color: "#888", maxWidth: "300px", overflow: "hidden", textOverflow: "ellipsis" }}>
                {a.detail ?? ""}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function ActionBadge({ action }: { action: string }) {
  const colors: Record<string, { bg: string; fg: string }> = {
    SIGNAL_CREATED: { bg: "#dbeafe", fg: "#1d4ed8" },
    ENTRY_FILLED: { bg: "#d1fae5", fg: "#065f46" },
    SLAB_HIT: { bg: "#fef3c7", fg: "#92400e" },
    STOP_HIT: { bg: "#fee2e2", fg: "#991b1b" },
    TARGET_HIT: { bg: "#d1fae5", fg: "#065f46" },
    TIMEOUT_EXIT: { bg: "#f3f4f6", fg: "#374151" },
    STOP_UPDATED: { bg: "#e0e7ff", fg: "#3730a3" },
  };
  const c = colors[action] || { bg: "#f3f4f6", fg: "#374151" };
  return (
    <span style={{ background: c.bg, color: c.fg, padding: "2px 8px", borderRadius: "4px", fontSize: "11px", fontWeight: 600 }}>
      {action.replace(/_/g, " ")}
    </span>
  );
}
