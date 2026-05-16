export type AnnotationIntent =
  | 'KEY_LEVEL'
  | 'RETEST_ENTRY'
  | 'BREAKOUT_CONFIRM'
  | 'REJECT_ON_TOUCH'
  | 'OVERTHROW_WATCH'
  | 'ABC_PROJECTION'
  | 'INVALIDATION'
  | 'TARGET';

export const INTENT_LABELS: Record<AnnotationIntent, string> = {
  KEY_LEVEL:        'Key level (importance flag)',
  RETEST_ENTRY:     'Retest entry',
  BREAKOUT_CONFIRM: 'Breakout confirmation',
  REJECT_ON_TOUCH:  'Reject on touch (fade)',
  OVERTHROW_WATCH:  'Overthrow watch',
  ABC_PROJECTION:   'ABC projection',
  INVALIDATION:     'Invalidation level',
  TARGET:           'Target',
};

export const INTENT_ORDER: AnnotationIntent[] = [
  'KEY_LEVEL', 'RETEST_ENTRY', 'BREAKOUT_CONFIRM', 'REJECT_ON_TOUCH',
  'OVERTHROW_WATCH', 'ABC_PROJECTION', 'INVALIDATION', 'TARGET',
];

export interface DrawingAnnotation {
  id: number;
  tabUuid: string;
  symbol: string;
  interval?: string;
  drawingId: string;
  intent: AnnotationIntent;
  intentParamsJson?: string;
  geometryJson?: string;
  note?: string;
  weight: number;
  active: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export interface JournalNote {
  id: number;
  symbol: string;
  noteDate: string;   // YYYY-MM-DD
  noteText: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface SaveAnnotationRequest {
  tabUuid: string;
  symbol: string;
  interval?: string;
  drawingId?: string;
  intent: AnnotationIntent;
  intentParamsJson?: string;
  geometryJson?: string;
  note?: string;
  weight?: number;
}

export interface SaveJournalNoteRequest {
  symbol: string;
  /** YYYY-MM-DD — server defaults to today if omitted */
  noteDate?: string;
  noteText: string;
}
