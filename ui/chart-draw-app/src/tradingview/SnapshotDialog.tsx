import React, { useState, useEffect, useRef } from 'react';
import { createSnapshot, SnapshotRequest, SnapshotResult } from './snapshotApi';
import { apiFetch, getApiUrl } from '../config/api';
import { withAuth } from '../utils/apiHelper';

interface SnapshotDialogProps {
  open: boolean;
  symbol: string;
  timeframe: string;
  onClose: () => void;
  onSuccess?: (result: SnapshotResult) => void;
  getChartState: () => string;
}

const COMMON_TAGS = [
  'Head and Shoulders',
  'Inverse Head and Shoulders',
  'Double Top',
  'Double Bottom',
  'Triangle',
  'Ascending Triangle',
  'Descending Triangle',
  'Wedge',
  'Rising Wedge',
  'Falling Wedge',
  'Channel',
  'Support',
  'Resistance',
  'Support/Resistance',
  'Trendline',
  'Breakout',
  'Breakdown',
  'Flag',
  'Pennant',
  'Cup and Handle',
  'Elliott Wave',
  'Fibonacci',
  'Consolidation',
  'Reversal',
  'Continuation'
];

interface AnalysisSummary {
  symbol: string;
  timeframe: string;
  price: number | null;
  peRatio: number | null;
  sector: string | null;
  sentiment: string;
  recentSnapshotsCount: number;
  commonTags: string[];
  latestNews: string | null;
}

export default function SnapshotDialog({
  open,
  symbol,
  timeframe,
  onClose,
  onSuccess,
  getChartState
}: SnapshotDialogProps) {
  const [userComment, setUserComment] = useState('');
  const [selectedTags, setSelectedTags] = useState<string[]>([]);
  const [tagInput, setTagInput] = useState('');
  const [tagSuggestions, setTagSuggestions] = useState<string[]>([]);
  const [showSuggestions, setShowSuggestions] = useState(false);
  const [validating, setValidating] = useState(false);
  const [visibility, setVisibility] = useState<'private' | 'public' | 'group'>('private');
  const [performAiValidation, setPerformAiValidation] = useState(true);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<SnapshotResult | null>(null);
  const tagInputRef = useRef<HTMLInputElement>(null);

  // Analysis summary state
  const [summary, setSummary] = useState<AnalysisSummary | null>(null);
  const [summaryLoading, setSummaryLoading] = useState(false);
  const [showSummary, setShowSummary] = useState(true);

  // Fetch analysis summary when dialog opens
  useEffect(() => {
    if (open && symbol) {
      fetchAnalysisSummary();
    }
  }, [open, symbol, timeframe]);

  // Filter suggestions based on input
  useEffect(() => {
    if (tagInput.trim().length > 0) {
      const filtered = COMMON_TAGS.filter(
        tag =>
          tag.toLowerCase().includes(tagInput.toLowerCase()) &&
          !selectedTags.includes(tag)
      );
      setTagSuggestions(filtered);
      setShowSuggestions(filtered.length > 0);
    } else {
      setShowSuggestions(false);
    }
  }, [tagInput, selectedTags]);

  const fetchAnalysisSummary = async () => {
    setSummaryLoading(true);
    try {
      const url = getApiUrl('/api/snapshots/summary');
      url.searchParams.set('symbol', symbol);
      url.searchParams.set('timeframe', timeframe);

      const response = await apiFetch(url.toString(), withAuth());
      if (response.ok) {
        const data = await response.json();
        setSummary(data);
      }
    } catch (err) {
      console.error('Error fetching analysis summary:', err);
      // Don't show error, just skip summary
    } finally {
      setSummaryLoading(false);
    }
  };

  const validateTag = async (tag: string): Promise<string> => {
    try {
      const url = getApiUrl('/api/tags/validate');
      url.searchParams.set('tag', tag);

      const response = await apiFetch(url.toString(), withAuth());
      if (response.ok) {
        const data = await response.json();
        return data.normalized || tag;
      }
    } catch (err) {
      console.error('Tag validation error:', err);
    }
    return tag;
  };

  const addTag = async (tag: string) => {
    const trimmed = tag.trim();
    if (!trimmed) return;

    // Check if already exists (case-insensitive)
    if (selectedTags.some(t => t.toLowerCase() === trimmed.toLowerCase())) {
      setTagInput('');
      setShowSuggestions(false);
      return;
    }

    // Validate and normalize the tag
    setValidating(true);
    try {
      const normalized = await validateTag(trimmed);

      // Check again after normalization
      if (!selectedTags.some(t => t.toLowerCase() === normalized.toLowerCase())) {
        setSelectedTags([...selectedTags, normalized]);
      }
    } catch (err) {
      console.error('Error adding tag:', err);
      // Add anyway with basic normalization
      const basicNormalized = trimmed.charAt(0).toUpperCase() + trimmed.slice(1).toLowerCase();
      if (!selectedTags.includes(basicNormalized)) {
        setSelectedTags([...selectedTags, basicNormalized]);
      }
    } finally {
      setValidating(false);
      setTagInput('');
      setShowSuggestions(false);
    }
  };

  const removeTag = (tagToRemove: string) => {
    setSelectedTags(selectedTags.filter(tag => tag !== tagToRemove));
  };

  const handleTagInputKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      if (tagInput.trim()) {
        addTag(tagInput);
      }
    } else if (e.key === 'Backspace' && !tagInput && selectedTags.length > 0) {
      // Remove last tag if backspace on empty input
      removeTag(selectedTags[selectedTags.length - 1]);
    } else if (e.key === 'Escape') {
      setShowSuggestions(false);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    if (!userComment.trim()) {
      setError('Please provide your analysis/reason');
      return;
    }

    if (selectedTags.length === 0) {
      setError('Please add at least one pattern tag');
      return;
    }

    setLoading(true);
    setError(null);
    setResult(null);

    try {
      const chartStateJson = getChartState();

      const request: SnapshotRequest = {
        symbol,
        timeframe,
        chartStateJson,
        userComment: userComment.trim(),
        patternTags: selectedTags,
        visibility,
        performAiValidation
      };

      const snapshotResult = await createSnapshot(request);
      setResult(snapshotResult);

      if (snapshotResult.success) {
        if (onSuccess) {
          onSuccess(snapshotResult);
        }
        setTimeout(() => {
          handleClose();
        }, 3000);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to create snapshot');
    } finally {
      setLoading(false);
    }
  };

  const handleClose = () => {
    setUserComment('');
    setSelectedTags([]);
    setTagInput('');
    setVisibility('private');
    setPerformAiValidation(true);
    setError(null);
    setResult(null);
    onClose();
  };

  if (!open) return null;

  return (
    <div style={styles.overlay} onClick={handleClose}>
      <div style={styles.dialog} onClick={(e) => e.stopPropagation()}>
        {/* Header */}
        <div style={styles.header}>
          <h2 style={styles.title}>📸 Snapshot & Reason</h2>
          <button onClick={handleClose} style={styles.closeButton}>✕</button>
        </div>

        {/* Content */}
        <div style={styles.content}>
          <div style={styles.infoBox}>
            <strong>{symbol}</strong> • {timeframe}
          </div>

          {/* Analysis Summary */}
          {showSummary && (
            <div style={styles.summarySection}>
              <div style={styles.summaryHeader}>
                <span style={styles.summaryTitle}>📊 Quick Analysis</span>
                <button
                  type="button"
                  onClick={() => setShowSummary(false)}
                  style={styles.summaryToggle}
                >
                  ▼
                </button>
              </div>

              {summaryLoading ? (
                <div style={styles.summaryLoading}>Loading analysis...</div>
              ) : summary ? (
                <div style={styles.summaryContent}>
                  {/* Price and Fundamentals */}
                  {summary.price && (
                    <div style={styles.summaryRow}>
                      <span style={styles.summaryLabel}>Price:</span>
                      <span style={styles.summaryValue}>₹{summary.price.toFixed(2)}</span>
                      {summary.peRatio && (
                        <span style={styles.summaryValue}>• PE: {summary.peRatio.toFixed(1)}</span>
                      )}
                      {summary.sector && (
                        <span style={styles.summaryValue}>• {summary.sector}</span>
                      )}
                    </div>
                  )}

                  {/* Sentiment */}
                  {summary.sentiment && summary.sentiment !== 'neutral' && (
                    <div style={styles.summaryRow}>
                      <span style={styles.summaryLabel}>Sentiment:</span>
                      <span style={{
                        ...styles.summaryValue,
                        color: summary.sentiment === 'bullish' ? '#2e7d32' : summary.sentiment === 'bearish' ? '#c62828' : '#666'
                      }}>
                        {summary.sentiment === 'bullish' ? '📈' : summary.sentiment === 'bearish' ? '📉' : '➡️'}
                        {' '}{summary.sentiment.charAt(0).toUpperCase() + summary.sentiment.slice(1)}
                      </span>
                      {summary.recentSnapshotsCount > 0 && (
                        <span style={styles.summaryMuted}>
                          ({summary.recentSnapshotsCount} snapshots)
                        </span>
                      )}
                    </div>
                  )}

                  {/* Common Tags */}
                  {summary.commonTags && summary.commonTags.length > 0 && (
                    <div style={styles.summaryRow}>
                      <span style={styles.summaryLabel}>Popular Tags:</span>
                      <span style={styles.summaryValue}>{summary.commonTags.join(', ')}</span>
                    </div>
                  )}

                  {/* Latest News */}
                  {summary.latestNews && (
                    <div style={styles.summaryRow}>
                      <span style={styles.summaryLabel}>📰</span>
                      <span style={styles.summaryNewsText}>{summary.latestNews}</span>
                    </div>
                  )}
                </div>
              ) : null}
            </div>
          )}
          {!showSummary && (
            <button
              type="button"
              onClick={() => setShowSummary(true)}
              style={styles.showSummaryButton}
            >
              📊 Show Analysis Summary
            </button>
          )}

          <form onSubmit={handleSubmit}>
            {/* Pattern Tags */}
            <div style={styles.formGroup}>
              <label style={styles.label}>
                Pattern Tags <span style={styles.required}>*</span>
              </label>
              <div style={styles.tagContainer}>
                {/* Selected Tags (Chips) */}
                {selectedTags.map((tag) => (
                  <div key={tag} style={styles.tagChip}>
                    <span>{tag}</span>
                    <button
                      type="button"
                      onClick={() => removeTag(tag)}
                      style={styles.tagRemove}
                      disabled={loading}
                    >
                      ×
                    </button>
                  </div>
                ))}

                {/* Tag Input */}
                <div style={styles.tagInputWrapper}>
                  <input
                    ref={tagInputRef}
                    type="text"
                    value={tagInput}
                    onChange={(e) => setTagInput(e.target.value)}
                    onKeyDown={handleTagInputKeyDown}
                    placeholder={selectedTags.length === 0 ? "Type to add tags..." : ""}
                    style={styles.tagInput}
                    disabled={loading || validating}
                  />
                  {validating && <span style={styles.validatingSpinner}>⏳</span>}

                  {/* Suggestions Dropdown */}
                  {showSuggestions && tagSuggestions.length > 0 && (
                    <div style={styles.suggestionsDropdown}>
                      {tagSuggestions.slice(0, 10).map((suggestion) => (
                        <div
                          key={suggestion}
                          style={styles.suggestionItem}
                          onClick={() => addTag(suggestion)}
                        >
                          {suggestion}
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              </div>
              <div style={styles.hint}>
                Add multiple tags. Press Enter to add. Click suggestions or type custom tags.
              </div>
            </div>

            {/* User Comment/Analysis */}
            <div style={styles.formGroup}>
              <label style={styles.label}>
                Your Analysis / Reason <span style={styles.required}>*</span>
              </label>
              <textarea
                value={userComment}
                onChange={(e) => setUserComment(e.target.value)}
                placeholder="Explain your analysis, why you think this pattern is forming, key levels, targets, etc."
                style={styles.textarea}
                rows={6}
                disabled={loading}
                required
              />
              <div style={styles.charCount}>{userComment.length} / 1000</div>
            </div>

            {/* Visibility */}
            <div style={styles.formGroup}>
              <label style={styles.label}>Visibility</label>
              <div style={styles.radioGroup}>
                <label style={styles.radioLabel}>
                  <input
                    type="radio"
                    value="private"
                    checked={visibility === 'private'}
                    onChange={() => setVisibility('private')}
                    disabled={loading}
                  />
                  <span style={styles.radioText}>🔒 Private</span>
                </label>
                <label style={styles.radioLabel}>
                  <input
                    type="radio"
                    value="public"
                    checked={visibility === 'public'}
                    onChange={() => setVisibility('public')}
                    disabled={loading}
                  />
                  <span style={styles.radioText}>🌐 Public</span>
                </label>
              </div>
            </div>

            {/* AI Validation */}
            <div style={styles.formGroup}>
              <label style={styles.checkboxLabel}>
                <input
                  type="checkbox"
                  checked={performAiValidation}
                  onChange={(e) => setPerformAiValidation(e.target.checked)}
                  disabled={loading}
                />
                <span style={styles.checkboxText}>
                  🤖 Get AI validation (checks your pattern against technical rules)
                </span>
              </label>
            </div>

            {/* Error Message */}
            {error && (
              <div style={styles.errorBox}>
                {error}
              </div>
            )}

            {/* Success Result */}
            {result && result.success && (
              <div style={styles.successBox}>
                <h3 style={styles.resultTitle}>✅ Snapshot Created Successfully!</h3>
                <p><strong>Snapshot ID:</strong> {result.snapshotId}</p>
                <p><strong>Drawings Captured:</strong> {result.drawingsCount}</p>

                {result.validationResult && (
                  <div style={styles.validationBox}>
                    <h4 style={styles.validationTitle}>AI Validation:</h4>
                    <div style={result.validationResult.isValid ? styles.validBadge : styles.invalidBadge}>
                      {result.validationResult.isValid ? '✓ Valid' : '⚠ Issues Found'}
                      {' '}({Math.round(result.validationResult.confidence * 100)}% confidence)
                    </div>
                    <p style={styles.validationText}>{result.validationResult.reason}</p>

                    {result.validationResult.violations && result.validationResult.violations.length > 0 && (
                      <div style={styles.violations}>
                        <strong>Issues:</strong>
                        <ul style={styles.violationList}>
                          {result.validationResult.violations.map((v, i) => (
                            <li key={i} style={styles.violationItem}>
                              <span style={getSeverityBadge(v.severity)}>{v.severity}</span>
                              {v.description}
                            </li>
                          ))}
                        </ul>
                      </div>
                    )}
                  </div>
                )}
              </div>
            )}

            {/* Actions */}
            <div style={styles.actions}>
              <button
                type="button"
                onClick={handleClose}
                style={styles.cancelButton}
                disabled={loading}
              >
                Cancel
              </button>
              <button
                type="submit"
                style={styles.submitButton}
                disabled={loading || validating}
              >
                {loading ? 'Creating...' : '📸 Create Snapshot'}
              </button>
            </div>
          </form>
        </div>
      </div>
    </div>
  );
}

// Helper function for severity badges
function getSeverityBadge(severity: string): React.ReactElement {
  const style = {
    padding: '2px 6px',
    borderRadius: '3px',
    fontSize: '10px',
    fontWeight: 'bold' as const,
    marginRight: '8px',
    textTransform: 'uppercase' as const,
  };

  switch (severity) {
    case 'error':
      return <span style={{ ...style, backgroundColor: '#f44336', color: 'white' }}>ERROR</span>;
    case 'warning':
      return <span style={{ ...style, backgroundColor: '#ff9800', color: 'white' }}>WARN</span>;
    default:
      return <span style={{ ...style, backgroundColor: '#2196f3', color: 'white' }}>INFO</span>;
  }
}

// ============ Styles ============

const styles: Record<string, React.CSSProperties> = {
  overlay: {
    position: 'fixed',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    backgroundColor: 'rgba(0, 0, 0, 0.5)',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    zIndex: 10000,
  },
  dialog: {
    backgroundColor: 'white',
    borderRadius: '12px',
    boxShadow: '0 8px 32px rgba(0,0,0,0.3)',
    width: '90%',
    maxWidth: '600px',
    maxHeight: '90vh',
    display: 'flex',
    flexDirection: 'column',
  },
  header: {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: '20px 24px',
    borderBottom: '1px solid #e0e0e0',
  },
  title: {
    margin: 0,
    fontSize: '20px',
    fontWeight: 600,
    color: '#333',
  },
  closeButton: {
    background: 'none',
    border: 'none',
    fontSize: '24px',
    cursor: 'pointer',
    padding: '4px 8px',
    color: '#666',
  },
  content: {
    padding: '24px',
    overflowY: 'auto',
  },
  infoBox: {
    padding: '12px',
    backgroundColor: '#e3f2fd',
    borderRadius: '6px',
    marginBottom: '20px',
    textAlign: 'center',
    color: '#1565c0',
  },
  formGroup: {
    marginBottom: '20px',
  },
  label: {
    display: 'block',
    marginBottom: '8px',
    fontWeight: 500,
    color: '#333',
    fontSize: '14px',
  },
  required: {
    color: '#f44336',
  },
  tagContainer: {
    display: 'flex',
    flexWrap: 'wrap',
    gap: '8px',
    padding: '8px',
    border: '1px solid #ccc',
    borderRadius: '6px',
    minHeight: '42px',
    backgroundColor: 'white',
    position: 'relative',
  },
  tagChip: {
    display: 'inline-flex',
    alignItems: 'center',
    gap: '6px',
    padding: '4px 8px 4px 12px',
    backgroundColor: '#1976d2',
    color: 'white',
    borderRadius: '16px',
    fontSize: '13px',
    fontWeight: 500,
  },
  tagRemove: {
    background: 'none',
    border: 'none',
    color: 'white',
    fontSize: '18px',
    cursor: 'pointer',
    padding: '0 2px',
    lineHeight: 1,
    fontWeight: 'bold',
  },
  tagInputWrapper: {
    flex: 1,
    minWidth: '120px',
    position: 'relative',
  },
  tagInput: {
    border: 'none',
    outline: 'none',
    fontSize: '14px',
    width: '100%',
    padding: '4px',
  },
  validatingSpinner: {
    position: 'absolute',
    right: '8px',
    top: '4px',
  },
  suggestionsDropdown: {
    position: 'absolute',
    top: '100%',
    left: 0,
    right: 0,
    backgroundColor: 'white',
    border: '1px solid #ccc',
    borderRadius: '4px',
    maxHeight: '200px',
    overflowY: 'auto',
    zIndex: 1000,
    marginTop: '4px',
    boxShadow: '0 4px 12px rgba(0,0,0,0.15)',
  },
  suggestionItem: {
    padding: '8px 12px',
    cursor: 'pointer',
    fontSize: '14px',
    borderBottom: '1px solid #f0f0f0',
  },
  hint: {
    fontSize: '12px',
    color: '#999',
    marginTop: '6px',
  },
  textarea: {
    width: '100%',
    padding: '10px 12px',
    border: '1px solid #ccc',
    borderRadius: '6px',
    fontSize: '14px',
    fontFamily: 'inherit',
    resize: 'vertical',
  },
  charCount: {
    fontSize: '12px',
    color: '#999',
    textAlign: 'right',
    marginTop: '4px',
  },
  radioGroup: {
    display: 'flex',
    gap: '16px',
  },
  radioLabel: {
    display: 'flex',
    alignItems: 'center',
    cursor: 'pointer',
  },
  radioText: {
    marginLeft: '6px',
    fontSize: '14px',
  },
  checkboxLabel: {
    display: 'flex',
    alignItems: 'flex-start',
    cursor: 'pointer',
  },
  checkboxText: {
    marginLeft: '8px',
    fontSize: '14px',
    lineHeight: '1.5',
  },
  errorBox: {
    padding: '12px',
    backgroundColor: '#ffebee',
    color: '#c62828',
    borderRadius: '6px',
    marginBottom: '16px',
    fontSize: '14px',
  },
  successBox: {
    padding: '16px',
    backgroundColor: '#e8f5e9',
    borderRadius: '6px',
    marginBottom: '16px',
  },
  resultTitle: {
    margin: '0 0 12px 0',
    fontSize: '16px',
    color: '#2e7d32',
  },
  validationBox: {
    marginTop: '12px',
    padding: '12px',
    backgroundColor: 'white',
    borderRadius: '6px',
    border: '1px solid #c8e6c9',
  },
  validationTitle: {
    margin: '0 0 8px 0',
    fontSize: '14px',
    fontWeight: 600,
  },
  validBadge: {
    display: 'inline-block',
    padding: '4px 12px',
    backgroundColor: '#4caf50',
    color: 'white',
    borderRadius: '12px',
    fontSize: '12px',
    fontWeight: 'bold',
    marginBottom: '8px',
  },
  invalidBadge: {
    display: 'inline-block',
    padding: '4px 12px',
    backgroundColor: '#ff9800',
    color: 'white',
    borderRadius: '12px',
    fontSize: '12px',
    fontWeight: 'bold',
    marginBottom: '8px',
  },
  validationText: {
    margin: '8px 0',
    fontSize: '13px',
    color: '#666',
  },
  violations: {
    marginTop: '12px',
  },
  violationList: {
    margin: '8px 0',
    paddingLeft: '20px',
  },
  violationItem: {
    marginBottom: '6px',
    fontSize: '13px',
    lineHeight: '1.5',
  },
  actions: {
    display: 'flex',
    justifyContent: 'flex-end',
    gap: '12px',
    marginTop: '24px',
    paddingTop: '20px',
    borderTop: '1px solid #e0e0e0',
  },
  cancelButton: {
    padding: '10px 24px',
    border: '1px solid #ccc',
    borderRadius: '6px',
    backgroundColor: 'white',
    color: '#666',
    fontSize: '14px',
    fontWeight: 500,
    cursor: 'pointer',
  },
  submitButton: {
    padding: '10px 24px',
    border: 'none',
    borderRadius: '6px',
    backgroundColor: '#f57c00',
    color: 'white',
    fontSize: '14px',
    fontWeight: 600,
    cursor: 'pointer',
  },
  // Summary section styles
  summarySection: {
    backgroundColor: '#f9fafb',
    border: '1px solid #e0e0e0',
    borderRadius: '8px',
    marginBottom: '20px',
    overflow: 'hidden',
  },
  summaryHeader: {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: '12px 16px',
    backgroundColor: '#e8eaf6',
    borderBottom: '1px solid #d1d5db',
  },
  summaryTitle: {
    fontSize: '14px',
    fontWeight: 600,
    color: '#3f51b5',
  },
  summaryToggle: {
    background: 'none',
    border: 'none',
    cursor: 'pointer',
    fontSize: '12px',
    color: '#666',
    padding: '4px',
  },
  summaryLoading: {
    padding: '16px',
    textAlign: 'center',
    color: '#999',
    fontSize: '13px',
  },
  summaryContent: {
    padding: '12px 16px',
  },
  summaryRow: {
    marginBottom: '8px',
    fontSize: '13px',
    display: 'flex',
    alignItems: 'flex-start',
    gap: '8px',
  },
  summaryLabel: {
    fontWeight: 500,
    color: '#666',
    minWidth: '80px',
  },
  summaryValue: {
    color: '#333',
  },
  summaryMuted: {
    color: '#999',
    fontSize: '12px',
  },
  summaryNewsText: {
    flex: 1,
    color: '#333',
    fontSize: '12px',
    lineHeight: '1.4',
  },
  showSummaryButton: {
    width: '100%',
    padding: '10px',
    backgroundColor: '#e8eaf6',
    border: '1px solid #d1d5db',
    borderRadius: '6px',
    cursor: 'pointer',
    fontSize: '13px',
    color: '#3f51b5',
    fontWeight: 500,
    marginBottom: '16px',
  },
};
