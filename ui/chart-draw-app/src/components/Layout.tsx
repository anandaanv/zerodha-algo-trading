import React from "react";

type Props = {
  children: React.ReactNode;
};

export default function Layout({ children }: Props) {
  return (
    <div style={{ display: "flex", flexDirection: "column", height: "100vh" }}>
      <div style={{ flex: 1, overflow: "auto", minHeight: 0 }}>
        {children}
      </div>

      {/* TradingView Attribution — permanent footer, always visible, never overlaps content */}
      <div
        style={{
          flexShrink: 0,
          textAlign: "center",
          padding: "6px",
          fontSize: 12,
          color: "#888",
          background: "#ffffff",
          borderTop: "1px solid #e0e0e0",
        }}
      >
        Charts powered by{" "}
        <a
          href="https://www.tradingview.com/"
          target="_blank"
          rel="noopener noreferrer"
          style={{ color: "#1976d2", textDecoration: "none" }}
        >
          TradingView
        </a>
      </div>
    </div>
  );
}
