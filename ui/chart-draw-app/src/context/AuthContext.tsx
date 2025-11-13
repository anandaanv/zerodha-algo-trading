import React, { createContext, useContext, useState, useEffect, ReactNode } from "react";
import {apiFetch} from "../config/api";

interface AuthContextType {
  user: User | null;
  token: string | null;
  login: (username: string, password: string) => Promise<void>;
  logout: () => void;
  isAuthenticated: boolean;
  hasRole: (role: string) => boolean;
}

interface User {
  username: string;
  role: string;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [token, setToken] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  // Check if service token is present in URL
  const getServiceToken = (): string | null => {
    const params = new URLSearchParams(window.location.search);
    return params.get('servicetoken');
  };

  // Initialize auth state from localStorage or service token
  useEffect(() => {
    const serviceToken = getServiceToken();

    // If service token is present, use it (backend-to-frontend flow)
    if (serviceToken) {
      // Service token grants MODERATOR access
      setToken(serviceToken);
      setUser({
        username: "service-account",
        role: "MODERATOR",
      });
      setLoading(false);
      return;
    }

    // Otherwise, check localStorage for regular user login
    const storedToken = localStorage.getItem("auth_token");
    const storedUser = localStorage.getItem("auth_user");

    if (storedToken && storedUser) {
      setToken(storedToken);
      setUser(JSON.parse(storedUser));
    }
    setLoading(false);
  }, []);

  const login = async (username: string, password: string) => {
    const response = await apiFetch("/api/auth/login", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ username, password }),
    });

    if (!response.ok) {
      const error = await response.json();
      throw new Error(error.error || "Login failed");
    }

    const data = await response.json();
    const userData: User = {
      username: data.username,
      role: data.role,
    };

    setToken(data.token);
    setUser(userData);

    localStorage.setItem("auth_token", data.token);
    localStorage.setItem("auth_user", JSON.stringify(userData));
  };

  const logout = () => {
    setToken(null);
    setUser(null);
    localStorage.removeItem("auth_token");
    localStorage.removeItem("auth_user");
  };

  const hasRole = (role: string): boolean => {
    if (!user) return false;

    // ADMIN has access to everything
    if (user.role === "ADMIN") return true;

    // MODERATOR has access to MODERATOR and USER roles
    if (user.role === "MODERATOR" && (role === "MODERATOR" || role === "USER")) return true;

    // USER only has access to USER role
    return user.role === role;
  };

  const value: AuthContextType = {
    user,
    token,
    login,
    logout,
    isAuthenticated: !!token && !!user,
    hasRole,
  };

  if (loading) {
    return (
      <div
        style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          minHeight: "100vh",
        }}
      >
        Loading...
      </div>
    );
  }

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (context === undefined) {
    throw new Error("useAuth must be used within an AuthProvider");
  }
  return context;
}
