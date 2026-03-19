// Snapshot API - Frontend integration for chart snapshot features
import { apiFetch, getApiUrl } from '../config/api';
import { withAuth } from '../utils/apiHelper';

// ============ Types ============

export interface SnapshotRequest {
  symbol: string;
  timeframe: string;
  layoutId?: number;
  startDate?: string; // ISO date string
  endDate?: string;
  chartStateJson: string;
  userComment: string;
  patternType?: string; // @deprecated Use patternTags instead
  patternTags?: string[]; // Multiple pattern tags
  visibility: 'private' | 'public' | 'group';
  groupIds?: string;
  performAiValidation?: boolean;
}

export interface ValidationResult {
  isValid: boolean;
  confidence: number;
  reason: string;
  detailedFeedback?: string;
  suggestions?: string[];
  violations?: RuleViolation[];
  metrics?: Record<string, any>;
  alternatives?: AlternativeInterpretation[];
  tradingImplication?: TradingImplication;
}

export interface RuleViolation {
  ruleName: string;
  description: string;
  severity: 'error' | 'warning' | 'info';
}

export interface AlternativeInterpretation {
  patternType: string;
  probability: number;
  description: string;
}

export interface TradingImplication {
  bias: 'bullish' | 'bearish' | 'neutral';
  targetPrice?: number;
  stopLoss?: number;
  timeframe?: string;
  strategy?: string;
}

export interface SnapshotResult {
  snapshotId?: number;
  success: boolean;
  message: string;
  validationResult?: ValidationResult;
  patternType?: string;
  drawingsCount?: number;
  aiUsed?: boolean;
  warnings?: string;
}

export interface ChartSnapshot {
  id: number;
  username: string;
  symbol: string;
  timeframe: string;
  layoutId?: number;
  snapshotTimestamp: string;
  startDate?: string;
  endDate?: string;
  chartStateJson: string;
  userComment: string;
  patternType: string;
  aiValidation?: string;
  aiAnalysis?: string;
  visibility: 'private' | 'public' | 'group';
  groupIds?: string;
  likesCount: number;
  viewsCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface PagedSnapshots {
  content: ChartSnapshot[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

// ============ API Functions ============

/**
 * Create a new snapshot with validation
 */
export async function createSnapshot(request: SnapshotRequest): Promise<SnapshotResult> {
  const url = getApiUrl('/api/snapshots/create');
  const response = await apiFetch(url.toString(), withAuth({
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request)
  }));

  if (!response.ok) {
    const error = await response.json().catch(() => ({ message: 'Failed to create snapshot' }));
    throw new Error(error.message || 'Failed to create snapshot');
  }

  return await response.json();
}

/**
 * Validate pattern without creating snapshot (preview)
 */
export async function validatePattern(request: SnapshotRequest): Promise<ValidationResult> {
  const url = getApiUrl('/api/snapshots/validate');
  const response = await apiFetch(url.toString(), withAuth({
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request)
  }));

  if (!response.ok) {
    throw new Error('Validation failed');
  }

  return await response.json();
}

/**
 * Get snapshot by ID
 */
export async function getSnapshot(id: number): Promise<ChartSnapshot> {
  const url = getApiUrl(`/api/snapshots/${id}`);
  const response = await apiFetch(url.toString(), withAuth());

  if (!response.ok) {
    throw new Error('Snapshot not found');
  }

  return await response.json();
}

/**
 * Get user's own snapshots
 */
export async function getMySnapshots(page: number = 0, size: number = 20): Promise<PagedSnapshots> {
  const url = getApiUrl('/api/snapshots/my-snapshots');
  url.searchParams.set('page', page.toString());
  url.searchParams.set('size', size.toString());

  const response = await apiFetch(url.toString(), withAuth());

  if (!response.ok) {
    throw new Error('Failed to fetch snapshots');
  }

  return await response.json();
}

/**
 * Get public snapshots (social feed)
 */
export async function getPublicSnapshots(
  filters: { symbol?: string; pattern?: string },
  page: number = 0,
  size: number = 20
): Promise<PagedSnapshots> {
  const url = getApiUrl('/api/snapshots/public');
  url.searchParams.set('page', page.toString());
  url.searchParams.set('size', size.toString());

  if (filters.symbol) {
    url.searchParams.set('symbol', filters.symbol);
  }
  if (filters.pattern) {
    url.searchParams.set('pattern', filters.pattern);
  }

  const response = await apiFetch(url.toString(), withAuth());

  if (!response.ok) {
    throw new Error('Failed to fetch public snapshots');
  }

  return await response.json();
}

/**
 * Get trending snapshots (most liked)
 */
export async function getTrendingSnapshots(page: number = 0, size: number = 10): Promise<PagedSnapshots> {
  const url = getApiUrl('/api/snapshots/trending');
  url.searchParams.set('page', page.toString());
  url.searchParams.set('size', size.toString());

  const response = await apiFetch(url.toString(), withAuth());

  if (!response.ok) {
    throw new Error('Failed to fetch trending snapshots');
  }

  return await response.json();
}

/**
 * Delete snapshot
 */
export async function deleteSnapshot(id: number): Promise<void> {
  const url = getApiUrl(`/api/snapshots/${id}`);
  const response = await apiFetch(url.toString(), withAuth({ method: 'DELETE' }));

  if (!response.ok) {
    throw new Error('Failed to delete snapshot');
  }
}

/**
 * Update snapshot visibility
 */
export async function updateVisibility(
  id: number,
  visibility: 'private' | 'public' | 'group',
  groupIds?: string
): Promise<void> {
  const url = getApiUrl(`/api/snapshots/${id}/visibility`);
  const response = await apiFetch(url.toString(), withAuth({
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ visibility, groupIds })
  }));

  if (!response.ok) {
    throw new Error('Failed to update visibility');
  }
}

/**
 * Like a snapshot
 */
export async function likeSnapshot(id: number): Promise<number> {
  const url = getApiUrl(`/api/snapshots/${id}/like`);
  const response = await apiFetch(url.toString(), withAuth({ method: 'POST' }));

  if (!response.ok) {
    throw new Error('Failed to like snapshot');
  }

  const result = await response.json();
  return result.likesCount;
}

/**
 * Get user's snapshot statistics
 */
export async function getSnapshotStats(): Promise<{ total: number; public: number; private: number }> {
  const url = getApiUrl('/api/snapshots/stats');
  const response = await apiFetch(url.toString(), withAuth());

  if (!response.ok) {
    throw new Error('Failed to fetch stats');
  }

  return await response.json();
}
