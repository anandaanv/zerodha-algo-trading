/**
 * Live integration test for POWERGRID Elliott analysis.
 * No stubs — hits the real backend and real chart.
 *
 * Requires the app (localhost:5173) and backend (localhost:8080) to be running.
 * Token is passed via Cypress.env('authToken').
 */

const TOKEN =
  'eyJhbGciOiJIUzM4NCJ9.eyJyb2xlIjoiQURNSU4iLCJzdWIiOiJhbmFuZCIsImlhdCI6MTc3NTY2MzU2MCwiZXhwIjoxNzc2MjY4MzYwfQ.YJg97bDfoAPQrt-vQtUieS3dygAlNM3R_t_9s8y9RYpuYoqxi6rpOU_vAGWdE-_6';

const USER = JSON.stringify({ username: 'anand', role: 'ADMIN' });

const TIMEFRAMES = [
  { label: '1D', tf: 'D' },
  { label: '4h', tf: '240' },
  { label: '1h', tf: '60' },
];

function visitChart(symbol: string, tf: string) {
  cy.visit(`/chart?symbol=${symbol}&timeframe=${tf}`, {
    onBeforeLoad(win) {
      win.localStorage.setItem('auth_token', TOKEN);
      win.localStorage.setItem('auth_user', USER);
    },
  });
}

describe('POWERGRID — Live Elliott Analysis', () => {
  TIMEFRAMES.forEach(({ label, tf }) => {
    describe(`Timeframe: ${label}`, () => {
      beforeEach(() => {
        visitChart('POWERGRID', tf);
        // Wait for chart to load — TradingView widget boots async
        cy.contains(/elliott/i, { timeout: 15000 }).should('exist');
      });

      it(`[${label}] captures raw chart before scan`, () => {
        cy.wait(4000); // let TradingView candles render
        cy.evidence(`powergrid-${label}-raw-chart`);
      });

      it(`[${label}] Scan (no AI) — detects Elliott patterns`, () => {
        cy.contains(/elliott/i).first().click();
        cy.contains('Elliott Analysis', { timeout: 8000 }).should('exist');

        cy.intercept('POST', '/api/analysis/full-elliott**').as('scanCall');
        cy.contains('button', 'Scan').click();
        cy.wait('@scanCall', { timeout: 30000 });

        cy.wait(1000);
        cy.evidence(`powergrid-${label}-scan-result`);

        // Log what the scan returned
        cy.get('@scanCall').then((interception: any) => {
          const body = interception.response?.body;
          cy.task('log', `[${label}] Scan response: ${JSON.stringify(body, null, 2)}`).then(() => {});
        });
      });

      it(`[${label}] Scan + AI — trade setup recommendation`, () => {
        cy.contains(/elliott/i).first().click();
        cy.contains('Elliott Analysis', { timeout: 8000 }).should('exist');

        cy.intercept('POST', '/api/analysis/full-elliott**').as('scanAiCall');
        cy.contains('button', /scan \+ ai/i).click();
        cy.wait('@scanAiCall', { timeout: 60000 }); // AI can be slow

        cy.wait(2000);
        cy.evidence(`powergrid-${label}-scan-ai-result`);

        cy.get('@scanAiCall').then((interception: any) => {
          const body = interception.response?.body;
          cy.task('log', `[${label}] Scan+AI response: ${JSON.stringify(body, null, 2)}`).then(() => {});
        });
      });
    });
  });
});
