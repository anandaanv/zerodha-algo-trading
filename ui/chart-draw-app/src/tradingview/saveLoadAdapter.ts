// TradingView Save/Load Adapter with separate drawings storage
// Implements: https://www.tradingview.com/charting-library-docs/latest/saving_loading/saving_drawings_separately
import { apiFetch } from '../config/api';
import { withAuth } from '../utils/apiHelper';
import { intervalToPeriod, mapTimeframeToInterval } from './intervalUtils';

// Helper function to recursively convert Maps and Map-like objects to plain objects
function mapToObject(obj: any): any {
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
    // Get all property names including non-enumerable ones
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
    // Also get enumerable properties from prototype chain
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

// Helper function to recursively convert plain objects to Maps where needed
function objectToMap(obj: any): any {
  if (obj !== null && typeof obj === 'object' && !Array.isArray(obj)) {
    // Check if this looks like it should be a Map (has numeric or complex keys)
    const keys = Object.keys(obj);
    if (keys.length > 0) {
      // For now, just return as-is for nested objects
      const result: any = {};
      for (const key in obj) {
        if (obj.hasOwnProperty(key)) {
          result[key] = objectToMap(obj[key]);
        }
      }
      return result;
    }
  } else if (Array.isArray(obj)) {
    return obj.map(item => objectToMap(item));
  }
  return obj;
}

export function createSaveLoadAdapter(symbol: string, interval: string) {
  const period = intervalToPeriod(interval);
  const defaultLayoutName = 'default';
  const storageKey = `lastLayout_${symbol}_${period}`;

  // Helper to get last opened layout name
  const getLastLayoutName = (): string => {
    return localStorage.getItem(storageKey) || defaultLayoutName;
  };

  // Helper to save last opened layout name
  const saveLastLayoutName = (layoutName: string) => {
    localStorage.setItem(storageKey, layoutName);
  };

  return {
    // ========== Chart Layout Management ==========
    // Layouts contain indicators, chart settings, but NOT drawings

    getAllCharts: async () => {
      try {
        const response = await apiFetch(
          `/api/chart-state/layouts?symbol=${symbol}&period=${period}`,
          withAuth()
        );
        if (response.ok) {
          const layouts = await response.json();
          const chartList = layouts.map((layout: any) => ({
            id: layout.id,
            name: layout.name,
            symbol: layout.symbol,
            resolution: mapTimeframeToInterval(layout.resolution),
            timestamp: layout.timestamp,
          }));

          // Sort so that last opened layout appears first
          const lastLayoutName = getLastLayoutName();
          chartList.sort((a: any, b: any) => {
            const aIsLast = a.name === lastLayoutName ? 1 : 0;
            const bIsLast = b.name === lastLayoutName ? 1 : 0;
            return bIsLast - aIsLast; // Last layout goes first
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
        // chartId format: "symbol_period_layoutName"
        const parts = String(chartId).split('_');
        const layoutName = parts.length >= 3 ? parts.slice(2).join('_') : defaultLayoutName;

        await apiFetch(
          `/api/chart-state?symbol=${symbol}&period=${period}&layoutName=${encodeURIComponent(layoutName)}`,
          withAuth({ method: 'DELETE' })
        );
      } catch (e) {
        console.error('Failed to remove chart:', e);
        throw e;
      }
    },

    saveChart: async (chartData: any) => {
      try {
        const layoutName = chartData.name || defaultLayoutName;
        const chartId = `${symbol}_${period}_${layoutName}`;

        const response = await apiFetch('/api/chart-state', withAuth({
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            symbol: symbol,
            period: period,
            layoutName: layoutName,
            overlays: {}, // Drawings are saved separately
            meta: {
              id: chartId,
              name: chartData.name,
              symbol: chartData.symbol,
              resolution: chartData.resolution,
              content: chartData.content,
              timestamp: chartData.timestamp || Math.floor(Date.now() / 1000),
            }
          })
        }));

        if (response.ok) {
          // Save this as the last opened layout
          saveLastLayoutName(layoutName);
          return chartId;
        }
        throw new Error('Failed to save chart');
      } catch (e) {
        console.error('Failed to save chart:', e);
        throw e;
      }
    },

    getChartContent: async (chartId: string | number) => {
      try {
        // chartId format: "symbol_period_layoutName"
        const parts = String(chartId).split('_');
        const layoutName = parts.length >= 3 ? parts.slice(2).join('_') : defaultLayoutName;

        const response = await apiFetch(
          `/api/chart-state?symbol=${symbol}&period=${period}&layoutName=${encodeURIComponent(layoutName)}`,
          withAuth()
        );

        if (response.ok) {
          const data = await response.json();
          // Save this as the last opened layout
          saveLastLayoutName(layoutName);
          return data.meta?.content || '';
        }
        return '';
      } catch (e) {
        console.error('Failed to get chart content:', e);
        return '';
      }
    },

    // ========== Drawings Storage (Separate from Layouts) ==========
    // Drawings are stored per-symbol and can be reused across layouts

    saveLineToolsAndGroups: async (layoutId: string | number | undefined, chartId: string | number, state: any) => {
      try {
        // layoutId and chartId can be used to identify the specific chart
        // For now, we'll save to the default layout
        const layoutName = defaultLayoutName;

        // Recursively convert all Maps to plain objects for JSON serialization
        const serializableState = mapToObject(state);

        await apiFetch('/api/chart-state/drawings', withAuth({
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            symbol: symbol,
            period: period,
            layoutName: layoutName,
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
        const layoutName = defaultLayoutName;

        const response = await apiFetch(
          `/api/chart-state/drawings?symbol=${symbol}&period=${period}&layoutName=${encodeURIComponent(layoutName)}`,
          withAuth()
        );

        if (response.ok) {
          const drawings = await response.json();

          // Convert top-level plain objects back to Maps for TradingView
          // The sources and groups properties need to be Maps
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
