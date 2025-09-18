import React from "react";
import { Link } from "react-router-dom";
import ScreenerForm from "../components/ScreenerForm";

export default function ScreenerCreatePage() {
  return (
    <div className="container">
      <nav className="toolbar" style={{ marginBottom: 16 }}>
        <Link className="btn" to="/screener">Screeners</Link>
      </nav>
      <div className="card">
        <h1>Create Screener</h1>
        <p className="muted">
          Compose your screener by providing the script, mapping/workflow config, optional OpenAI prompt, and chart intervals.
        </p>
        <ScreenerForm />
      </div>
    </div>
  );
}
