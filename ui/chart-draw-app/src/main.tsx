import React from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter, Routes, Route, Navigate } from "react-router-dom";
import { GoogleOAuthProvider } from "@react-oauth/google";
import "./styles.css";
import "./screener/screener.css";
import { AuthProvider } from "./context/AuthContext";
import Layout from "./components/Layout";
import Login from "./components/Login";
import Dashboard from "./components/Dashboard";
import ProtectedRoute from "./components/ProtectedRoute";
import LegacyChartApp from "./legacy-chart/ProApp";
import TVChartApp from "./tradingview/TVChartApp";
import PromptBuilderPage from "./tradingview/PromptBuilderPage";

import KiteLogin from "./components/KiteLogin";
import KiteSuccess from "./components/KiteSuccess";

// Get Google OAuth Client ID from environment variable
// Set this in .env file: VITE_GOOGLE_OAUTH_CLIENT_ID=your_client_id_here
const GOOGLE_CLIENT_ID = import.meta.env.VITE_GOOGLE_OAUTH_CLIENT_ID || "";

// Screener pages
import ScreenerCreatePage from "./screener/pages/ScreenerCreatePage";
import ScreenerListPage from "./screener/pages/ScreenerListPage";
import ScreenerDetailPage from "./screener/pages/ScreenerDetailPage";

// Trades routes
import { tradesRoutes } from "./trades";

// Elliott Screener
import ElliottScreenerPage from "./elliottScreener/pages/ElliottScreenerPage";
import ScreenerStatusPage from "./elliottScreener/pages/ScreenerStatusPage";
import SuggestionChartView from "./elliottScreener/pages/SuggestionChartView";
import ScanPage from "./scan/pages/ScanPage";
import ScanChartView from "./scan/pages/ScanChartView";

// Kite Config Management
import KiteConfigPage from './admin/kiteConfig/KiteConfigPage';

// Group Management
import GroupManagementPage from './admin/groups/GroupManagementPage';

// Settings
import SettingsPage from './settings/SettingsPage';

// Trade Alerts
import TradeAlertToast from './elliottScreener/components/TradeAlertToast';

const root = createRoot(document.getElementById("root")!);
root.render(
  <GoogleOAuthProvider clientId={GOOGLE_CLIENT_ID}>
    <BrowserRouter>
      <AuthProvider>
        <Layout>
          <TradeAlertToast />
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
            <ProtectedRoute requiredRole="USER">
              <LegacyChartApp />
            </ProtectedRoute>
          }
        />
        <Route
          path="/chart"
          element={
            <ProtectedRoute requiredRole="ADMIN">
              <TVChartApp />
            </ProtectedRoute>
          }
        />
        <Route
          path="/prompt-builder"
          element={
            <ProtectedRoute requiredRole="ADMIN">
              <PromptBuilderPage />
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
              <ProtectedRoute requiredRole="ADMIN">{r.element}</ProtectedRoute>
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
        <Route
          path="/elliott-screener"
          element={
            <ProtectedRoute requiredRole="USER">
              <ElliottScreenerPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/elliott-screener/:id/status"
          element={
            <ProtectedRoute requiredRole="USER">
              <ScreenerStatusPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/suggestion-chart/:suggestionId"
          element={
            <ProtectedRoute requiredRole="USER">
              <SuggestionChartView />
            </ProtectedRoute>
          }
        />
        <Route
          path="/scan"
          element={
            <ProtectedRoute requiredRole="USER">
              <ScanPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/scan-chart/:scanId"
          element={
            <ProtectedRoute requiredRole="USER">
              <ScanChartView />
            </ProtectedRoute>
          }
        />
        <Route
          path="/admin/kite-config"
          element={
            <ProtectedRoute requiredRole="ADMIN">
              <KiteConfigPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/admin/groups"
          element={
            <ProtectedRoute requiredRole="ADMIN">
              <GroupManagementPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/settings"
          element={
            <ProtectedRoute requiredRole="USER">
              <SettingsPage />
            </ProtectedRoute>
          }
        />
        <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
        </Layout>
      </AuthProvider>
    </BrowserRouter>
  </GoogleOAuthProvider>
);
