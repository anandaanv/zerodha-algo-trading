import { useState, useCallback } from 'react';
import type { CopilotHypothesis, CopilotAnomalyFlag, CopilotActiveTrade } from './copilotTypes';
import {
  triggerAnalysis, getHypothesisBoard, getTradeDashboard,
  confirmHypothesis, dismissHypothesis, acknowledgeFlag,
  openTrade, closeTrade,
} from './copilotApi';

export interface InvestigationState {
  investigationId: number | null;
  hypotheses: CopilotHypothesis[];
  flags: CopilotAnomalyFlag[];
  activeTrades: CopilotActiveTrade[];
  loading: boolean;
  error: string | null;
}

export function useInvestigation(layoutId: number, symbol: string) {
  const [state, setState] = useState<InvestigationState>({
    investigationId: null,
    hypotheses: [],
    flags: [],
    activeTrades: [],
    loading: false,
    error: null,
  });

  const setError = (error: string | null) => setState(s => ({ ...s, error }));
  const setLoading = (loading: boolean) => setState(s => ({ ...s, loading }));

  const trigger = useCallback(async (drawingsJson?: string, timeframes?: string[]) => {
    setLoading(true);
    setError(null);
    try {
      const res = await triggerAnalysis(layoutId, symbol, drawingsJson, timeframes);
      setState(s => ({
        ...s,
        investigationId: res.investigationId,
        hypotheses: res.hypotheses,
        flags: res.flags,
        loading: false,
      }));
      return res.investigationId;
    } catch (e) {
      setState(s => ({ ...s, loading: false, error: String(e) }));
      return null;
    }
  }, [layoutId, symbol]);

  const refresh = useCallback(async (investigationId: number) => {
    try {
      const [board, dashboard] = await Promise.all([
        getHypothesisBoard(investigationId),
        getTradeDashboard(investigationId),
      ]);
      setState(s => ({
        ...s,
        hypotheses: board.hypotheses,
        flags: board.unacknowledgedFlags,
        activeTrades: dashboard.activeTrades,
      }));
    } catch (e) {
      setError(String(e));
    }
  }, []);

  const confirm = useCallback(async (hypothesisId: number, entryType: string) => {
    if (!state.investigationId) return;
    await confirmHypothesis(hypothesisId, entryType, false);
    await refresh(state.investigationId);
  }, [state.investigationId, refresh]);

  const dismiss = useCallback(async (hypothesisId: number, reason?: string) => {
    if (!state.investigationId) return;
    await dismissHypothesis(hypothesisId, reason);
    await refresh(state.investigationId);
  }, [state.investigationId, refresh]);

  const ackFlag = useCallback(async (flagId: number, action: string, notes?: string) => {
    if (!state.investigationId) return;
    await acknowledgeFlag(flagId, action, notes);
    await refresh(state.investigationId);
  }, [state.investigationId, refresh]);

  const enterTrade = useCallback(async (tradeId: number, price: number) => {
    await openTrade(tradeId, price);
    if (state.investigationId) await refresh(state.investigationId);
  }, [state.investigationId, refresh]);

  const exitTrade = useCallback(async (tradeId: number, price: number, outcome: string, notes?: string) => {
    await closeTrade(tradeId, price, outcome, notes);
    if (state.investigationId) await refresh(state.investigationId);
  }, [state.investigationId, refresh]);

  return { state, trigger, refresh, confirm, dismiss, ackFlag, enterTrade, exitTrade };
}
