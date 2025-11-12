import React, { useState, useEffect } from "react";
import { withAuth } from "../utils/apiHelper";

interface SyncModalProps {
  isOpen: boolean;
  onClose: () => void;
}

interface RemoteSyncConfig {
  apiUrl: string;
  username: string;
  password: string;
}

export default function SyncModal({ isOpen, onClose }: SyncModalProps) {
  const [config, setConfig] = useState<RemoteSyncConfig | null>(null);
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState<{ type: "success" | "error"; text: string } | null>(null);

  useEffect(() => {
    if (isOpen) {
      fetchConfig();
    }
  }, [isOpen]);

  const fetchConfig = async () => {
    try {
      const response = await fetch("/api/remote-sync/config", withAuth());

      if (!response.ok) {
        throw new Error("Failed to fetch sync configuration");
      }

      const data = await response.json();
      setConfig(data);
    } catch (error) {
      setMessage({ type: "error", text: "Failed to load sync configuration" });
    }
  };

  const syncFromRemote = async () => {
    if (!config || !config.apiUrl) {
      setMessage({ type: "error", text: "Remote sync not configured" });
      return;
    }

    setLoading(true);
    setMessage(null);

    try {
      // Step 1: Login to remote server
      const loginResponse = await fetch(`${config.apiUrl}/api/auth/login`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          username: config.username,
          password: config.password,
        }),
      });

      if (!loginResponse.ok) {
        throw new Error("Failed to login to remote server");
      }

      const loginData = await loginResponse.json();
      const remoteToken = loginData.token;

      // Step 2: Export from remote
      const exportResponse = await fetch(`${config.apiUrl}/api/chart-state/export`, {
        headers: {
          Authorization: `Bearer ${remoteToken}`,
        },
      });

      if (!exportResponse.ok) {
        throw new Error("Failed to export from remote server");
      }

      const exportData = await exportResponse.json();

      // Step 3: Import to local
      const importResponse = await fetch("/api/chart-state/import", withAuth({
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify(exportData),
      }));

      if (!importResponse.ok) {
        throw new Error("Failed to import to local server");
      }

      const importResult = await importResponse.json();
      setMessage({
        type: "success",
        text: `Successfully synced ${importResult.imported} of ${importResult.total} chart states from remote`,
      });
    } catch (error) {
      setMessage({
        type: "error",
        text: error instanceof Error ? error.message : "Sync failed",
      });
    } finally {
      setLoading(false);
    }
  };

  const syncToRemote = async () => {
    if (!config || !config.apiUrl) {
      setMessage({ type: "error", text: "Remote sync not configured" });
      return;
    }

    setLoading(true);
    setMessage(null);

    try {
      // Step 1: Export from local
      const exportResponse = await fetch("/api/chart-state/export", withAuth());

      if (!exportResponse.ok) {
        throw new Error("Failed to export from local server");
      }

      const exportData = await exportResponse.json();

      // Step 2: Login to remote server
      const loginResponse = await fetch(`${config.apiUrl}/api/auth/login`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          username: config.username,
          password: config.password,
        }),
      });

      if (!loginResponse.ok) {
        throw new Error("Failed to login to remote server");
      }

      const loginData = await loginResponse.json();
      const remoteToken = loginData.token;

      // Step 3: Import to remote
      const importResponse = await fetch(`${config.apiUrl}/api/chart-state/import`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${remoteToken}`,
        },
        body: JSON.stringify(exportData),
      });

      if (!importResponse.ok) {
        throw new Error("Failed to import to remote server");
      }

      const importResult = await importResponse.json();
      setMessage({
        type: "success",
        text: `Successfully synced ${importResult.imported} of ${importResult.total} chart states to remote`,
      });
    } catch (error) {
      setMessage({
        type: "error",
        text: error instanceof Error ? error.message : "Sync failed",
      });
    } finally {
      setLoading(false);
    }
  };

  if (!isOpen) return null;

  return (
    <div
      style={{
        position: "fixed",
        top: 0,
        left: 0,
        right: 0,
        bottom: 0,
        backgroundColor: "rgba(0, 0, 0, 0.7)",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        zIndex: 1000,
      }}
      onClick={onClose}
    >
      <div
        style={{
          backgroundColor: "#1a1a2e",
          padding: "2rem",
          borderRadius: "8px",
          minWidth: "400px",
          maxWidth: "500px",
        }}
        onClick={(e) => e.stopPropagation()}
      >
        <h2 style={{ marginTop: 0, marginBottom: "1.5rem", color: "#fff" }}>Sync Chart States</h2>

        {message && (
          <div
            style={{
              padding: "0.75rem",
              marginBottom: "1rem",
              borderRadius: "4px",
              backgroundColor: message.type === "success" ? "#10b981" : "#ef4444",
              color: "#fff",
            }}
          >
            {message.text}
          </div>
        )}

        <div style={{ display: "flex", flexDirection: "column", gap: "1rem" }}>
          <button
            onClick={syncFromRemote}
            disabled={loading}
            style={{
              padding: "0.75rem 1.5rem",
              fontSize: "1rem",
              fontWeight: "600",
              color: "#fff",
              backgroundColor: loading ? "#4b5563" : "#3b82f6",
              border: "none",
              borderRadius: "4px",
              cursor: loading ? "not-allowed" : "pointer",
              transition: "background-color 0.2s",
            }}
            onMouseEnter={(e) => {
              if (!loading) e.currentTarget.style.backgroundColor = "#2563eb";
            }}
            onMouseLeave={(e) => {
              if (!loading) e.currentTarget.style.backgroundColor = "#3b82f6";
            }}
          >
            {loading ? "Syncing..." : "Sync From Remote"}
          </button>

          <button
            onClick={syncToRemote}
            disabled={loading}
            style={{
              padding: "0.75rem 1.5rem",
              fontSize: "1rem",
              fontWeight: "600",
              color: "#fff",
              backgroundColor: loading ? "#4b5563" : "#10b981",
              border: "none",
              borderRadius: "4px",
              cursor: loading ? "not-allowed" : "pointer",
              transition: "background-color 0.2s",
            }}
            onMouseEnter={(e) => {
              if (!loading) e.currentTarget.style.backgroundColor = "#059669";
            }}
            onMouseLeave={(e) => {
              if (!loading) e.currentTarget.style.backgroundColor = "#10b981";
            }}
          >
            {loading ? "Syncing..." : "Sync To Remote"}
          </button>

          <button
            onClick={onClose}
            disabled={loading}
            style={{
              padding: "0.75rem 1.5rem",
              fontSize: "1rem",
              fontWeight: "600",
              color: "#fff",
              backgroundColor: "#6b7280",
              border: "none",
              borderRadius: "4px",
              cursor: loading ? "not-allowed" : "pointer",
              transition: "background-color 0.2s",
            }}
            onMouseEnter={(e) => {
              if (!loading) e.currentTarget.style.backgroundColor = "#4b5563";
            }}
            onMouseLeave={(e) => {
              if (!loading) e.currentTarget.style.backgroundColor = "#6b7280";
            }}
          >
            Close
          </button>
        </div>
      </div>
    </div>
  );
}
