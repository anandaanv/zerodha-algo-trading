// Central API base configuration for the React app (Vite)
// Priority:
// 1) import.meta.env.VITE_API_BASE_URL
// 2) window.__API_BASE_URL__ (runtime override)
// 3) window.location.origin (same-origin fallback)
export const API_BASE_URL: string = (() => {
  // Vite env
  const fromEnv = (typeof import.meta !== "undefined" && (import.meta as any)?.env?.VITE_API_BASE_URL) as string | undefined;
  if (fromEnv && typeof fromEnv === "string" && fromEnv.trim().length > 0) return fromEnv.trim();

  // Runtime global (optional; can be set before app boot)
  if (typeof window !== "undefined" && (window as any).__API_BASE_URL__) {
    const runtime = String((window as any).__API_BASE_URL__);
    if (runtime.trim().length > 0) return runtime.trim();
  }

  // Fallback to current origin
  if (typeof window !== "undefined" && window.location?.origin) {
    return window.location.origin;
  }

  // Last resort
  return "";
})();

// Helper to build absolute API URLs
export const getApiUrl = (path: string): string => {
  const p = path?.startsWith("/") ? path : `/${path ?? ""}`;
  return new URL(p, API_BASE_URL || window.location.origin).toString();
};
