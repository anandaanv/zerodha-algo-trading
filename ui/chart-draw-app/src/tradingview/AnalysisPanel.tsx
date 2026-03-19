import React, { useState, useEffect } from 'react';
import { analyzeStock, StockAnalysisResponse } from './analysisApi';

interface AnalysisPanelProps {
  open: boolean;
  symbol: string;
  timeframe: string;
  onClose: () => void;
}

type TabType = 'fundamentals' | 'news' | 'correlation' | 'social';

export default function AnalysisPanel({ open, symbol, timeframe, onClose }: AnalysisPanelProps) {
  const [activeTab, setActiveTab] = useState<TabType>('fundamentals');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [analysis, setAnalysis] = useState<StockAnalysisResponse | null>(null);

  // Fetch analysis when panel opens or symbol changes
  useEffect(() => {
    if (open && symbol) {
      fetchAnalysis();
    }
  }, [open, symbol, timeframe]);

  const fetchAnalysis = async () => {
    setLoading(true);
    setError(null);

    try {
      const data = await analyzeStock(symbol, timeframe);
      setAnalysis(data);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load analysis');
    } finally {
      setLoading(false);
    }
  };

  if (!open) return null;

  return (
    <div style={styles.overlay}>
      <div style={styles.panel}>
        {/* Header */}
        <div style={styles.header}>
          <h2 style={styles.title}>Analysis: {symbol}</h2>
          <button onClick={onClose} style={styles.closeButton}>✕</button>
        </div>

        {/* Tab Navigation */}
        <div style={styles.tabContainer}>
          <button
            style={activeTab === 'fundamentals' ? styles.tabActive : styles.tab}
            onClick={() => setActiveTab('fundamentals')}
          >
            Fundamentals
          </button>
          <button
            style={activeTab === 'news' ? styles.tabActive : styles.tab}
            onClick={() => setActiveTab('news')}
          >
            News
          </button>
          <button
            style={activeTab === 'correlation' ? styles.tabActive : styles.tab}
            onClick={() => setActiveTab('correlation')}
          >
            Correlation
          </button>
          <button
            style={activeTab === 'social' ? styles.tabActive : styles.tab}
            onClick={() => setActiveTab('social')}
          >
            Social
          </button>
        </div>

        {/* Content */}
        <div style={styles.content}>
          {loading && <div style={styles.loading}>Loading...</div>}
          {error && <div style={styles.error}>{error}</div>}

          {!loading && !error && analysis && (
            <>
              {activeTab === 'fundamentals' && <FundamentalsTab data={analysis.fundamentals} />}
              {activeTab === 'news' && <NewsTab data={analysis.news} />}
              {activeTab === 'correlation' && <CorrelationTab data={analysis.correlation} />}
              {activeTab === 'social' && <SocialTab data={analysis.socialSentiment} />}
            </>
          )}
        </div>

        {/* Refresh Button */}
        <div style={styles.footer}>
          <button onClick={fetchAnalysis} style={styles.refreshButton} disabled={loading}>
            {loading ? 'Refreshing...' : 'Refresh Analysis'}
          </button>
        </div>
      </div>
    </div>
  );
}

// ============ Tab Components ============

function FundamentalsTab({ data }: { data: StockAnalysisResponse['fundamentals'] }) {
  return (
    <div style={styles.tabContent}>
      <div style={styles.dataGrid}>
        <DataRow label="Current Price" value={`₹${data.currentPrice?.toFixed(2)}`} />
        <DataRow label="P/E Ratio" value={data.peRatio?.toFixed(2)} />
        <DataRow label="Market Cap" value={`₹${(data.marketCap / 1000).toFixed(2)}K Cr`} />
        <DataRow label="EPS" value={data.epsValue?.toFixed(2)} />
        <DataRow label="Dividend Yield" value={`${data.dividendYield?.toFixed(2)}%`} />
        <DataRow label="Sector" value={data.sector} />
        <DataRow label="Industry" value={data.industry} />
        <DataRow label="52W High" value={`₹${data.yearHigh?.toFixed(2)}`} />
        <DataRow label="52W Low" value={`₹${data.yearLow?.toFixed(2)}`} />
      </div>
      <div style={styles.lastUpdated}>Last updated: {new Date(data.lastUpdated).toLocaleString()}</div>
    </div>
  );
}

function NewsTab({ data }: { data: StockAnalysisResponse['news'] }) {
  return (
    <div style={styles.tabContent}>
      {data.length === 0 ? (
        <div style={styles.emptyState}>No recent news available</div>
      ) : (
        data.map((item, index) => (
          <div key={index} style={styles.newsItem}>
            <div style={styles.newsHeader}>
              <span style={getSentimentStyle(item.sentiment)}>{item.sentiment.toUpperCase()}</span>
              <span style={styles.newsSource}>{item.source}</span>
            </div>
            <h4 style={styles.newsTitle}>{item.title}</h4>
            <p style={styles.newsDescription}>{item.description}</p>
            <div style={styles.newsFooter}>
              <span style={styles.newsDate}>{new Date(item.publishedAt).toLocaleDateString()}</span>
              <a href={item.url} target="_blank" rel="noopener noreferrer" style={styles.newsLink}>
                Read more →
              </a>
            </div>
          </div>
        ))
      )}
    </div>
  );
}

function CorrelationTab({ data }: { data: StockAnalysisResponse['correlation'] }) {
  return (
    <div style={styles.tabContent}>
      <h3 style={styles.sectionTitle}>Index Correlation ({data.correlationPeriod})</h3>
      <div style={styles.dataGrid}>
        <DataRow label="NIFTY" value={`${(data.niftyCorrelation * 100).toFixed(1)}%`} />
        <DataRow label={data.sectorIndexName} value={`${(data.sectorIndexCorrelation * 100).toFixed(1)}%`} />
      </div>

      <h3 style={styles.sectionTitle}>Related Stocks</h3>
      <div style={styles.dataGrid}>
        {Object.entries(data.relatedStocksCorrelation).map(([stock, correlation]) => (
          <DataRow key={stock} label={stock} value={`${(correlation * 100).toFixed(1)}%`} />
        ))}
      </div>
    </div>
  );
}

function SocialTab({ data }: { data: StockAnalysisResponse['socialSentiment'] }) {
  const total = data.bullishCount + data.bearishCount + data.neutralCount;

  return (
    <div style={styles.tabContent}>
      <h3 style={styles.sectionTitle}>
        Overall Sentiment: <span style={getSentimentStyle(data.overallSentiment)}>{data.overallSentiment.toUpperCase()}</span>
      </h3>

      <div style={styles.sentimentBar}>
        <div style={{ ...styles.sentimentSegment, width: `${(data.bullishCount / total) * 100}%`, backgroundColor: '#4caf50' }}>
          {data.bullishCount > 0 && `${data.bullishCount}`}
        </div>
        <div style={{ ...styles.sentimentSegment, width: `${(data.bearishCount / total) * 100}%`, backgroundColor: '#f44336' }}>
          {data.bearishCount > 0 && `${data.bearishCount}`}
        </div>
        <div style={{ ...styles.sentimentSegment, width: `${(data.neutralCount / total) * 100}%`, backgroundColor: '#9e9e9e' }}>
          {data.neutralCount > 0 && `${data.neutralCount}`}
        </div>
      </div>

      <h3 style={styles.sectionTitle}>Recent Community Snapshots</h3>
      {data.recentSnapshots.length === 0 ? (
        <div style={styles.emptyState}>No public snapshots available yet</div>
      ) : (
        <div style={styles.snapshotsList}>
          {data.recentSnapshots.map((snapshot) => (
            <div key={snapshot.snapshotId} style={styles.snapshotCard}>
              <div style={styles.snapshotHeader}>
                <span style={styles.snapshotUser}>@{snapshot.username}</span>
                <span style={styles.snapshotPattern}>{snapshot.patternType}</span>
              </div>
              <p style={styles.snapshotComment}>{snapshot.userComment}</p>
              {snapshot.aiValidation && (
                <p style={styles.snapshotAi}>AI: {snapshot.aiValidation}</p>
              )}
              <div style={styles.snapshotFooter}>
                <span>❤️ {snapshot.likesCount}</span>
                <span>💬 {snapshot.commentsCount}</span>
                <span>{snapshot.timeframe}</span>
                <span>{new Date(snapshot.createdAt).toLocaleDateString()}</span>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

// Helper Components
function DataRow({ label, value }: { label: string; value: string | number | undefined }) {
  return (
    <div style={styles.dataRow}>
      <span style={styles.dataLabel}>{label}:</span>
      <span style={styles.dataValue}>{value ?? 'N/A'}</span>
    </div>
  );
}

// Helper Functions
function getSentimentStyle(sentiment: string): React.CSSProperties {
  const baseStyle = {
    padding: '2px 8px',
    borderRadius: '4px',
    fontWeight: 'bold' as const,
    fontSize: '12px',
  };

  switch (sentiment.toLowerCase()) {
    case 'positive':
    case 'bullish':
      return { ...baseStyle, backgroundColor: '#e8f5e9', color: '#2e7d32' };
    case 'negative':
    case 'bearish':
      return { ...baseStyle, backgroundColor: '#ffebee', color: '#c62828' };
    default:
      return { ...baseStyle, backgroundColor: '#f5f5f5', color: '#616161' };
  }
}

// ============ Styles ============

const styles: Record<string, React.CSSProperties> = {
  overlay: {
    position: 'fixed',
    top: 0,
    right: 0,
    bottom: 0,
    width: '450px',
    backgroundColor: 'white',
    boxShadow: '-2px 0 10px rgba(0,0,0,0.1)',
    zIndex: 10000,
    display: 'flex',
    flexDirection: 'column',
  },
  panel: {
    display: 'flex',
    flexDirection: 'column',
    height: '100%',
  },
  header: {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: '16px',
    borderBottom: '1px solid #e0e0e0',
  },
  title: {
    margin: 0,
    fontSize: '18px',
    fontWeight: 600,
  },
  closeButton: {
    background: 'none',
    border: 'none',
    fontSize: '24px',
    cursor: 'pointer',
    padding: '4px 8px',
    color: '#666',
  },
  tabContainer: {
    display: 'flex',
    borderBottom: '1px solid #e0e0e0',
  },
  tab: {
    flex: 1,
    padding: '12px',
    background: 'none',
    border: 'none',
    cursor: 'pointer',
    fontSize: '14px',
    color: '#666',
    borderBottom: '2px solid transparent',
  },
  tabActive: {
    flex: 1,
    padding: '12px',
    background: 'none',
    border: 'none',
    cursor: 'pointer',
    fontSize: '14px',
    fontWeight: 600,
    color: '#1976d2',
    borderBottom: '2px solid #1976d2',
  },
  content: {
    flex: 1,
    overflowY: 'auto',
    padding: '16px',
  },
  footer: {
    padding: '16px',
    borderTop: '1px solid #e0e0e0',
  },
  refreshButton: {
    width: '100%',
    padding: '10px',
    backgroundColor: '#1976d2',
    color: 'white',
    border: 'none',
    borderRadius: '4px',
    cursor: 'pointer',
    fontSize: '14px',
    fontWeight: 600,
  },
  loading: {
    textAlign: 'center',
    padding: '32px',
    color: '#666',
  },
  error: {
    padding: '16px',
    backgroundColor: '#ffebee',
    color: '#c62828',
    borderRadius: '4px',
    marginBottom: '16px',
  },
  tabContent: {
    animation: 'fadeIn 0.3s',
  },
  dataGrid: {
    display: 'flex',
    flexDirection: 'column',
    gap: '8px',
    marginBottom: '16px',
  },
  dataRow: {
    display: 'flex',
    justifyContent: 'space-between',
    padding: '8px',
    backgroundColor: '#f5f5f5',
    borderRadius: '4px',
  },
  dataLabel: {
    fontWeight: 500,
    color: '#666',
  },
  dataValue: {
    fontWeight: 600,
    color: '#333',
  },
  lastUpdated: {
    fontSize: '12px',
    color: '#999',
    textAlign: 'right',
  },
  sectionTitle: {
    fontSize: '16px',
    fontWeight: 600,
    marginTop: '16px',
    marginBottom: '12px',
    color: '#333',
  },
  newsItem: {
    padding: '12px',
    marginBottom: '12px',
    border: '1px solid #e0e0e0',
    borderRadius: '4px',
  },
  newsHeader: {
    display: 'flex',
    justifyContent: 'space-between',
    marginBottom: '8px',
  },
  newsSource: {
    fontSize: '12px',
    color: '#999',
  },
  newsTitle: {
    margin: '0 0 8px 0',
    fontSize: '14px',
    fontWeight: 600,
  },
  newsDescription: {
    margin: '0 0 8px 0',
    fontSize: '13px',
    color: '#666',
    lineHeight: '1.4',
  },
  newsFooter: {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  newsDate: {
    fontSize: '12px',
    color: '#999',
  },
  newsLink: {
    fontSize: '12px',
    color: '#1976d2',
    textDecoration: 'none',
  },
  emptyState: {
    textAlign: 'center',
    padding: '32px',
    color: '#999',
  },
  sentimentBar: {
    display: 'flex',
    height: '40px',
    borderRadius: '4px',
    overflow: 'hidden',
    marginBottom: '16px',
  },
  sentimentSegment: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    color: 'white',
    fontWeight: 'bold',
    fontSize: '14px',
  },
  snapshotsList: {
    display: 'flex',
    flexDirection: 'column',
    gap: '12px',
  },
  snapshotCard: {
    padding: '12px',
    border: '1px solid #e0e0e0',
    borderRadius: '4px',
    backgroundColor: '#fafafa',
  },
  snapshotHeader: {
    display: 'flex',
    justifyContent: 'space-between',
    marginBottom: '8px',
  },
  snapshotUser: {
    fontWeight: 600,
    color: '#1976d2',
  },
  snapshotPattern: {
    fontSize: '12px',
    padding: '2px 8px',
    backgroundColor: '#e3f2fd',
    color: '#1565c0',
    borderRadius: '4px',
  },
  snapshotComment: {
    margin: '0 0 8px 0',
    fontSize: '13px',
    color: '#333',
  },
  snapshotAi: {
    margin: '0 0 8px 0',
    fontSize: '12px',
    color: '#666',
    fontStyle: 'italic',
  },
  snapshotFooter: {
    display: 'flex',
    gap: '12px',
    fontSize: '12px',
    color: '#999',
  },
};
