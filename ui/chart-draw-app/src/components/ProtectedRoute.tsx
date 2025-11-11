import React from "react";
import { Navigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext";

interface ProtectedRouteProps {
  children: React.ReactNode;
  requiredRole?: string; // USER, MODERATOR, or ADMIN
}

export default function ProtectedRoute({ children, requiredRole }: ProtectedRouteProps) {
  const { isAuthenticated, hasRole } = useAuth();

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }

  if (requiredRole && !hasRole(requiredRole)) {
    return (
      <div
        style={{
          display: "flex",
          flexDirection: "column",
          alignItems: "center",
          justifyContent: "center",
          minHeight: "100vh",
          padding: "20px",
        }}
      >
        <h1 style={{ fontSize: "2rem", marginBottom: "1rem", color: "#c33" }}>
          Access Denied
        </h1>
        <p style={{ fontSize: "1.1rem", color: "#666" }}>
          You don't have permission to access this page.
        </p>
      </div>
    );
  }

  return <>{children}</>;
}
