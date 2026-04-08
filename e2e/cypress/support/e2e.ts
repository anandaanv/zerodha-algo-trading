import './commands';

// Suppress external/iframe errors
Cypress.on('uncaught:exception', (err) => {
  // Suppress known external/widget errors so tests don't abort on TradingView issues
  const suppressed = [
    'TradingView',
    'ResizeObserver',
    'Non-Error',
    'ChunkLoadError',
    'is not defined',         // TradingView global missing in test
    'Cannot read properties',  // chart widget access before ready
    'charting_library',
  ];
  if (suppressed.some(s => err.message.includes(s))) {
    return false;
  }
});
