type OverlayChange = { tool?: string; shapes?: number };

/**
 * Minimal event bus for overlay change notifications.
 * Consumers can subscribe via onOverlayChange(handler) and receive events when plugins emit changes.
 */
const listeners: Array<(ev: OverlayChange) => void> = [];

export function onOverlayChange(handler: (ev: OverlayChange) => void): () => void {
  listeners.push(handler);
  return () => {
    const idx = listeners.indexOf(handler);
    if (idx >= 0) listeners.splice(idx, 1);
  };
}

export function emitOverlayChange(ev: OverlayChange) {
  try {
    for (const h of listeners.slice()) {
      try {
        h(ev);
      } catch (e) {
        console.warn("emitOverlayChange handler failed", e);
      }
    }
  } catch (e) {
    // ignore
  }
}
