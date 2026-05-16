import { useState, ReactNode } from 'react';

type Props = { title: string; defaultOpen?: boolean; children: ReactNode };

export default function CollapsibleSection({ title, defaultOpen = true, children }: Props) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <div style={{ borderBottom: '1px solid #ddd' }}>
      <div
        onClick={() => setOpen(o => !o)}
        style={{
          padding: '8px 10px',
          cursor: 'pointer',
          fontWeight: 600,
          fontSize: 13,
          background: '#fafafa'
        }}
      >
        {open ? '▾' : '▸'} {title}
      </div>
      {open && <div>{children}</div>}
    </div>
  );
}
