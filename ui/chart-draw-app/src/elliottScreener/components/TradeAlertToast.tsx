import React, { useEffect, useRef, useState } from 'react';
import { Client } from '@stomp/stompjs';

export interface TradeAlert {
  suggestionId: number;
  event: string;
  symbol: string;
  direction: string;
  price: number;
  message: string;
  timestamp: string;
}

const EVENT_COLOR: Record<string, string> = {
  ENTRY_HIT: '#2e7d32',
  TARGET_HIT: '#1565c0',
  STOP_LOSS_HIT: '#b71c1c',
  INVALIDATED: '#e65100',
};

const EVENT_LABEL: Record<string, string> = {
  ENTRY_HIT: '✓ Entry Hit',
  TARGET_HIT: '🎯 Target Hit',
  STOP_LOSS_HIT: '⚠ Stop Loss',
  INVALIDATED: '✕ Invalidated',
};

interface ToastItem {
  id: number;
  alert: TradeAlert;
}

export default function TradeAlertToast() {
  const [toasts, setToasts] = useState<ToastItem[]>([]);
  const clientRef = useRef<Client | null>(null);
  const counterRef = useRef(0);

  useEffect(() => {
    const wsUrl = `${window.location.protocol === 'https:' ? 'wss' : 'ws'}://${window.location.host}/ws`;
    const jwtToken = localStorage.getItem('auth_token');
    if (!jwtToken) return;

    const client = new Client({
      brokerURL: wsUrl,
      connectHeaders: { Authorization: `Bearer ${jwtToken}` },
      reconnectDelay: 10000,
    });

    client.onConnect = () => {
      client.subscribe('/user/topic/trade-alerts', (msg) => {
        try {
          const alert: TradeAlert = JSON.parse(msg.body);
          const id = ++counterRef.current;
          setToasts(prev => [...prev, { id, alert }]);
          // Auto-dismiss after 8 seconds
          setTimeout(() => setToasts(prev => prev.filter(t => t.id !== id)), 8000);
        } catch (e) {
          console.error('[TradeAlert] parse error', e);
        }
      });
    };

    client.activate();
    clientRef.current = client;

    return () => {
      client.deactivate();
    };
  }, []);

  if (toasts.length === 0) return null;

  return (
    <div style={{
      position: 'fixed',
      bottom: 24,
      right: 24,
      zIndex: 9999,
      display: 'flex',
      flexDirection: 'column',
      gap: 10,
      maxWidth: 340,
    }}>
      {toasts.map(({ id, alert }) => (
        <div key={id} style={{
          background: '#1e1e1e',
          border: `1px solid ${EVENT_COLOR[alert.event] ?? '#555'}`,
          borderLeft: `4px solid ${EVENT_COLOR[alert.event] ?? '#555'}`,
          borderRadius: 6,
          padding: '12px 14px',
          boxShadow: '0 4px 16px rgba(0,0,0,0.5)',
          animation: 'slideInRight 0.2s ease',
        }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 4 }}>
            <span style={{ color: EVENT_COLOR[alert.event] ?? '#aaa', fontWeight: 700, fontSize: 13 }}>
              {EVENT_LABEL[alert.event] ?? alert.event}
            </span>
            <button
              onClick={() => setToasts(prev => prev.filter(t => t.id !== id))}
              style={{ background: 'none', border: 'none', color: '#666', cursor: 'pointer', fontSize: 16, lineHeight: 1, padding: 0 }}
            >×</button>
          </div>
          <div style={{ color: '#fff', fontWeight: 600, fontSize: 15 }}>
            {alert.symbol}
            <span style={{ marginLeft: 8, fontSize: 12, color: alert.direction === 'LONG' ? '#4caf50' : '#ef5350' }}>
              {alert.direction}
            </span>
          </div>
          <div style={{ color: '#aaa', fontSize: 12, marginTop: 2 }}>
            @ ₹{alert.price.toLocaleString('en-IN', { maximumFractionDigits: 2 })}
          </div>
          <div style={{ color: '#888', fontSize: 11, marginTop: 4 }}>{alert.message}</div>
        </div>
      ))}
    </div>
  );
}
