import React, { useState, useEffect, useRef } from 'react';
import { analyzeStock, StockAnalysisResponse, technicalChatAnalysis, ChatMode, TechnicalChatResponse } from './analysisApi';
import { createSnapshot, SnapshotRequest, SnapshotResult } from './snapshotApi';
import { apiFetch, getApiUrl } from '../config/api';
import { withAuth } from '../utils/apiHelper';

interface AnalysisPanelProps {
  open: boolean;
  symbol: string;
  timeframe: string;
  onClose: () => void;
  getChartState?: () => string;
}

type TabType = 'fundamentals' | 'news' | 'correlation' | 'social' | 'snapshot' | 'technical-ai';

interface ChatMessage {
  role: 'user' | 'ai';
  content: string;
  timestamp: number;
}

export default function AnalysisPanel({ open, symbol, timeframe, onClose, getChartState }: AnalysisPanelProps) {
  const [activeTab, setActiveTab] = useState<TabType>('fundamentals');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [analysis, setAnalysis] = useState<StockAnalysisResponse | null>(null);

  // Technical AI state
  const [chatHistory, setChatHistory] = useState<ChatMessage[]>([]);
  const [validateInput, setValidateInput] = useState('');
  const [chatLoading, setChatLoading] = useState(false);
  const [chatError, setChatError] = useState<string | null>(null);
  const [publicSnapshots, setPublicSnapshots] = useState<any[]>([]);
  const chatBottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (open && symbol) fetchAnalysis();
  }, [open, symbol, timeframe]);

  useEffect(() => {
    if (activeTab === 'technical-ai' && symbol) fetchPublicSnapshots();
  }, [activeTab, symbol]);

  useEffect(() => {
    chatBottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [chatHistory]);

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

  const fetchPublicSnapshots = async () => {
    try {
      const url = getApiUrl('/api/snapshots/public');
      url.searchParams.set('symbol', symbol);
      url.searchParams.set('size', '3');
      const res = await apiFetch(url.toString(), withAuth());
      if (res.ok) {
        const data = await res.json();
        setPublicSnapshots(data.content || []);
      }
    } catch { /* non-critical */ }
  };

  const sendChatMessage = async (mode: ChatMode) => {
    if (!getChartState) {
      setChatError('Chart not available — cannot capture drawings.');
      return;
    }
    const userMessage = mode === 'VALIDATE' ? validateInput.trim() : undefined;
    if (mode === 'VALIDATE' && !userMessage) return;

    const displayMessage = mode === 'REASON' ? 'Reason my drawings' : userMessage!;
    setChatHistory(prev => [...prev, { role: 'user', content: displayMessage, timestamp: Date.now() }]);
    if (mode === 'VALIDATE') setValidateInput('');
    setChatLoading(true);
    setChatError(null);
    try {
      const chartStateJson = getChartState();
      const response: TechnicalChatResponse = await technicalChatAnalysis({
        symbol, timeframe, chartStateJson, userMessage, mode,
      });
      setChatHistory(prev => [...prev, { role: 'ai', content: response.message, timestamp: Date.now() }]);
    } catch (err) {
      setChatError(err instanceof Error ? err.message : 'AI request failed');
    } finally {
      setChatLoading(false);
    }
  };

  if (!open) return null;

  const TAB_LABELS: Record<TabType, string> = {
    fundamentals: 'Fund.',
    news: 'News',
    correlation: 'Corr.',
    social: 'Social',
    snapshot: 'Snap',
    'technical-ai': 'AI',
  };

  const isFundamentalsTab = activeTab !== 'technical-ai' && activeTab !== 'snapshot';

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
          {(Object.keys(TAB_LABELS) as TabType[]).map(tab => (
            <button
              key={tab}
              style={activeTab === tab ? styles.tabActive : styles.tab}
              onClick={() => setActiveTab(tab)}
            >
              {TAB_LABELS[tab]}
            </button>
          ))}
        </div>

        {/* Content */}
        <div style={styles.content}>
          {isFundamentalsTab && loading && <div style={styles.loading}>Loading...</div>}
          {isFundamentalsTab && !loading && error && <div style={styles.error}>{error}</div>}
          {isFundamentalsTab && !loading && !error && analysis && (
            <>
              {activeTab === 'fundamentals' && <FundamentalsTab data={analysis.fundamentals} />}
              {activeTab === 'news' && <NewsTab data={analysis.news} />}
              {activeTab === 'correlation' && <CorrelationTab data={analysis.correlation} />}
              {activeTab === 'social' && <SocialTab data={analysis.socialSentiment} />}
            </>
          )}

          {activeTab === 'snapshot' && (
            <SnapshotTab symbol={symbol} timeframe={timeframe} getChartState={getChartState} />
          )}

          {activeTab === 'technical-ai' && (
            <TechnicalAITab
              chatHistory={chatHistory}
              chatLoading={chatLoading}
              chatError={chatError}
              validateInput={validateInput}
              publicSnapshots={publicSnapshots}
              chatBottomRef={chatBottomRef}
              onValidateInputChange={setValidateInput}
              onReason={() => sendChatMessage('REASON')}
              onValidate={() => sendChatMessage('VALIDATE')}
            />
          )}
        </div>

        {isFundamentalsTab && (
          <div style={styles.footer}>
            <button onClick={fetchAnalysis} style={styles.refreshButton} disabled={loading}>
              {loading ? 'Refreshing...' : 'Refresh Analysis'}
            </button>
          </div>
        )}
      </div>
    </div>
  );
}

// ============ Snapshot Tab ============

const COMMON_TAGS = [
  'Head and Shoulders', 'Inverse Head and Shoulders', 'Double Top', 'Double Bottom',
  'Triangle', 'Ascending Triangle', 'Descending Triangle', 'Wedge', 'Rising Wedge',
  'Falling Wedge', 'Channel', 'Support', 'Resistance', 'Support/Resistance',
  'Trendline', 'Breakout', 'Breakdown', 'Flag', 'Pennant', 'Cup and Handle',
  'Elliott Wave', 'Fibonacci', 'Consolidation', 'Reversal', 'Continuation',
];

interface AnalysisSummary {
  symbol: string; timeframe: string; price: number | null; peRatio: number | null;
  sector: string | null; sentiment: string; recentSnapshotsCount: number;
  commonTags: string[]; latestNews: string | null;
}

function SnapshotTab({ symbol, timeframe, getChartState }: {
  symbol: string; timeframe: string; getChartState?: () => string;
}) {
  const [userComment, setUserComment] = useState('');
  const [selectedTags, setSelectedTags] = useState<string[]>([]);
  const [tagInput, setTagInput] = useState('');
  const [tagSuggestions, setTagSuggestions] = useState<string[]>([]);
  const [showSuggestions, setShowSuggestions] = useState(false);
  const [validating, setValidating] = useState(false);
  const [visibility, setVisibility] = useState<'private' | 'public'>('private');
  const [performAiValidation, setPerformAiValidation] = useState(true);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<SnapshotResult | null>(null);
  const [summary, setSummary] = useState<AnalysisSummary | null>(null);
  const [summaryLoading, setSummaryLoading] = useState(false);
  const tagInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    fetchSummary();
    // Reset form when symbol changes
    setUserComment('');
    setSelectedTags([]);
    setError(null);
    setResult(null);
  }, [symbol, timeframe]);

  useEffect(() => {
    if (tagInput.trim().length > 0) {
      const filtered = COMMON_TAGS.filter(
        t => t.toLowerCase().includes(tagInput.toLowerCase()) && !selectedTags.includes(t)
      );
      setTagSuggestions(filtered);
      setShowSuggestions(filtered.length > 0);
    } else {
      setShowSuggestions(false);
    }
  }, [tagInput, selectedTags]);

  const fetchSummary = async () => {
    setSummaryLoading(true);
    try {
      const url = getApiUrl('/api/snapshots/summary');
      url.searchParams.set('symbol', symbol);
      url.searchParams.set('timeframe', timeframe);
      const res = await apiFetch(url.toString(), withAuth());
      if (res.ok) setSummary(await res.json());
    } catch { /* non-critical */ } finally {
      setSummaryLoading(false);
    }
  };

  const validateTag = async (tag: string): Promise<string> => {
    try {
      const url = getApiUrl('/api/tags/validate');
      url.searchParams.set('tag', tag);
      const res = await apiFetch(url.toString(), withAuth());
      if (res.ok) return (await res.json()).normalized || tag;
    } catch { /* fallback */ }
    return tag;
  };

  const addTag = async (tag: string) => {
    const trimmed = tag.trim();
    if (!trimmed || selectedTags.some(t => t.toLowerCase() === trimmed.toLowerCase())) {
      setTagInput(''); setShowSuggestions(false); return;
    }
    setValidating(true);
    try {
      const normalized = await validateTag(trimmed);
      if (!selectedTags.some(t => t.toLowerCase() === normalized.toLowerCase())) {
        setSelectedTags(prev => [...prev, normalized]);
      }
    } catch {
      const basic = trimmed.charAt(0).toUpperCase() + trimmed.slice(1).toLowerCase();
      if (!selectedTags.includes(basic)) setSelectedTags(prev => [...prev, basic]);
    } finally {
      setValidating(false); setTagInput(''); setShowSuggestions(false);
    }
  };

  const removeTag = (tag: string) => setSelectedTags(selectedTags.filter(t => t !== tag));

  const handleTagKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter') { e.preventDefault(); if (tagInput.trim()) addTag(tagInput); }
    else if (e.key === 'Backspace' && !tagInput && selectedTags.length > 0) removeTag(selectedTags[selectedTags.length - 1]);
    else if (e.key === 'Escape') setShowSuggestions(false);
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!userComment.trim()) { setError('Please provide your analysis/reason'); return; }
    if (selectedTags.length === 0) { setError('Please add at least one pattern tag'); return; }
    if (!getChartState) { setError('Chart not available'); return; }

    setLoading(true); setError(null); setResult(null);
    try {
      const req: SnapshotRequest = {
        symbol, timeframe,
        chartStateJson: getChartState(),
        userComment: userComment.trim(),
        patternTags: selectedTags,
        visibility,
        performAiValidation,
      };
      const res = await createSnapshot(req);
      setResult(res);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to create snapshot');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div>
      {/* Quick analysis summary */}
      {summaryLoading && <div style={sStyles.summaryLoading}>Loading context...</div>}
      {summary && (
        <div style={sStyles.summaryBar}>
          {summary.price && <span>₹{summary.price.toFixed(2)}</span>}
          {summary.peRatio && <span>PE: {summary.peRatio.toFixed(1)}</span>}
          {summary.sector && <span>{summary.sector}</span>}
          {summary.sentiment && summary.sentiment !== 'neutral' && (
            <span style={{ color: summary.sentiment === 'bullish' ? '#2e7d32' : '#c62828' }}>
              {summary.sentiment === 'bullish' ? '📈' : '📉'} {summary.sentiment}
            </span>
          )}
          {summary.latestNews && (
            <div style={sStyles.summaryNews}>📰 {summary.latestNews}</div>
          )}
        </div>
      )}

      {result?.success ? (
        <SnapshotSuccess result={result} onNew={() => { setResult(null); setUserComment(''); setSelectedTags([]); }} />
      ) : (
        <form onSubmit={handleSubmit} style={{ marginTop: '12px' }}>
          {/* Pattern Tags */}
          <div style={sStyles.formGroup}>
            <label style={sStyles.label}>Pattern Tags <span style={{ color: '#f44336' }}>*</span></label>
            <div style={sStyles.tagContainer}>
              {selectedTags.map(tag => (
                <div key={tag} style={sStyles.tagChip}>
                  <span>{tag}</span>
                  <button type="button" onClick={() => removeTag(tag)} style={sStyles.tagRemove} disabled={loading}>×</button>
                </div>
              ))}
              <div style={{ flex: 1, minWidth: '120px', position: 'relative' }}>
                <input
                  ref={tagInputRef}
                  type="text"
                  value={tagInput}
                  onChange={e => setTagInput(e.target.value)}
                  onKeyDown={handleTagKeyDown}
                  placeholder={selectedTags.length === 0 ? 'Type to add tags...' : ''}
                  style={sStyles.tagInput}
                  disabled={loading || validating}
                />
                {validating && <span style={{ position: 'absolute', right: 4, top: 4 }}>⏳</span>}
                {showSuggestions && (
                  <div style={sStyles.suggestionsDropdown}>
                    {tagSuggestions.slice(0, 8).map(s => (
                      <div key={s} style={sStyles.suggestionItem} onClick={() => addTag(s)}>{s}</div>
                    ))}
                  </div>
                )}
              </div>
            </div>
          </div>

          {/* Analysis text */}
          <div style={sStyles.formGroup}>
            <label style={sStyles.label}>Your Analysis <span style={{ color: '#f44336' }}>*</span></label>
            <textarea
              value={userComment}
              onChange={e => setUserComment(e.target.value)}
              placeholder="Explain why this pattern is forming, key levels, targets..."
              style={sStyles.textarea}
              rows={5}
              disabled={loading}
            />
            <div style={{ fontSize: '11px', color: '#999', textAlign: 'right' }}>{userComment.length} / 1000</div>
          </div>

          {/* Visibility + AI toggle row */}
          <div style={{ display: 'flex', gap: '16px', alignItems: 'center', marginBottom: '16px', flexWrap: 'wrap' }}>
            <label style={sStyles.radioLabel}>
              <input type="radio" value="private" checked={visibility === 'private'} onChange={() => setVisibility('private')} disabled={loading} />
              <span style={{ marginLeft: 4, fontSize: '13px' }}>🔒 Private</span>
            </label>
            <label style={sStyles.radioLabel}>
              <input type="radio" value="public" checked={visibility === 'public'} onChange={() => setVisibility('public')} disabled={loading} />
              <span style={{ marginLeft: 4, fontSize: '13px' }}>🌐 Public</span>
            </label>
            <label style={{ display: 'flex', alignItems: 'center', cursor: 'pointer', fontSize: '13px' }}>
              <input type="checkbox" checked={performAiValidation} onChange={e => setPerformAiValidation(e.target.checked)} disabled={loading} />
              <span style={{ marginLeft: 6 }}>🤖 AI validation</span>
            </label>
          </div>

          {error && <div style={sStyles.errorBox}>{error}</div>}

          <button type="submit" style={sStyles.submitButton} disabled={loading || validating}>
            {loading ? 'Creating...' : '📸 Create Snapshot'}
          </button>
        </form>
      )}
    </div>
  );
}

function SnapshotSuccess({ result, onNew }: { result: SnapshotResult; onNew: () => void }) {
  return (
    <div style={{ marginTop: '12px' }}>
      <div style={sStyles.successBox}>
        <h3 style={{ margin: '0 0 8px 0', fontSize: '15px', color: '#2e7d32' }}>✅ Snapshot Created!</h3>
        <p style={{ margin: '4px 0', fontSize: '13px' }}>ID: {result.snapshotId} · Drawings: {result.drawingsCount}</p>
        {result.validationResult && (
          <div style={{ marginTop: '8px' }}>
            <span style={{
              display: 'inline-block', padding: '3px 10px', borderRadius: '10px', fontSize: '12px', fontWeight: 'bold',
              backgroundColor: result.validationResult.isValid ? '#4caf50' : '#ff9800', color: 'white',
            }}>
              {result.validationResult.isValid ? '✓ Valid' : '⚠ Issues'} ({Math.round(result.validationResult.confidence * 100)}%)
            </span>
            <p style={{ margin: '8px 0 0', fontSize: '13px', color: '#555' }}>{result.validationResult.reason}</p>
          </div>
        )}
      </div>
      <button onClick={onNew} style={{ ...sStyles.submitButton, marginTop: '12px', backgroundColor: '#1976d2' }}>
        + New Snapshot
      </button>
    </div>
  );
}

// ============ Technical AI Tab ============

interface TechnicalAITabProps {
  chatHistory: ChatMessage[];
  chatLoading: boolean;
  chatError: string | null;
  validateInput: string;
  publicSnapshots: any[];
  chatBottomRef: React.RefObject<HTMLDivElement>;
  onValidateInputChange: (v: string) => void;
  onReason: () => void;
  onValidate: () => void;
}

function TechnicalAITab({
  chatHistory, chatLoading, chatError, validateInput, publicSnapshots,
  chatBottomRef, onValidateInputChange, onReason, onValidate,
}: TechnicalAITabProps) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
      <div style={styles.chatHistory}>
        {chatHistory.length === 0 && (
          <div style={styles.chatEmpty}>
            Draw objects on the chart, then click "Reason my drawings" or type a claim to validate.
          </div>
        )}
        {chatHistory.map((msg, i) => (
          <div key={i} style={msg.role === 'user' ? styles.chatBubbleUser : styles.chatBubbleAi}>
            {msg.content}
          </div>
        ))}
        {chatLoading && <div style={styles.chatBubbleAi}>Thinking...</div>}
        <div ref={chatBottomRef} />
      </div>

      {chatError && <div style={styles.error}>{chatError}</div>}

      <button onClick={onReason} disabled={chatLoading}
        style={{ ...styles.reasonButton, opacity: chatLoading ? 0.6 : 1 }}>
        Reason my drawings
      </button>

      <div style={{ display: 'flex', gap: '8px' }}>
        <input
          type="text"
          placeholder="Type a claim to validate..."
          value={validateInput}
          onChange={e => onValidateInputChange(e.target.value)}
          onKeyDown={e => e.key === 'Enter' && !chatLoading && validateInput.trim() && onValidate()}
          disabled={chatLoading}
          style={styles.validateInput}
        />
        <button onClick={onValidate} disabled={chatLoading || !validateInput.trim()}
          style={{ ...styles.validateButton, opacity: chatLoading || !validateInput.trim() ? 0.6 : 1 }}>
          Validate
        </button>
      </div>

      <div>
        <h3 style={styles.sectionTitle}>Community Views</h3>
        {publicSnapshots.length === 0 ? (
          <div style={styles.emptyState}>No public snapshots yet for this symbol</div>
        ) : (
          <div style={styles.snapshotsList}>
            {publicSnapshots.map((snap: any) => (
              <div key={snap.snapshotId} style={styles.snapshotCard}>
                <div style={styles.snapshotHeader}>
                  <span style={styles.snapshotUser}>@{snap.username}</span>
                  <span style={styles.snapshotPattern}>{snap.patternType}</span>
                </div>
                <p style={styles.snapshotComment}>{snap.userComment}</p>
                {snap.aiValidation && <p style={styles.snapshotAi}>AI: {snap.aiValidation}</p>}
                <div style={styles.snapshotFooter}>
                  <span>❤️ {snap.likesCount}</span>
                  <span>💬 {snap.commentsCount}</span>
                  <span>{snap.timeframe}</span>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

// ============ Existing Tab Components ============

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
              <a href={item.url} target="_blank" rel="noopener noreferrer" style={styles.newsLink}>Read more →</a>
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
        Overall: <span style={getSentimentStyle(data.overallSentiment)}>{data.overallSentiment.toUpperCase()}</span>
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
          {data.recentSnapshots.map(snapshot => (
            <div key={snapshot.snapshotId} style={styles.snapshotCard}>
              <div style={styles.snapshotHeader}>
                <span style={styles.snapshotUser}>@{snapshot.username}</span>
                <span style={styles.snapshotPattern}>{snapshot.patternType}</span>
              </div>
              <p style={styles.snapshotComment}>{snapshot.userComment}</p>
              {snapshot.aiValidation && <p style={styles.snapshotAi}>AI: {snapshot.aiValidation}</p>}
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

function DataRow({ label, value }: { label: string; value: string | number | undefined }) {
  return (
    <div style={styles.dataRow}>
      <span style={styles.dataLabel}>{label}:</span>
      <span style={styles.dataValue}>{value ?? 'N/A'}</span>
    </div>
  );
}

function getSentimentStyle(sentiment: string): React.CSSProperties {
  const base = { padding: '2px 8px', borderRadius: '4px', fontWeight: 'bold' as const, fontSize: '12px' };
  switch (sentiment.toLowerCase()) {
    case 'positive': case 'bullish': return { ...base, backgroundColor: '#e8f5e9', color: '#2e7d32' };
    case 'negative': case 'bearish': return { ...base, backgroundColor: '#ffebee', color: '#c62828' };
    default: return { ...base, backgroundColor: '#f5f5f5', color: '#616161' };
  }
}

// ============ Styles — AnalysisPanel ============

const styles: Record<string, React.CSSProperties> = {
  overlay: { position: 'fixed', top: 0, right: 0, bottom: 0, width: '450px', backgroundColor: 'white', boxShadow: '-2px 0 10px rgba(0,0,0,0.1)', zIndex: 10000, display: 'flex', flexDirection: 'column' },
  panel: { display: 'flex', flexDirection: 'column', height: '100%' },
  header: { display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '16px', borderBottom: '1px solid #e0e0e0' },
  title: { margin: 0, fontSize: '18px', fontWeight: 600 },
  closeButton: { background: 'none', border: 'none', fontSize: '24px', cursor: 'pointer', padding: '4px 8px', color: '#666' },
  tabContainer: { display: 'flex', borderBottom: '1px solid #e0e0e0' },
  tab: { flex: 1, padding: '10px 2px', background: 'none', border: 'none', cursor: 'pointer', fontSize: '11px', color: '#666', borderBottom: '2px solid transparent' },
  tabActive: { flex: 1, padding: '10px 2px', background: 'none', border: 'none', cursor: 'pointer', fontSize: '11px', fontWeight: 600, color: '#1976d2', borderBottom: '2px solid #1976d2' },
  content: { flex: 1, overflowY: 'auto', padding: '16px', display: 'flex', flexDirection: 'column' },
  footer: { padding: '16px', borderTop: '1px solid #e0e0e0' },
  refreshButton: { width: '100%', padding: '10px', backgroundColor: '#1976d2', color: 'white', border: 'none', borderRadius: '4px', cursor: 'pointer', fontSize: '14px', fontWeight: 600 },
  loading: { textAlign: 'center', padding: '32px', color: '#666' },
  error: { padding: '12px', backgroundColor: '#ffebee', color: '#c62828', borderRadius: '4px', marginBottom: '8px', fontSize: '13px' },
  tabContent: { animation: 'fadeIn 0.3s' },
  dataGrid: { display: 'flex', flexDirection: 'column', gap: '8px', marginBottom: '16px' },
  dataRow: { display: 'flex', justifyContent: 'space-between', padding: '8px', backgroundColor: '#f5f5f5', borderRadius: '4px' },
  dataLabel: { fontWeight: 500, color: '#666' },
  dataValue: { fontWeight: 600, color: '#333' },
  lastUpdated: { fontSize: '12px', color: '#999', textAlign: 'right' },
  sectionTitle: { fontSize: '14px', fontWeight: 600, marginTop: '12px', marginBottom: '8px', color: '#333' },
  newsItem: { padding: '12px', marginBottom: '12px', border: '1px solid #e0e0e0', borderRadius: '4px' },
  newsHeader: { display: 'flex', justifyContent: 'space-between', marginBottom: '8px' },
  newsSource: { fontSize: '12px', color: '#999' },
  newsTitle: { margin: '0 0 8px 0', fontSize: '14px', fontWeight: 600 },
  newsDescription: { margin: '0 0 8px 0', fontSize: '13px', color: '#666', lineHeight: '1.4' },
  newsFooter: { display: 'flex', justifyContent: 'space-between', alignItems: 'center' },
  newsDate: { fontSize: '12px', color: '#999' },
  newsLink: { fontSize: '12px', color: '#1976d2', textDecoration: 'none' },
  emptyState: { textAlign: 'center', padding: '24px', color: '#999', fontSize: '13px' },
  sentimentBar: { display: 'flex', height: '40px', borderRadius: '4px', overflow: 'hidden', marginBottom: '16px' },
  sentimentSegment: { display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'white', fontWeight: 'bold', fontSize: '14px' },
  snapshotsList: { display: 'flex', flexDirection: 'column', gap: '8px' },
  snapshotCard: { padding: '10px', border: '1px solid #e0e0e0', borderRadius: '4px', backgroundColor: '#fafafa' },
  snapshotHeader: { display: 'flex', justifyContent: 'space-between', marginBottom: '6px' },
  snapshotUser: { fontWeight: 600, color: '#1976d2', fontSize: '13px' },
  snapshotPattern: { fontSize: '11px', padding: '2px 6px', backgroundColor: '#e3f2fd', color: '#1565c0', borderRadius: '4px' },
  snapshotComment: { margin: '0 0 6px 0', fontSize: '13px', color: '#333' },
  snapshotAi: { margin: '0 0 6px 0', fontSize: '12px', color: '#666', fontStyle: 'italic' },
  snapshotFooter: { display: 'flex', gap: '10px', fontSize: '12px', color: '#999' },
  // Technical AI
  chatHistory: { maxHeight: '300px', overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: '8px', padding: '4px', border: '1px solid #e0e0e0', borderRadius: '4px', minHeight: '60px', backgroundColor: '#fafafa' },
  chatEmpty: { textAlign: 'center', padding: '16px', color: '#aaa', fontSize: '13px' },
  chatBubbleUser: { alignSelf: 'flex-end', backgroundColor: '#1976d2', color: 'white', padding: '8px 12px', borderRadius: '12px 12px 2px 12px', maxWidth: '85%', fontSize: '13px', lineHeight: '1.4', whiteSpace: 'pre-wrap' },
  chatBubbleAi: { alignSelf: 'flex-start', backgroundColor: '#f0f0f0', color: '#333', padding: '8px 12px', borderRadius: '12px 12px 12px 2px', maxWidth: '95%', fontSize: '13px', lineHeight: '1.5', whiteSpace: 'pre-wrap' },
  reasonButton: { width: '100%', padding: '10px', backgroundColor: '#7b1fa2', color: 'white', border: 'none', borderRadius: '4px', cursor: 'pointer', fontSize: '14px', fontWeight: 600 },
  validateInput: { flex: 1, padding: '8px 12px', border: '1px solid #ccc', borderRadius: '4px', fontSize: '13px', outline: 'none' },
  validateButton: { padding: '8px 16px', backgroundColor: '#388e3c', color: 'white', border: 'none', borderRadius: '4px', cursor: 'pointer', fontSize: '13px', fontWeight: 600 },
};

// ============ Styles — SnapshotTab ============

const sStyles: Record<string, React.CSSProperties> = {
  summaryLoading: { fontSize: '12px', color: '#999', padding: '8px 0' },
  summaryBar: { display: 'flex', flexWrap: 'wrap', gap: '8px', padding: '8px 12px', backgroundColor: '#e3f2fd', borderRadius: '6px', fontSize: '13px', color: '#1565c0', marginBottom: '4px' },
  summaryNews: { width: '100%', fontSize: '12px', color: '#555', lineHeight: '1.4', marginTop: '4px' },
  formGroup: { marginBottom: '14px' },
  label: { display: 'block', marginBottom: '6px', fontWeight: 500, color: '#333', fontSize: '13px' },
  tagContainer: { display: 'flex', flexWrap: 'wrap', gap: '6px', padding: '6px', border: '1px solid #ccc', borderRadius: '6px', minHeight: '38px', backgroundColor: 'white', position: 'relative' },
  tagChip: { display: 'inline-flex', alignItems: 'center', gap: '4px', padding: '3px 8px 3px 10px', backgroundColor: '#1976d2', color: 'white', borderRadius: '14px', fontSize: '12px', fontWeight: 500 },
  tagRemove: { background: 'none', border: 'none', color: 'white', fontSize: '16px', cursor: 'pointer', padding: '0 2px', lineHeight: 1, fontWeight: 'bold' },
  tagInput: { border: 'none', outline: 'none', fontSize: '13px', width: '100%', padding: '2px 4px' },
  suggestionsDropdown: { position: 'absolute', top: '100%', left: 0, right: 0, backgroundColor: 'white', border: '1px solid #ccc', borderRadius: '4px', maxHeight: '160px', overflowY: 'auto', zIndex: 1000, marginTop: '2px', boxShadow: '0 4px 12px rgba(0,0,0,0.15)' },
  suggestionItem: { padding: '7px 12px', cursor: 'pointer', fontSize: '13px', borderBottom: '1px solid #f0f0f0' },
  textarea: { width: '100%', padding: '8px 10px', border: '1px solid #ccc', borderRadius: '6px', fontSize: '13px', fontFamily: 'inherit', resize: 'vertical', boxSizing: 'border-box' },
  radioLabel: { display: 'flex', alignItems: 'center', cursor: 'pointer' },
  errorBox: { padding: '10px 12px', backgroundColor: '#ffebee', color: '#c62828', borderRadius: '6px', marginBottom: '12px', fontSize: '13px' },
  successBox: { padding: '14px', backgroundColor: '#e8f5e9', borderRadius: '6px' },
  submitButton: { width: '100%', padding: '10px', border: 'none', borderRadius: '6px', backgroundColor: '#f57c00', color: 'white', fontSize: '14px', fontWeight: 600, cursor: 'pointer' },
};
