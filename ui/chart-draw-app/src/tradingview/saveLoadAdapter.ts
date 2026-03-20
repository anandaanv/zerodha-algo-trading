// TradingView Save/Load Adapter with symbol-agnostic layouts
// Layouts are now stored separately and drawings are tied to symbol + layoutId
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
}

// Maps a (tabId, symbol) pair to a scoped key for the drawings backend API.
// This isolates each tab's drawings from other tabs with the same symbol.
function scopedSymbol(ctx: AdapterContext): string {
  return `${ctx.tabId}:${ctx.symbol}`;
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
    // Drawings are stored per symbol + layoutId combination

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
        const url = getApiUrl('/api/chart-state/drawings');
        url.searchParams.set('symbol', scopedSymbol(ctx));
        url.searchParams.set('layoutId', String(numericLayoutId));

        const response = await apiFetch(url.toString(), withAuth());

        if (response.ok) {
          const drawings = await response.json();

          // Convert top-level plain objects back to Maps for TradingView
          const stateWithMaps = {
            sources: drawings.sources ? new Map(Object.entries(drawings.sources)) : new Map(),
            groups: drawings.groups ? new Map(Object.entries(drawings.groups)) : new Map(),
          };

          return stateWithMaps;
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
