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
          background: "#ffffff",
          borderTop: "1px solid #e0e0e0",
          zIndex: 9999,
          pointerEvents: "none",
        }}
      >
        Charts powered by{" "}
        <a
          href="https://www.tradingview.com/"
          target="_blank"
          rel="noopener noreferrer"
          style={{ color: "#1976d2", textDecoration: "none", pointerEvents: "auto" }}
        >
          TradingView
        </a>
      </div>
    </div>
  );
}
