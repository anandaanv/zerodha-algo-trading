// TradingView Save/Load Adapter implementation
import { apiFetch } from '../config/api';
import { withAuth } from '../utils/apiHelper';
import { intervalToPeriod, mapTimeframeToInterval } from './intervalUtils';

export function createSaveLoadAdapter(symbol: string, interval: string) {
  return {
    // Chart management methods
    getAllCharts: async () => {
      try {
        // Since we don't have a list endpoint, return the current chart if it exists
        const period = intervalToPeriod(interval);
        const response = await apiFetch(`/api/chart-state?symbol=${symbol}&period=${period}`, withAuth());
        if (response.ok) {
          const data = await response.json();
          if (data && data.meta) {
            return [{
              id: `${symbol}_${period}`,
              name: `${symbol} - ${period}`,
              symbol: symbol,
              resolution: interval,
              timestamp: Math.floor(Date.now() / 1000),
            }];
          }
        }
        return [];
      } catch (e) {
        console.error('Failed to get all charts:', e);
        return [];
      }
    },

    removeChart: async (chartId: string | number) => {
      try {
        await apiFetch(`/api/chart-state/${chartId}`, withAuth({
          method: 'DELETE',
        }));
      } catch (e) {
        console.error('Failed to remove chart:', e);
        throw e;
      }
    },

    saveChart: async (chartData: any) => {
      try {
        const chartId = chartData.id || `${chartData.symbol}_${intervalToPeriod(chartData.resolution)}`;
        const response = await apiFetch('/api/chart-state', withAuth({
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            symbol: chartData.symbol,
            period: intervalToPeriod(chartData.resolution),
            overlays: {},
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
        // Parse chartId to extract symbol and period
        const idStr = String(chartId);
        let targetSymbol = symbol;
        let targetPeriod = intervalToPeriod(interval);

        if (idStr.includes('_')) {
          const [sym, per] = idStr.split('_');
          targetSymbol = sym;
          targetPeriod = per;
        }

        const response = await apiFetch(
          `/api/chart-state?symbol=${targetSymbol}&period=${targetPeriod}`,
          withAuth()
        );
        if (response.ok) {
          const data = await response.json();
          return data.meta?.content || '';
        }
        return '';
      } catch (e) {
        console.error('Failed to get chart content:', e);
        return '';
      }
    },

    // Study template methods (returning empty for now)
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

    // Drawing template methods (returning empty for now)
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
