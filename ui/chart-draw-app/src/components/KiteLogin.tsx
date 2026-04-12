import { useEffect } from "react";
import { getApiUrl } from "../config/api";
import {withAuth} from "../utils/apiHelper";

/**
 * Component to redirect user to Kite login URL
 * The backend /kite-login endpoint returns the Kite authentication URL
 */
export default function KiteLogin() {
  useEffect(() => {
    // Redirect to backend endpoint which will redirect to Kite
    const kiteLoginUrl = getApiUrl("/api/admin/kite-configs/1/connect");
    window.location.href = kiteLoginUrl;
  }, []);

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
      <div>Redirecting to Kite login...</div>
      <div style={{ fontSize: "14px", color: "#666" }}>
        Please wait while we redirect you to Zerodha Kite for authentication.
      </div>
    </div>
  );
}
