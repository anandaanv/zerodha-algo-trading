import { useEffect, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { apiFetch } from "../config/api";
import { withAuth } from "../utils/apiHelper";

/**
 * Component to handle Kite callback after successful authentication
 * URL format: /kite-success?action=login&type=login&status=success&request_token=xxx
 */
export default function KiteSuccess() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const handleCallback = async () => {
      try {
        // Extract request_token from URL
        const requestToken = searchParams.get("request_token");
        const status = searchParams.get("status");

        // Check if authentication was successful
        if (status !== "success") {
          setError("Kite authentication failed. Please try again.");
          setLoading(false);
          return;
        }

        if (!requestToken) {
          setError("Request token not found in callback URL.");
          setLoading(false);
          return;
        }

        // Send request_token to backend /app endpoint
        const response = await apiFetch(`/update-token?request_token=${requestToken}`, withAuth());

        if (!response.ok) {
          const errorText = await response.text();
          throw new Error(errorText || `Failed to initialize: ${response.status}`);
        }

        const result = await response.text();

        if (result === "success") {
          // Redirect to dashboard on success
          navigate("/dashboard");
        } else {
          setError("Unexpected response from server.");
          setLoading(false);
        }
      } catch (err) {
        console.error("Kite callback error:", err);
        setError(err instanceof Error ? err.message : "An error occurred during authentication.");
        setLoading(false);
      }
    };

    handleCallback();
  }, [searchParams, navigate]);

  if (loading) {
    return (
      <div
        style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          minHeight: "100vh",
          flexDirection: "column",
          gap: "16px",
        }}
      >
        <div>Processing Kite authentication...</div>
        <div style={{ fontSize: "14px", color: "#666" }}>
          Please wait while we complete the setup.
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div
        style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          minHeight: "100vh",
          flexDirection: "column",
          gap: "16px",
        }}
      >
        <div style={{ color: "#d32f2f", fontSize: "18px", fontWeight: "bold" }}>
          Authentication Error
        </div>
        <div style={{ fontSize: "14px", color: "#666" }}>{error}</div>
        <button
          onClick={() => navigate("/kite-login")}
          style={{
            padding: "10px 20px",
            borderRadius: "6px",
            border: "1px solid #1976d2",
            background: "#1976d2",
            color: "#fff",
            cursor: "pointer",
            fontSize: "14px",
            fontWeight: "600",
          }}
        >
          Try Again
        </button>
      </div>
    );
  }

  return null;
}
