const TOKEN = 'eyJhbGciOiJIUzM4NCJ9.eyJyb2xlIjoiQURNSU4iLCJzdWIiOiJhbmFuZCIsImlhdCI6MTc3NTY2MzU2MCwiZXhwIjoxNzc2MjY4MzYwfQ.YJg97bDfoAPQrt-vQtUieS3dygAlNM3R_t_9s8y9RYpuYoqxi6rpOU_vAGWdE-_6';
const USER = JSON.stringify({ username: 'anand', role: 'ADMIN' });

const TIMEFRAMES = [
  { label: '1D', tf: '1D' },
  { label: '4h', tf: '240' },
  { label: '1h', tf: '1h' },
];

describe('COALINDIA — Visual Chart Verification', () => {
  TIMEFRAMES.forEach(({ label, tf }) => {
    it(`[${label}] raw chart screenshot`, () => {
      cy.visit(`/chart?symbol=COALINDIA&timeframe=${tf}`, {
        onBeforeLoad(win) {
          win.localStorage.setItem('auth_token', TOKEN);
          win.localStorage.setItem('auth_user', USER);
        },
      });
      cy.contains(/elliott/i, { timeout: 15000 }).should('exist');
      cy.wait(5000); // let TradingView candles render
      cy.evidence(`coalindia-${label}-raw`);
    });

    it(`[${label}] after scan — patterns panel`, () => {
      cy.visit(`/chart?symbol=COALINDIA&timeframe=${tf}`, {
        onBeforeLoad(win) {
          win.localStorage.setItem('auth_token', TOKEN);
          win.localStorage.setItem('auth_user', USER);
        },
      });
      cy.contains(/elliott/i, { timeout: 15000 }).should('exist');
      cy.wait(4000);

      // Open Elliott panel
      cy.contains(/elliott/i).first().click();
      cy.contains('Elliott Analysis', { timeout: 8000 }).should('exist');

      // Intercept scan call
      cy.intercept('POST', '/api/analysis/full-elliott**').as('scan');
      cy.contains('button', 'Scan').click();
      cy.wait('@scan', { timeout: 30000 });
      cy.wait(2000);

      cy.evidence(`coalindia-${label}-scan`);

      // Log scan response for analysis
      cy.get('@scan').then((interception: any) => {
        const body = interception.response?.body;
        const wa = body?.waveAnalysis || {};
        const scenarios = wa.scenarios || [];
        const patterns = (wa.allPatterns || []).filter((p: any) => p.status !== 'INVALIDATED');
        cy.task('log', `[COALINDIA ${label}] price=${body?.waveAnalysis?.scenarios?.[0]?.hypotheses?.[0]?.waveCount?.pivots?.slice(-1)?.[0]?.price} scenarios=${scenarios.length} patterns=${patterns.length}`);
      });
    });
  });
});
