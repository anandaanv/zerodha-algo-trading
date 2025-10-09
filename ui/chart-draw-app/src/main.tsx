import React from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter, Routes, Route, Navigate } from "react-router-dom";
import "./styles.css";
import "./screener/screener.css";
import ProApp from "./pro/ProApp";

// Screener pages
import ScreenerCreatePage from "./screener/pages/ScreenerCreatePage";
import ScreenerListPage from "./screener/pages/ScreenerListPage";
import ScreenerDetailPage from "./screener/pages/ScreenerDetailPage";

// Trades routes
import { tradesRoutes } from "./trades";

const root = createRoot(document.getElementById("root")!);
root.render(
  <BrowserRouter>
    <Routes>
      <Route path="/" element={<ProApp />} />
      <Route path="/screener" element={<ScreenerListPage />} />
      <Route path="/screener/new" element={<ScreenerCreatePage />} />
      <Route path="/screener/:id" element={<ScreenerDetailPage />} />

      {/* Trades */}
      {tradesRoutes.map((r) => (
        <Route key={r.path} path={r.path} element={r.element} />
      ))}

      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  </BrowserRouter>
);
