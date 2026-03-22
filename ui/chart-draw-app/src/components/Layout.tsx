import React from "react";
import { createPortal } from "react-dom";

type Props = {
  children: React.ReactNode;
};

// Rendered via portal into document.body so it is completely outside any page
// stacking context (transforms, filters, etc. cannot affect position: fixed on
// a portal child of body). pointerEvents: none means it never blocks clicks.
const Attribution = () =>
  createPortal(
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
        zIndex: 99999,
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
    </div>,
    document.body
  );

export default function Layout({ children }: Props) {
  return (
    <div style={{ position: "relative", minHeight: "100vh" }}>
      {children}
      <Attribution />
    </div>
  );
}
