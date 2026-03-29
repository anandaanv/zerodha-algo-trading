import React, { useEffect, useState } from 'react';
import {
  ScanGroup, ScanGroupMember,
  listGroups, createGroup, updateGroup,
  getGroupMembers, addMember, removeMember,
} from './api';

export default function GroupManagementPage() {
  const [groups, setGroups] = useState<ScanGroup[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [newGroupName, setNewGroupName] = useState('');
  const [newGroupMaxScans, setNewGroupMaxScans] = useState('');
  const [creating, setCreating] = useState(false);

  // Per-group expanded state and member data
  const [expanded, setExpanded] = useState<Record<number, boolean>>({});
  const [members, setMembers] = useState<Record<number, ScanGroupMember[]>>({});
  const [addUserId, setAddUserId] = useState<Record<number, string>>({});
  const [memberError, setMemberError] = useState<Record<number, string>>({});

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      setGroups(await listGroups());
    } catch (e: any) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, []);

  const handleCreate = async () => {
    if (!newGroupName.trim()) return;
    setCreating(true);
    try {
      const g = await createGroup(newGroupName.trim(), newGroupMaxScans ? Number(newGroupMaxScans) : null);
      setGroups(prev => [...prev, g]);
      setNewGroupName('');
      setNewGroupMaxScans('');
    } catch (e: any) {
      setError(e.message);
    } finally {
      setCreating(false);
    }
  };

  const toggleScreenerAccess = async (group: ScanGroup) => {
    try {
      const updated = await updateGroup(group.id, { screenerAccess: !group.screenerAccess });
      setGroups(prev => prev.map(g => g.id === group.id ? updated : g));
    } catch (e: any) {
      setError(e.message);
    }
  };

  const toggleExpand = async (groupId: number) => {
    const next = !expanded[groupId];
    setExpanded(prev => ({ ...prev, [groupId]: next }));
    if (next && !members[groupId]) {
      try {
        const m = await getGroupMembers(groupId);
        setMembers(prev => ({ ...prev, [groupId]: m }));
      } catch {}
    }
  };

  const handleAddMember = async (groupId: number) => {
    const uid = Number(addUserId[groupId]);
    if (!uid) return;
    setMemberError(prev => ({ ...prev, [groupId]: '' }));
    try {
      await addMember(groupId, uid);
      const m = await getGroupMembers(groupId);
      setMembers(prev => ({ ...prev, [groupId]: m }));
      setAddUserId(prev => ({ ...prev, [groupId]: '' }));
    } catch (e: any) {
      setMemberError(prev => ({ ...prev, [groupId]: e.message }));
    }
  };

  const handleRemoveMember = async (groupId: number, userId: number) => {
    try {
      await removeMember(groupId, userId);
      setMembers(prev => ({
        ...prev,
        [groupId]: (prev[groupId] || []).filter(m => m.userId !== userId),
      }));
    } catch (e: any) {
      setMemberError(prev => ({ ...prev, [groupId]: e.message }));
    }
  };

  const cellStyle: React.CSSProperties = { padding: '10px 14px', borderBottom: '1px solid #333' };
  const headerStyle: React.CSSProperties = { ...cellStyle, color: '#aaa', fontSize: 12, fontWeight: 600, textTransform: 'uppercase' };

  return (
    <div style={{ background: '#1a1a1a', minHeight: '100vh', padding: 24, color: '#fff' }}>
      <h1 style={{ color: '#90caf9', marginTop: 0 }}>Group Management</h1>

      {error && <p style={{ color: '#ef9a9a' }}>{error}</p>}

      {/* Create group */}
      <div style={{ background: '#1e1e1e', border: '1px solid #333', borderRadius: 8, padding: 16, marginBottom: 24 }}>
        <h3 style={{ margin: '0 0 12px', color: '#aaa', fontSize: 14 }}>Create New Group</h3>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          <input
            placeholder="Group name"
            value={newGroupName}
            onChange={e => setNewGroupName(e.target.value)}
            style={{ background: '#2a2a2a', color: '#fff', border: '1px solid #444', borderRadius: 4, padding: '6px 10px', flex: 1, minWidth: 180 }}
          />
          <input
            placeholder="Max scans/hour (optional)"
            value={newGroupMaxScans}
            onChange={e => setNewGroupMaxScans(e.target.value)}
            type="number"
            style={{ background: '#2a2a2a', color: '#fff', border: '1px solid #444', borderRadius: 4, padding: '6px 10px', width: 180 }}
          />
          <button
            onClick={handleCreate}
            disabled={creating || !newGroupName.trim()}
            style={{ background: '#1565c0', color: '#fff', border: 'none', borderRadius: 4, padding: '6px 18px', cursor: 'pointer' }}
          >
            {creating ? 'Creating...' : 'Create'}
          </button>
        </div>
      </div>

      {/* Groups table */}
      {loading ? <p style={{ color: '#aaa' }}>Loading...</p> : (
        <div style={{ background: '#1e1e1e', border: '1px solid #333', borderRadius: 8, overflow: 'hidden' }}>
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr>
                <th style={headerStyle}>Name</th>
                <th style={headerStyle}>Max Scans/hr</th>
                <th style={headerStyle}>Screener Access</th>
                <th style={headerStyle}>Members</th>
              </tr>
            </thead>
            <tbody>
              {groups.map(group => (
                <React.Fragment key={group.id}>
                  <tr>
                    <td style={cellStyle}>{group.name}</td>
                    <td style={cellStyle}>{group.maxScansPerHour ?? '—'}</td>
                    <td style={cellStyle}>
                      <button
                        onClick={() => toggleScreenerAccess(group)}
                        style={{
                          background: group.screenerAccess ? '#2e7d32' : '#424242',
                          color: '#fff',
                          border: 'none',
                          borderRadius: 4,
                          padding: '4px 12px',
                          cursor: 'pointer',
                          fontSize: 12,
                        }}
                      >
                        {group.screenerAccess ? 'Enabled' : 'Disabled'}
                      </button>
                    </td>
                    <td style={cellStyle}>
                      <button
                        onClick={() => toggleExpand(group.id)}
                        style={{ background: '#333', color: '#aaa', border: 'none', borderRadius: 4, padding: '4px 10px', cursor: 'pointer', fontSize: 12 }}
                      >
                        {expanded[group.id] ? 'Hide' : 'Manage'}
                      </button>
                    </td>
                  </tr>
                  {expanded[group.id] && (
                    <tr>
                      <td colSpan={4} style={{ padding: '10px 20px', background: '#161616', borderBottom: '1px solid #333' }}>
                        <div style={{ marginBottom: 8, fontSize: 13, color: '#aaa' }}>Members (User IDs)</div>
                        {(members[group.id] || []).map(m => (
                          <span key={m.id} style={{ display: 'inline-flex', alignItems: 'center', gap: 4, background: '#2a2a2a', border: '1px solid #444', borderRadius: 4, padding: '2px 8px', marginRight: 6, marginBottom: 4, fontSize: 13 }}>
                            User #{m.userId}
                            <button
                              onClick={() => handleRemoveMember(group.id, m.userId)}
                              style={{ background: 'none', border: 'none', color: '#ef9a9a', cursor: 'pointer', fontSize: 14, lineHeight: 1, padding: 0 }}
                            >×</button>
                          </span>
                        ))}
                        {(members[group.id] || []).length === 0 && <span style={{ color: '#555', fontSize: 13 }}>No members</span>}
                        <div style={{ display: 'flex', gap: 6, marginTop: 8 }}>
                          <input
                            placeholder="User ID"
                            type="number"
                            value={addUserId[group.id] || ''}
                            onChange={e => setAddUserId(prev => ({ ...prev, [group.id]: e.target.value }))}
                            style={{ background: '#2a2a2a', color: '#fff', border: '1px solid #444', borderRadius: 4, padding: '4px 8px', width: 100 }}
                          />
                          <button
                            onClick={() => handleAddMember(group.id)}
                            style={{ background: '#1565c0', color: '#fff', border: 'none', borderRadius: 4, padding: '4px 12px', cursor: 'pointer', fontSize: 12 }}
                          >
                            Add
                          </button>
                        </div>
                        {memberError[group.id] && <p style={{ color: '#ef9a9a', margin: '4px 0 0', fontSize: 12 }}>{memberError[group.id]}</p>}
                      </td>
                    </tr>
                  )}
                </React.Fragment>
              ))}
              {groups.length === 0 && (
                <tr><td colSpan={4} style={{ ...cellStyle, color: '#555', textAlign: 'center' }}>No groups yet</td></tr>
              )}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
