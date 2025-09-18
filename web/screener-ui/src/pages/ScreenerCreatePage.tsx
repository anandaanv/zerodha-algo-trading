import React from 'react';
import ScreenerForm from '../components/ScreenerForm';

export default function ScreenerCreatePage() {
  return (
    <div className="card">
      <h1>Create Screener</h1>
      <p className="muted">
        Compose your screener by providing the script, mapping/workflow config, optional OpenAI prompt, and chart intervals.
      </p>
      <ScreenerForm />
    </div>
  );
}
