// Stock Analysis API - Frontend integration for analysis features
import { apiFetch, getApiUrl } from '../config/api';
import { withAuth } from '../utils/apiHelper';

// ============ Types ============

export interface FundamentalsData {
  peRatio: number;
  marketCap: number;
  epsValue: number;
  dividendYield: number;
  sector: string;
  industry: string;
  yearHigh: number;
  yearLow: number;
  currentPrice: number;
  lastUpdated: string;
}

export interface NewsItem {
  title: string;
  description: string;
  url: string;
  source: string;
  publishedAt: string;
  sentiment: 'positive' | 'negative' | 'neutral';
}

export interface CorrelationData {
  niftyCorrelation: number;
  sectorIndexCorrelation: number;
  sectorIndexName: string;
  relatedStocksCorrelation: Record<string, number>;
  correlationPeriod: string;
}

export interface PublicSnapshotSummary {
  snapshotId: number;
  username: string;
  patternType: string;
  userComment: string;
  aiValidation: string;
  likesCount: number;
  commentsCount: number;
  createdAt: string;
  timeframe: string;
}

export interface SocialSentimentData {
  overallSentiment: 'bullish' | 'bearish' | 'neutral';
  bullishCount: number;
  bearishCount: number;
  neutralCount: number;
  recentSnapshots: PublicSnapshotSummary[];
}

export interface StockAnalysisResponse {
  symbol: string;
  timeframe: string;
  fundamentals: FundamentalsData;
  news: NewsItem[];
  correlation: CorrelationData;
  socialSentiment: SocialSentimentData;
}

// ============ API Functions ============

/**
 * Get comprehensive stock analysis
 * Includes fundamentals, news, correlation, and social sentiment
 */
export async function analyzeStock(
  symbol: string,
  timeframe: string = '1d'
): Promise<StockAnalysisResponse> {
  const url = getApiUrl('/api/analysis/analyze');
  const response = await apiFetch(url.toString(), withAuth({
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ symbol, timeframe })
  }));

  if (!response.ok) {
    throw new Error(`Analysis failed: ${response.status}`);
  }

  return await response.json();
}

/**
 * Get only fundamentals data
 */
export async function getFundamentals(symbol: string): Promise<FundamentalsData> {
  const url = getApiUrl('/api/analysis/fundamentals');
  url.searchParams.set('symbol', symbol);

  const response = await apiFetch(url.toString(), withAuth());

  if (!response.ok) {
    throw new Error(`Fundamentals fetch failed: ${response.status}`);
  }

  return await response.json();
}

/**
 * Get only news data
 */
export async function getNews(symbol: string): Promise<NewsItem[]> {
  const url = getApiUrl('/api/analysis/news');
  url.searchParams.set('symbol', symbol);

  const response = await apiFetch(url.toString(), withAuth());

  if (!response.ok) {
    throw new Error(`News fetch failed: ${response.status}`);
  }

  return await response.json();
}

/**
 * Get only correlation data
 */
export async function getCorrelation(symbol: string): Promise<CorrelationData> {
  const url = getApiUrl('/api/analysis/correlation');
  url.searchParams.set('symbol', symbol);

  const response = await apiFetch(url.toString(), withAuth());

  if (!response.ok) {
    throw new Error(`Correlation fetch failed: ${response.status}`);
  }

  return await response.json();
}

/**
 * Get only social sentiment data
 */
export async function getSocialSentiment(symbol: string): Promise<SocialSentimentData> {
  const url = getApiUrl('/api/analysis/social');
  url.searchParams.set('symbol', symbol);

  const response = await apiFetch(url.toString(), withAuth());

  if (!response.ok) {
    throw new Error(`Social sentiment fetch failed: ${response.status}`);
  }

  return await response.json();
}

/**
 * Clear cache for a specific symbol
 */
export async function clearAnalysisCache(symbol: string): Promise<void> {
  const url = getApiUrl('/api/analysis/cache');
  url.searchParams.set('symbol', symbol);

  const response = await apiFetch(url.toString(), withAuth({ method: 'DELETE' }));

  if (!response.ok) {
    throw new Error(`Cache clear failed: ${response.status}`);
  }
}
