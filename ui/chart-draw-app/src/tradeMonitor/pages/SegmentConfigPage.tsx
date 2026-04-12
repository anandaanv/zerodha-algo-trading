import React, { useState, useEffect } from "react";
import {
  getSegmentConfigs,
  createSegmentConfig,
  updateSegmentConfig,
  deleteSegmentConfig,
  seedSegmentConfigs,
  clearAllSegmentConfigs,
  SegmentConfig,
  TradingSegment,
} from "../api";

const SegmentConfigPage: React.FC = () => {
  const [configs, setConfigs] = useState<SegmentConfig[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>("");
  const [editingId, setEditingId] = useState<number | null>(null);
  const [newConfig, setNewConfig] = useState<Omit<SegmentConfig, "id"> | null>(
    null
  );
  const [seedList, setSeedList] = useState<'NIFTY50' | 'FNO'>('FNO');
  const [seedSegment, setSeedSegment] = useState<TradingSegment>('EQ');

  const fetchConfigs = async () => {
    setLoading(true);
    setError("");
    try {
      const data = await getSegmentConfigs();
      setConfigs(data);
    } catch (err) {
      setError(`Error loading configs: ${err}`);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchConfigs();
  }, []);

  const handleAdd = async () => {
    if (!newConfig) return;
    try {
      await createSegmentConfig(newConfig);
      setNewConfig(null);
      fetchConfigs();
    } catch (err) {
      setError(`Error creating config: ${err}`);
    }
  };

  const handleUpdate = async (id: number, config: SegmentConfig) => {
    try {
      await updateSegmentConfig(id, config);
      setEditingId(null);
      fetchConfigs();
    } catch (err) {
      setError(`Error updating config: ${err}`);
    }
  };

  const handleDelete = async (id: number) => {
    if (!window.confirm("Delete this configuration?")) return;
    try {
      await deleteSegmentConfig(id);
      fetchConfigs();
    } catch (err) {
      setError(`Error deleting config: ${err}`);
    }
  };

  const handleSeed = async () => {
    try {
      const result = await seedSegmentConfigs(seedList, seedSegment);
      alert(`Seeded ${result.seeded} configurations`);
      fetchConfigs();
    } catch (err) {
      setError(`Error seeding configs: ${err}`);
    }
  };

  const handleClearAll = async () => {
    if (!window.confirm('Delete ALL segment configurations? This cannot be undone.')) return;
    try {
      await clearAllSegmentConfigs();
      fetchConfigs();
    } catch (err) {
      setError(`Error clearing configs: ${err}`);
    }
  };

  const containerStyle: React.CSSProperties = {
    padding: 24,
    backgroundColor: "#1a1a1a",
    minHeight: "100vh",
    color: "#fff",
    fontFamily: "system-ui",
  };

  const headerStyle: React.CSSProperties = {
    display: "flex",
    justifyContent: "space-between",
    alignItems: "center",
    marginBottom: 24,
  };

  const titleStyle: React.CSSProperties = {
    fontSize: 24,
    fontWeight: "bold",
    margin: 0,
  };

  const buttonGroupStyle: React.CSSProperties = {
    display: "flex",
    gap: 12,
  };

  const buttonStyle: React.CSSProperties = {
    padding: "8px 16px",
    backgroundColor: "#90caf9",
    color: "#000",
    border: "none",
    borderRadius: 4,
    cursor: "pointer",
    fontWeight: "bold",
  };

  const secondaryButtonStyle: React.CSSProperties = {
    ...buttonStyle,
    backgroundColor: "#333",
    color: "#fff",
  };

  const tableStyle: React.CSSProperties = {
    width: "100%",
    borderCollapse: "collapse",
    marginTop: 24,
  };

  const thStyle: React.CSSProperties = {
    color: "#90caf9",
    textAlign: "left",
    padding: 12,
    borderBottom: "2px solid #333",
  };

  const tdStyle: React.CSSProperties = {
    padding: 12,
    borderBottom: "1px solid #333",
  };

  const enabledDotStyle = (enabled: boolean): React.CSSProperties => ({
    display: "inline-block",
    width: 12,
    height: 12,
    borderRadius: "50%",
    backgroundColor: enabled ? "#4caf50" : "#ef5350",
  });

  const inputStyle: React.CSSProperties = {
    padding: "6px 8px",
    backgroundColor: "#2a2a2a",
    color: "#fff",
    border: "1px solid #444",
    borderRadius: 4,
    fontSize: 14,
  };

  const cancelButtonStyle: React.CSSProperties = {
    ...secondaryButtonStyle,
    padding: "4px 8px",
    fontSize: 12,
  };

  if (loading) {
    return (
      <div style={containerStyle}>
        <p>Loading...</p>
      </div>
    );
  }

  return (
    <div style={containerStyle}>
      {error && (
        <div
          style={{
            backgroundColor: "#d32f2f",
            color: "#fff",
            padding: 12,
            borderRadius: 4,
            marginBottom: 16,
          }}
        >
          {error}
        </div>
      )}

      <div style={headerStyle}>
        <h1 style={titleStyle}>Segment Config</h1>
        <div style={buttonGroupStyle}>
          <select
            style={inputStyle}
            value={seedList}
            onChange={(e) => setSeedList(e.target.value as 'NIFTY50' | 'FNO')}
          >
            <option value="NIFTY50">Nifty50</option>
            <option value="FNO">FNO</option>
          </select>
          <select
            style={inputStyle}
            value={seedSegment}
            onChange={(e) => setSeedSegment(e.target.value as TradingSegment)}
          >
            <option value="EQ">EQ</option>
            <option value="FUT">FUT</option>
            <option value="OPT">OPT</option>
          </select>
          <button style={buttonStyle} onClick={handleSeed}>
            Seed
          </button>
          <button
            style={{ ...buttonStyle, backgroundColor: '#d32f2f', color: '#fff' }}
            onClick={handleClearAll}
          >
            Clear All
          </button>
          <button
            style={buttonStyle}
            onClick={() =>
              setNewConfig({
                symbol: "",
                segment: "EQ",
                enabled: false,
                capitalPct: 2.0,
              })
            }
          >
            Add New
          </button>
        </div>
      </div>

      <table style={tableStyle}>
        <thead>
          <tr>
            <th style={thStyle}>Symbol</th>
            <th style={thStyle}>Segment</th>
            <th style={thStyle}>Capital %</th>
            <th style={thStyle}>Enabled</th>
            <th style={thStyle}>Provider</th>
            <th style={thStyle}>Actions</th>
          </tr>
        </thead>
        <tbody>
          {newConfig && (
            <tr>
              <td style={tdStyle}>
                <input
                  type="text"
                  style={inputStyle}
                  value={newConfig.symbol}
                  onChange={(e) =>
                    setNewConfig({ ...newConfig, symbol: e.target.value })
                  }
                  placeholder="Symbol"
                />
              </td>
              <td style={tdStyle}>
                <select
                  style={inputStyle}
                  value={newConfig.segment}
                  onChange={(e) =>
                    setNewConfig({
                      ...newConfig,
                      segment: e.target.value as TradingSegment,
                    })
                  }
                >
                  <option value="EQ">EQ</option>
                  <option value="FUT">FUT</option>
                  <option value="OPT">OPT</option>
                </select>
              </td>
              <td style={tdStyle}>
                <input
                  type="number"
                  style={inputStyle}
                  value={newConfig.capitalPct}
                  onChange={(e) =>
                    setNewConfig({
                      ...newConfig,
                      capitalPct: parseFloat(e.target.value),
                    })
                  }
                  step="0.1"
                />
              </td>
              <td style={tdStyle}>
                <input
                  type="checkbox"
                  checked={newConfig.enabled}
                  onChange={(e) =>
                    setNewConfig({ ...newConfig, enabled: e.target.checked })
                  }
                />
              </td>
              <td style={tdStyle}>{newConfig.provider || "KITE"}</td>
              <td style={tdStyle}>
                <button
                  style={buttonStyle}
                  onClick={handleAdd}
                >
                  Save
                </button>
                <button
                  style={{ ...cancelButtonStyle, marginLeft: 8 }}
                  onClick={() => setNewConfig(null)}
                >
                  Cancel
                </button>
              </td>
            </tr>
          )}
          {configs.map((config) => (
            <tr key={config.id}>
              <td style={tdStyle}>{config.symbol}</td>
              <td style={tdStyle}>{config.segment}</td>
              <td style={tdStyle}>
                {editingId === config.id ? (
                  <input
                    type="number"
                    style={inputStyle}
                    defaultValue={config.capitalPct}
                    onChange={(e) =>
                      (config.capitalPct = parseFloat(e.target.value))
                    }
                    step="0.1"
                  />
                ) : (
                  config.capitalPct
                )}
              </td>
              <td style={tdStyle}>
                <div style={enabledDotStyle(config.enabled)} />
              </td>
              <td style={tdStyle}>{config.provider || "KITE"}</td>
              <td style={tdStyle}>
                {editingId === config.id ? (
                  <>
                    <button
                      style={buttonStyle}
                      onClick={() => {
                        handleUpdate(config.id!, config);
                      }}
                    >
                      Save
                    </button>
                    <button
                      style={{ ...cancelButtonStyle, marginLeft: 8 }}
                      onClick={() => setEditingId(null)}
                    >
                      Cancel
                    </button>
                  </>
                ) : (
                  <>
                    <button
                      style={{ ...secondaryButtonStyle, marginRight: 8 }}
                      onClick={() => setEditingId(config.id!)}
                    >
                      Edit
                    </button>
                    <button
                      style={{
                        ...secondaryButtonStyle,
                        backgroundColor: "#d32f2f",
                      }}
                      onClick={() => handleDelete(config.id!)}
                    >
                      Delete
                    </button>
                  </>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
};

export default SegmentConfigPage;
