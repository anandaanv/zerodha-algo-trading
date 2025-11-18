import React, { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import SyncModal from "./SyncModal";

export default function Dashboard() {
  const navigate = useNavigate();
  const { user, logout, hasRole } = useAuth();
  const [showSyncModal, setShowSyncModal] = useState(false);

  return (
    <div
      style={{
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        justifyContent: "center",
        minHeight: "100vh",
        background: "linear-gradient(135deg, #667eea 0%, #764ba2 100%)",
        padding: "20px",
        position: "relative",
      }}
    >
      {/* Logout and Sync buttons */}
      <div
        style={{
          position: "absolute",
          top: "20px",
          right: "20px",
          display: "flex",
          alignItems: "center",
          gap: "1rem",
        }}
      >
        <span style={{ color: "#fff", fontWeight: 500 }}>
          {user?.username} ({user?.role})
        </span>
        {user?.role === "ADMIN" && (
          <button
            onClick={() => setShowSyncModal(true)}
            style={{
              padding: "0.5rem 1rem",
              borderRadius: "8px",
              border: "1px solid #fff",
              background: "rgba(255, 255, 255, 0.2)",
              color: "#fff",
              cursor: "pointer",
              fontWeight: 500,
            }}
          >
            Sync
          </button>
        )}
        <button
          onClick={() => navigate("/kite-login")}
          style={{
            padding: "0.5rem 1rem",
            borderRadius: "8px",
            border: "1px solid #fff",
            background: "rgba(255, 255, 255, 0.2)",
            color: "#fff",
            cursor: "pointer",
            fontWeight: 500,
          }}
        >
          Kite Login
        </button>
        <button
          onClick={logout}
          style={{
            padding: "0.5rem 1rem",
            borderRadius: "8px",
            border: "1px solid #fff",
            background: "rgba(255, 255, 255, 0.2)",
            color: "#fff",
            cursor: "pointer",
            fontWeight: 500,
          }}
        >
          Logout
        </button>
      </div>

      <h1
        style={{
          color: "#fff",
          fontSize: "3rem",
          marginBottom: "3rem",
          fontWeight: 700,
          textShadow: "0 2px 10px rgba(0,0,0,0.2)",
        }}
      >
        Trading Dashboard
      </h1>

      <div
        style={{
          display: "grid",
          gridTemplateColumns: "repeat(auto-fit, minmax(280px, 1fr))",
          gap: "2rem",
          maxWidth: "900px",
          width: "100%",
        }}
      >
        {/* Trades Card - USER, MODERATOR, ADMIN */}
        {hasRole("USER") && (
          <div
            onClick={() => navigate("/trades")}
            style={{
              background: "#fff",
              borderRadius: "16px",
              padding: "2.5rem",
              cursor: "pointer",
              boxShadow: "0 10px 30px rgba(0,0,0,0.15)",
              transition: "transform 0.2s, box-shadow 0.2s",
              textAlign: "center",
            }}
            onMouseEnter={(e) => {
              e.currentTarget.style.transform = "translateY(-8px)";
              e.currentTarget.style.boxShadow = "0 20px 40px rgba(0,0,0,0.2)";
            }}
            onMouseLeave={(e) => {
              e.currentTarget.style.transform = "translateY(0)";
              e.currentTarget.style.boxShadow = "0 10px 30px rgba(0,0,0,0.15)";
            }}
          >
            <div style={{ fontSize: "3rem", marginBottom: "1rem" }}>💼</div>
            <h2
              style={{
                fontSize: "1.5rem",
                marginBottom: "0.5rem",
                color: "#333",
                fontWeight: 600,
              }}
            >
              Trades
            </h2>
            <p style={{ color: "#666", fontSize: "0.95rem" }}>
              View and manage your trading positions
            </p>
          </div>
        )}

        {/* Screeners Card - MODERATOR, ADMIN */}
        {hasRole("MODERATOR") && (
          <div
            onClick={() => navigate("/screener")}
            style={{
            background: "#fff",
            borderRadius: "16px",
            padding: "2.5rem",
            cursor: "pointer",
            boxShadow: "0 10px 30px rgba(0,0,0,0.15)",
            transition: "transform 0.2s, box-shadow 0.2s",
            textAlign: "center",
          }}
          onMouseEnter={(e) => {
            e.currentTarget.style.transform = "translateY(-8px)";
            e.currentTarget.style.boxShadow = "0 20px 40px rgba(0,0,0,0.2)";
          }}
          onMouseLeave={(e) => {
            e.currentTarget.style.transform = "translateY(0)";
            e.currentTarget.style.boxShadow = "0 10px 30px rgba(0,0,0,0.15)";
          }}
        >
          <div
            style={{
              fontSize: "3rem",
              marginBottom: "1rem",
            }}
          >
            🔍
          </div>
          <h2
            style={{
              fontSize: "1.5rem",
              marginBottom: "0.5rem",
              color: "#333",
              fontWeight: 600,
            }}
          >
            Screeners
          </h2>
          <p style={{ color: "#666", fontSize: "0.95rem" }}>
            Scan and filter stocks based on technical indicators
          </p>
        </div>
        )}

        {/* Charts Card - MODERATOR, ADMIN */}
        {hasRole("MODERATOR") && (
          <div
            onClick={() => navigate("/chart?indicators=true")}
            style={{
              background: "#fff",
              borderRadius: "16px",
              padding: "2.5rem",
              cursor: "pointer",
              boxShadow: "0 10px 30px rgba(0,0,0,0.15)",
              transition: "transform 0.2s, box-shadow 0.2s",
              textAlign: "center",
            }}
            onMouseEnter={(e) => {
              e.currentTarget.style.transform = "translateY(-8px)";
              e.currentTarget.style.boxShadow = "0 20px 40px rgba(0,0,0,0.2)";
            }}
            onMouseLeave={(e) => {
              e.currentTarget.style.transform = "translateY(0)";
              e.currentTarget.style.boxShadow = "0 10px 30px rgba(0,0,0,0.15)";
            }}
          >
            <div style={{ fontSize: "3rem", marginBottom: "1rem" }}>📈</div>
            <h2
              style={{
                fontSize: "1.5rem",
                marginBottom: "0.5rem",
                color: "#333",
                fontWeight: 600,
              }}
            >
              Charts
            </h2>
            <p style={{ color: "#666", fontSize: "0.95rem" }}>
              Advanced charting with technical analysis tools
            </p>
          </div>
        )}
      </div>

      {/* Sync Modal */}
      <SyncModal isOpen={showSyncModal} onClose={() => setShowSyncModal(false)} />
    </div>
  );
}
