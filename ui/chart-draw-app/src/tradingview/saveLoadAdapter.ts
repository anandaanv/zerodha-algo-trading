// TradingView Save/Load Adapter with symbol-agnostic layouts
// Layouts are now stored separately and drawings are tied to symbol + layoutId + timeframe
import { apiFetch, getApiUrl } from '../config/api';
import { withAuth } from '../utils/apiHelper';

// Helper function to recursively convert Maps and Map-like objects to plain objects
export function mapToObject(obj: any): any {
  // Handle native Map
  if (obj instanceof Map) {
    const result: any = {};
    obj.forEach((value, key) => {
      result[String(key)] = mapToObject(value);
    });
    return result;
  }

  // Handle Map-like objects (have forEach, size, entries methods)
  if (obj !== null && typeof obj === 'object' &&
      typeof obj.forEach === 'function' &&
      typeof obj.size !== 'undefined') {
    const result: any = {};
    try {
      obj.forEach((value: any, key: any) => {
        result[String(key)] = mapToObject(value);
      });
      return result;
    } catch (e) {
      console.error('Error iterating Map-like object:', e);
    }
  }

  // Handle arrays
  if (Array.isArray(obj)) {
    return obj.map(item => mapToObject(item));
  }

  // Handle plain objects
  if (obj !== null && typeof obj === 'object') {
    const result: any = {};
    const allKeys = Object.getOwnPropertyNames(obj);
    for (const key of allKeys) {
      try {
        const descriptor = Object.getOwnPropertyDescriptor(obj, key);
        if (descriptor && (descriptor.enumerable || descriptor.value !== undefined)) {
          result[key] = mapToObject(obj[key]);
        }
      } catch (e) {
        // Skip properties that throw errors
      }
    }
    for (const key in obj) {
      if (!result.hasOwnProperty(key)) {
        try {
          result[key] = mapToObject(obj[key]);
        } catch (e) {
          // Skip properties that throw errors
        }
      }
    }
    return result;
  }

  return obj;
}

const STORAGE_LAST_LAYOUT_KEY = 'lastLayoutId';

export interface AdapterContext {
  symbol: string;
  tabId: string;
  timeframe?: string;
}

// Maps a (tabId, symbol, timeframe) tuple to a scoped key for the drawings backend.
// Tabs isolated from each other; per-timeframe scoping nested inside that. The
// timeframe component is appended into the `symbol` column rather than relying on
// the dedicated timeframe column because user_chart_state has a legacy unique key
// on (symbol, layout_id) that doesn't include timeframe — without encoding the TF
// here, saves collide with any pre-existing null-timeframe row.
function scopedSymbol(ctx: AdapterContext): string {
  const base = `${ctx.tabId}:${ctx.symbol}`;
  return ctx.timeframe ? `${base}:${ctx.timeframe}` : base;
}

export function createSaveLoadAdapter(getContext: () => AdapterContext) {

  const getLastLayoutId = (): number | null => {
    const stored = localStorage.getItem(STORAGE_LAST_LAYOUT_KEY);
    return stored ? parseInt(stored, 10) : null;
  };

  const saveLastLayoutId = (layoutId: number) => {
    localStorage.setItem(STORAGE_LAST_LAYOUT_KEY, String(layoutId));
  };

  return {
    // ========== Chart Layout Management ==========
    // Layouts are symbol-agnostic and contain indicators, chart settings

    getAllCharts: async () => {
      try {
        const url = getApiUrl('/api/layouts');
        const response = await apiFetch(url.toString(), withAuth());
        if (response.ok) {
          const layouts = await response.json();
          const chartList = layouts.map((layout: any) => ({
            id: layout.id,
            name: layout.name,
            timestamp: new Date(layout.updatedAt).getTime() / 1000,
          }));

          // Sort so that last opened layout appears first
          const lastLayoutId = getLastLayoutId();
          chartList.sort((a: any, b: any) => {
            if (a.id === lastLayoutId) return -1;
            if (b.id === lastLayoutId) return 1;
            return b.timestamp - a.timestamp;
          });

          return chartList;
        }
        return [];
      } catch (e) {
        console.error('Failed to get all charts:', e);
        return [];
      }
    },

    removeChart: async (chartId: string | number) => {
      try {
        const layoutId = typeof chartId === 'string' ? parseInt(chartId, 10) : chartId;
        const url = getApiUrl(`/api/layouts/${layoutId}`);
        await apiFetch(url.toString(), withAuth({ method: 'DELETE' }));
      } catch (e) {
        console.error('Failed to remove chart:', e);
        throw e;
      }
    },

    saveChart: async (chartData: any) => {
      try {
        const url = getApiUrl('/api/layouts');
        const response = await apiFetch(url.toString(), withAuth({
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            name: chartData.name || 'default',
            content: chartData.content || chartData,
          })
        }));

        if (response.ok) {
          const layout = await response.json();
          saveLastLayoutId(layout.id);
          return String(layout.id);
        }
        throw new Error('Failed to save chart');
      } catch (e) {
        console.error('Failed to save chart:', e);
        throw e;
      }
    },

    getChartContent: async (chartId: string | number) => {
      try {
        const layoutId = typeof chartId === 'string' ? parseInt(chartId, 10) : chartId;
        const url = getApiUrl(`/api/layouts/${layoutId}`);
        const response = await apiFetch(url.toString(), withAuth());

        if (response.ok) {
          const layout = await response.json();
          saveLastLayoutId(layout.id);

          // Parse the layoutContent JSON string
          const content = JSON.parse(layout.layoutContent || '{}');
          return content.content || content;
        }
        return '';
      } catch (e) {
        console.error('Failed to get chart content:', e);
        return '';
      }
    },

    // ========== Drawings Storage (Separate from Layouts) ==========
    // Drawings are stored per symbol + layoutId + timeframe combination

    saveLineToolsAndGroups: async (layoutId: string | number | undefined, chartId: string | number, state: any) => {
      try {
        const actualLayoutId = layoutId || chartId || getLastLayoutId();
        if (!actualLayoutId) {
          console.warn('No layoutId available for saving drawings');
          return;
        }

        const numericLayoutId = typeof actualLayoutId === 'string'
          ? parseInt(actualLayoutId, 10)
          : actualLayoutId;

        // Recursively convert all Maps to plain objects
        const serializableState = mapToObject(state);

        const ctx = getContext();
        const url = getApiUrl('/api/chart-state/drawings');
        await apiFetch(url.toString(), withAuth({
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            symbol: scopedSymbol(ctx),
            layoutId: numericLayoutId,
            timeframe: ctx.timeframe,
            drawings: serializableState,
          })
        }));
      } catch (e) {
        console.error('Failed to save line tools:', e);
        throw e;
      }
    },

    loadLineToolsAndGroups: async (layoutId: string | number | undefined, chartId: string | number) => {
      try {
        const actualLayoutId = layoutId || chartId || getLastLayoutId();
        if (!actualLayoutId) {
          return null;
        }

        const numericLayoutId = typeof actualLayoutId === 'string'
          ? parseInt(actualLayoutId, 10)
          : actualLayoutId;

        const ctx = getContext();

        // Try keys in order: per-TF composite, then legacy non-TF composite (for one-time
        // migration of drawings saved before TF was encoded into the symbol column).
        const candidateSymbols: string[] = [];
        candidateSymbols.push(scopedSymbol(ctx));
        if (ctx.timeframe) candidateSymbols.push(`${ctx.tabId}:${ctx.symbol}`);

        for (const symbolKey of candidateSymbols) {
          const url = getApiUrl('/api/chart-state/drawings');
          url.searchParams.set('symbol', symbolKey);
          url.searchParams.set('layoutId', String(numericLayoutId));
          if (ctx.timeframe) url.searchParams.set('timeframe', ctx.timeframe);

          const response = await apiFetch(url.toString(), withAuth());
          if (!response.ok) continue;
          const drawings = await response.json();
          const hasContent = drawings && (
            (drawings.sources && Object.keys(drawings.sources).length > 0) ||
            (drawings.groups && Object.keys(drawings.groups).length > 0)
          );
          if (!hasContent) continue;

          return {
            sources: drawings.sources ? new Map(Object.entries(drawings.sources)) : new Map(),
            groups: drawings.groups ? new Map(Object.entries(drawings.groups)) : new Map(),
          };
        }
        return null;
      } catch (e) {
        console.error('Failed to load line tools:', e);
        return null;
      }
    },

    // ========== Study Templates (Not implemented) ==========

    getAllStudyTemplates: async () => {
      return [];
    },

    removeStudyTemplate: async (studyTemplateInfo: any) => {
      // Not implemented
    },

    saveStudyTemplate: async (studyTemplateData: any) => {
      // Not implemented
    },

    getStudyTemplateContent: async (studyTemplateInfo: any) => {
      return '';
    },

    // ========== Drawing Templates (Not implemented) ==========

    getDrawingTemplates: async () => {
      return [];
    },

    loadDrawingTemplate: async (toolName: string, templateName: string) => {
      return '';
    },

    removeDrawingTemplate: async (toolName: string, templateName: string) => {
      // Not implemented
    },

    saveDrawingTemplate: async (toolName: string, templateName: string, content: string) => {
      // Not implemented
    },
  };
}
