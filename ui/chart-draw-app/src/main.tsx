import React from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter, Routes, Route, Navigate } from "react-router-dom";
import "./styles.css";
import "./screener/screener.css";
import { AuthProvider } from "./context/AuthContext";
import Layout from "./components/Layout";
import Login from "./components/Login";
import Dashboard from "./components/Dashboard";
import ProtectedRoute from "./components/ProtectedRoute";
import LegacyChartApp from "./legacy-chart/ProApp";
import TVChartApp from "./tradingview/TVChartApp";
import KiteLogin from "./components/KiteLogin";
import KiteSuccess from "./components/KiteSuccess";

// Screener pages
import ScreenerCreatePage from "./screener/pages/ScreenerCreatePage";
import ScreenerListPage from "./screener/pages/ScreenerListPage";
import ScreenerDetailPage from "./screener/pages/ScreenerDetailPage";

// Trades routes
import { tradesRoutes } from "./trades";

const root = createRoot(document.getElementById("root")!);
root.render(
  <BrowserRouter>
    <AuthProvider>
      <Layout>
        <Routes>
        {/* Public routes */}
        <Route path="/login" element={<Login />} />
        <Route path="/kite-login" element={<KiteLogin />} />
        <Route path="/kite-success" element={<KiteSuccess />} />

        {/* Protected routes */}
        <Route
          path="/dashboard"
          element={
            <ProtectedRoute>
              <Dashboard />
            </ProtectedRoute>
          }
        />
        <Route
          path="/chart-legacy"
          element={
            <ProtectedRoute requiredRole="MODERATOR">
              <LegacyChartApp />
            </ProtectedRoute>
          }
        />
        <Route
          path="/chart"
          element={
            <ProtectedRoute requiredRole="MODERATOR">
              <TVChartApp />
            </ProtectedRoute>
          }
        />
        <Route
          path="/screener"
          element={
            <ProtectedRoute requiredRole="MODERATOR">
              <ScreenerListPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/screener/new"
          element={
            <ProtectedRoute requiredRole="MODERATOR">
              <ScreenerCreatePage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/screener/:id"
          element={
            <ProtectedRoute requiredRole="MODERATOR">
              <ScreenerDetailPage />
            </ProtectedRoute>
          }
        />

        {/* Trades - accessible by all authenticated users */}
        {tradesRoutes.map((r) => (
          <Route
            key={r.path}
            path={r.path}
            element={
              <ProtectedRoute requiredRole="USER">{r.element}</ProtectedRoute>
            }
          />
        ))}

        <Route
          path="/"
          element={
            <ProtectedRoute>
              <Dashboard />
            </ProtectedRoute>
          }
        />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
      </Layout>
    </AuthProvider>
  </BrowserRouter>
);
