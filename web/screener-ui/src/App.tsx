import React from 'react';
import { Routes, Route, Link, Navigate } from 'react-router-dom';
import ScreenerCreatePage from './pages/ScreenerCreatePage';
import ScreenerListPage from './pages/ScreenerListPage';
import ScreenerDetailPage from './pages/ScreenerDetailPage';

export default function App() {
  return (
    <div className="container">
      <nav className="toolbar" style={{ marginBottom: 16 }}>
        <Link className="btn" to="/">Home</Link>
        <Link className="btn" to="/screeners">Screeners</Link>
        <Link className="btn primary" to="/screeners/new">New Screener</Link>
      </nav>

      <Routes>
        <Route
          path="/"
          element={
            <div className="card">
              <h1>Welcome</h1>
              <p className="muted">Use the navigation to view or create screeners.</p>
            </div>
          }
        />
        <Route path="/screeners" element={<ScreenerListPage />} />
        <Route path="/screeners/new" element={<ScreenerCreatePage />} />
        <Route path="/screeners/:id" element={<ScreenerDetailPage />} />
        <Route path="*" element={<Navigate to="/screeners" replace />} />
      </Routes>
    </div>
  );
}
