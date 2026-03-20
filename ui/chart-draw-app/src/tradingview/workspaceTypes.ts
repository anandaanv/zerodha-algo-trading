// Multi-tab workspace types

export interface WorkspaceTab {
  id: string;
  label: string;       // editable display name
  symbol: string;
  timeframe: string;   // ui key: "1h", "1d", etc.
  drawingsState?: string;  // JSON of serialized getLineToolsState() (Maps as plain objects)
  visibleFrom?: number;    // epoch seconds
  visibleTo?: number;
}

export function newTab(symbol: string, timeframe: string): WorkspaceTab {
  return {
    id: crypto.randomUUID(),
    label: symbol,
    symbol,
    timeframe,
  };
}

// ─── Layout persistence ────────────────────────────────────────────────────

export interface WorkspaceLayoutTab {
  label: string;
  symbol: string;
  timeframe: string;
}

export interface WorkspaceLayout {
  id: string;
  name: string;
  scope: 'ALL' | string; // 'ALL' = global template, or specific symbol e.g. 'RECLTD'
  tabs: WorkspaceLayoutTab[];
  createdAt: number;
}

const LAYOUTS_KEY = 'workspace_layouts_v1';
const LAST_LAYOUT_KEY = 'workspace_last_layout';

export function loadWorkspaceLayouts(): WorkspaceLayout[] {
  try {
    const stored = localStorage.getItem(LAYOUTS_KEY);
    if (stored) {
      const arr = JSON.parse(stored);
      if (Array.isArray(arr)) return arr;
    }
  } catch { /* ignore */ }
  return [];
}

export function saveWorkspaceLayouts(layouts: WorkspaceLayout[]) {
  try { localStorage.setItem(LAYOUTS_KEY, JSON.stringify(layouts)); } catch { /* ignore */ }
}

export function getLayoutsForSymbol(symbol: string): { symbolSpecific: WorkspaceLayout[]; global: WorkspaceLayout[] } {
  const all = loadWorkspaceLayouts();
  return {
    symbolSpecific: all.filter(l => l.scope === symbol),
    global: all.filter(l => l.scope === 'ALL'),
  };
}

export function getLastLayoutIdForSymbol(symbol: string): string | null {
  try {
    const stored = localStorage.getItem(LAST_LAYOUT_KEY);
    if (stored) {
      const map = JSON.parse(stored) as Record<string, string>;
      return map[symbol] ?? null;
    }
  } catch { /* ignore */ }
  return null;
}

export function setLastLayoutIdForSymbol(symbol: string, layoutId: string) {
  try {
    const stored = localStorage.getItem(LAST_LAYOUT_KEY);
    const map: Record<string, string> = stored ? JSON.parse(stored) : {};
    map[symbol] = layoutId;
    localStorage.setItem(LAST_LAYOUT_KEY, JSON.stringify(map));
  } catch { /* ignore */ }
}

const TABS_STORAGE_KEY = 'workspace_tabs_v2';
const ACTIVE_TAB_KEY = 'workspace_active_tab_v2';
const WORKSPACE_NAME_KEY = 'workspace_name';

export function loadWorkspaceName(fallback: string): string {
  return localStorage.getItem(WORKSPACE_NAME_KEY) || fallback;
}

export function saveWorkspaceName(name: string) {
  try { localStorage.setItem(WORKSPACE_NAME_KEY, name); } catch { /* ignore */ }
}

export function loadTabsFromStorage(defaultSymbol: string, defaultTimeframe: string): WorkspaceTab[] {
  try {
    const stored = localStorage.getItem(TABS_STORAGE_KEY);
    if (stored) {
      const tabs = JSON.parse(stored) as WorkspaceTab[];
      if (Array.isArray(tabs) && tabs.length > 0) return tabs;
    }
  } catch { /* ignore */ }
  return [newTab(defaultSymbol, defaultTimeframe)];
}

export function saveTabsToStorage(tabs: WorkspaceTab[]) {
  try {
    // Don't persist drawingsState in localStorage — it can be very large
    // Store only the metadata; drawings are restored from backend on initial load
    const slim = tabs.map(({ drawingsState: _, ...rest }) => rest);
    localStorage.setItem(TABS_STORAGE_KEY, JSON.stringify(slim));
  } catch { /* ignore */ }
}

export function loadActiveTabIdFromStorage(tabs: WorkspaceTab[]): string {
  try {
    const stored = localStorage.getItem(ACTIVE_TAB_KEY);
    if (stored && tabs.some(t => t.id === stored)) return stored;
  } catch { /* ignore */ }
  return tabs[0]?.id || '';
}

export function saveActiveTabToStorage(tabId: string) {
  try {
    localStorage.setItem(ACTIVE_TAB_KEY, tabId);
  } catch { /* ignore */ }
}
