import { useEffect, useState } from "react";
import { useParams, useSearchParams, useNavigate } from "react-router-dom";
import { getApiUrl } from "../config/api";
import { withAuth } from "../utils/apiHelper";

/**
 * Handles the Kite OAuth callback when Kite redirects to /kite-callback/config/:configId
 * Extracts request_token, calls backend process-token API, then navigates to admin page.
 */
export default function KiteCallback() {
  const { configId } = useParams<{ configId: string }>();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const processToken = async () => {
      const requestToken = searchParams.get("request_token");
      const status = searchParams.get("status");

      if (status !== "success") {
        setError("Kite authentication failed. Please try again.");
        return;
      }

      if (!requestToken) {
        setError("Request token not found in callback URL.");
        return;
      }

      try {
        const url = getApiUrl(`/api/admin/kite-configs/${configId}/process-token?request_token=${requestToken}`).toString();
        console.log("[KiteCallback] calling process-token:", url);
        const res = await fetch(url, { ...withAuth(), method: "POST" });
        if (!res.ok) {
          const body = await res.json().catch(() => ({}));
          throw new Error(body.message || `Server error ${res.status}`);
        }
        console.log("[KiteCallback] success, navigating to /admin/kite-config");
        navigate("/admin/kite-config", { replace: true });
      } catch (err) {
        console.error("[KiteCallback] error:", err);
        setError(err instanceof Error ? err.message : "Authentication failed.");
      }
    };

    processToken();
  }, [configId, searchParams, navigate]);

  if (error) {
    return (
      <div style={{ display: "flex", alignItems: "center", justifyContent: "center", minHeight: "100vh", flexDirection: "column", gap: "16px" }}>
        <div style={{ color: "#d32f2f", fontSize: "18px", fontWeight: "bold" }}>Authentication Error</div>
        <div style={{ fontSize: "14px", color: "#666" }}>{error}</div>
        <button
          onClick={() => window.location.href = "/kite-login"}
          style={{ padding: "10px 20px", borderRadius: "6px", border: "1px solid #1976d2", background: "#1976d2", color: "#fff", cursor: "pointer", fontSize: "14px", fontWeight: "600" }}
        >
          Try Again
        </button>
      </div>
    );
  }

  return (
    <div style={{ display: "flex", alignItems: "center", justifyContent: "center", minHeight: "100vh", flexDirection: "column", gap: "16px" }}>
      <div>Processing Kite authentication...</div>
      <div style={{ fontSize: "14px", color: "#666" }}>Please wait while we complete the setup.</div>
    </div>
  );
}
