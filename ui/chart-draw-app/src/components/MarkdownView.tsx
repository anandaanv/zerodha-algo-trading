import React from 'react';
import ReactMarkdown from 'react-markdown';

interface Props {
  content: string;
  /** 'primary' = blue/teal headings (default). 'raw' = purple headings for raw AI output. */
  theme?: 'primary' | 'raw';
  maxHeight?: number;
}

const PRIMARY_COMPONENTS = {
  h1: ({children}: any) => <h1 style={{ color: '#90caf9', fontSize: 16, marginBottom: 8, marginTop: 0 }}>{children}</h1>,
  h2: ({children}: any) => <h2 style={{ color: '#80cbc4', fontSize: 14, marginBottom: 6, marginTop: 14 }}>{children}</h2>,
  h3: ({children}: any) => <h3 style={{ color: '#a5d6a7', fontSize: 13, marginBottom: 4, marginTop: 10 }}>{children}</h3>,
  p: ({children}: any) => <p style={{ margin: '4px 0', color: '#c9d1d9' }}>{children}</p>,
  strong: ({children}: any) => <strong style={{ color: '#ffd54f' }}>{children}</strong>,
  li: ({children}: any) => <li style={{ marginBottom: 2, color: '#c9d1d9' }}>{children}</li>,
  ul: ({children}: any) => <ul style={{ paddingLeft: 20, margin: '4px 0' }}>{children}</ul>,
  ol: ({children}: any) => <ol style={{ paddingLeft: 20, margin: '4px 0' }}>{children}</ol>,
  code: ({children}: any) => <code style={{ background: '#1a2a3a', padding: '1px 4px', borderRadius: 3, fontSize: 12, color: '#90caf9' }}>{children}</code>,
  hr: () => <hr style={{ border: 'none', borderTop: '1px solid #333', margin: '8px 0' }} />,
};

const RAW_COMPONENTS = {
  h1: ({children}: any) => <h1 style={{ color: '#7986cb', fontSize: 16, marginBottom: 8, marginTop: 0 }}>{children}</h1>,
  h2: ({children}: any) => <h2 style={{ color: '#9575cd', fontSize: 14, marginBottom: 6, marginTop: 14 }}>{children}</h2>,
  h3: ({children}: any) => <h3 style={{ color: '#ba68c8', fontSize: 13, marginBottom: 4, marginTop: 10 }}>{children}</h3>,
  p: ({children}: any) => <p style={{ margin: '4px 0', color: '#8b949e' }}>{children}</p>,
  strong: ({children}: any) => <strong style={{ color: '#ce93d8' }}>{children}</strong>,
  li: ({children}: any) => <li style={{ marginBottom: 2, color: '#8b949e' }}>{children}</li>,
  ul: ({children}: any) => <ul style={{ paddingLeft: 20, margin: '4px 0' }}>{children}</ul>,
  ol: ({children}: any) => <ol style={{ paddingLeft: 20, margin: '4px 0' }}>{children}</ol>,
  code: ({children}: any) => <code style={{ background: '#1a1a3a', padding: '1px 4px', borderRadius: 3, fontSize: 12, color: '#7986cb' }}>{children}</code>,
  hr: () => <hr style={{ border: 'none', borderTop: '1px solid #333', margin: '8px 0' }} />,
};

export default function MarkdownView({ content, theme = 'primary', maxHeight = 400 }: Props) {
  if (!content) return null;
  const components = theme === 'raw' ? RAW_COMPONENTS : PRIMARY_COMPONENTS;
  return (
    <div style={{
      background: '#0d1117', border: '1px solid #333', borderRadius: 4,
      padding: '10px 16px', fontSize: 13, lineHeight: 1.6,
      maxHeight, overflow: 'auto',
    }}>
      <ReactMarkdown components={components}>{content}</ReactMarkdown>
    </div>
  );
}
