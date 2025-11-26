import React from "react";

type Props = {
  children: React.ReactNode;
};

export default function Layout({ children }: Props) {
  return (
    <div style={{ position: "relative", minHeight: "100vh" }}>
      {children}

      {/* TradingView Attribution Footer */}
      <div
        style={{
          position: "fixed",
          bottom: 0,
          left: 0,
          right: 0,
          textAlign: "center",
          padding: "8px",
          fontSize: 13,
          color: "#666",
          background: "rgba(255,255,255,0.95)",
          borderTop: "1px solid #e0e0e0",
          zIndex: 9999,
          backdropFilter: "blur(4px)",
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
