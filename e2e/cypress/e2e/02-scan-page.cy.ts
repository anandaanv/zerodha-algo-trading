describe('Scan Page', () => {
  // ── Watching setup (TRIPLE_TOP) ─────────────────────────────────────────────────────
  describe('Watching setup (TRIPLE_TOP)', () => {
    beforeEach(() => {
      cy.stubAuth();
      cy.fixture('scan-watching').then((data) => {
        cy.intercept('GET', '/api/scan', { statusCode: 200, body: data }).as('getScans');
        cy.intercept('GET', '/api/scan*', { statusCode: 200, body: data }).as('getScansWild');
      });
      cy.visitAuth('/scan');
      cy.wait('@getScansWild', { timeout: 10000 });
    });

    it('renders the scan page', () => {
      cy.evidence('scan-page-loaded');
    });

    it('shows TRIPLE TOP pattern badge', () => {
      cy.contains('TRIPLE TOP').should('exist');
      cy.evidence('scan-triple-top-badge');
    });

    it('shows Anticipatory Setup card (WATCHING stage)', () => {
      cy.contains(/anticipatory setup/i).should('exist');
      cy.evidence('scan-watching-setup-card');
    });

    it('shows entry zone and stop loss', () => {
      cy.contains('455').should('exist');
      cy.contains('492').should('exist');
      cy.evidence('scan-watching-levels');
    });

    it('shows confirmation trigger', () => {
      cy.contains(/confirm when/i).should('exist');
      cy.evidence('scan-confirm-trigger');
    });

    it('does not show "No actionable trade setup" when watching setup present', () => {
      cy.contains(/no actionable trade setup/i).should('not.exist');
      cy.evidence('scan-no-empty-state');
    });

    it('shows Monitor button', () => {
      cy.contains(/monitor/i).should('exist');
      cy.evidence('scan-monitor-button');
    });

    it('Monitor button calls from-scan API and shows confirmed state', () => {
      cy.intercept('POST', '/api/elliott-suggestions/from-scan/101', {
        statusCode: 200,
        body: { id: 201, state: 'ANTICIPATORY', symbol: 'COALINDIA' },
      }).as('createFromScan');
      cy.contains(/monitor/i).first().click();
      cy.wait('@createFromScan');
      cy.contains(/monitoring/i).should('exist');
      cy.evidence('scan-monitor-button-success');
    });

    it('shows RSI divergence indicator', () => {
      cy.contains('RSI').should('exist');
      cy.evidence('scan-rsi-indicator');
    });
  });

  // ── Entry-ready setup (DOUBLE_BOTTOM) ──────────────────────────────────────────────
  describe('Entry-ready setup (DOUBLE_BOTTOM)', () => {
    beforeEach(() => {
      cy.stubAuth();
      cy.fixture('scan-entry-ready').then((data) => {
        cy.intercept('GET', '/api/scan', { statusCode: 200, body: data }).as('getScans');
        cy.intercept('GET', '/api/scan*', { statusCode: 200, body: data }).as('getScansWild');
      });
      cy.visitAuth('/scan');
      cy.wait('@getScansWild', { timeout: 10000 });
    });

    it('shows green Trade Setup card', () => {
      cy.contains(/trade setup identified/i).should('exist');
      cy.evidence('scan-entry-ready-card');
    });

    it('shows DOUBLE BOTTOM pattern badge', () => {
      cy.contains(/double bottom/i).should('exist');
      cy.evidence('scan-double-bottom-badge');
    });

    it('shows entry, SL and target levels', () => {
      cy.contains('1520').should('exist');
      cy.contains('1495').should('exist');
      cy.contains('1620').should('exist');
      cy.evidence('scan-entry-ready-levels');
    });

    it('shows Monitor button on entry-ready', () => {
      cy.contains(/monitor/i).should('exist');
      cy.evidence('scan-entry-ready-monitor');
    });
  });
});
