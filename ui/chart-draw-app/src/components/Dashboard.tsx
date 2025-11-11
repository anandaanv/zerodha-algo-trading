import React from "react";
import { useNavigate } from "react-router-dom";

export default function Dashboard() {
  const navigate = useNavigate();

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
      }}
    >
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
        {/* Screeners Card */}
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

        {/* Charts Card */}
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
          <div
            style={{
              fontSize: "3rem",
              marginBottom: "1rem",
            }}
          >
            📈
          </div>
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

        {/* Trades Card */}
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
          <div
            style={{
              fontSize: "3rem",
              marginBottom: "1rem",
            }}
          >
            💼
          </div>
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
      </div>
    </div>
  );
}
