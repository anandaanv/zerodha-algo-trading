describe('Trade Monitor — from-scan API', () => {
  beforeEach(() => {
    cy.stubAuth();
  });

  it('POST /from-scan creates an ANTICIPATORY suggestion', () => {
    cy.intercept('POST', '/api/elliott-suggestions/from-scan/101', {
      statusCode: 200,
      body: {
        id: 201,
        symbol: 'COALINDIA',
        direction: 'SHORT',
        state: 'ANTICIPATORY',
        entryLow: 455.0,
        entryHigh: 460.0,
        stopLossPrice: 492.0,
        target1Price: 410.0,
      },
    }).as('createFromScan');

    cy.fixture('scan-watching').then((data) => {
      cy.intercept('GET', '/api/scan', { statusCode: 200, body: data }).as('getScans');
      cy.intercept('GET', '/api/scan*', { statusCode: 200, body: data }).as('getScansWild');
    });
    cy.visitAuth('/scan');
    cy.wait('@getScansWild', { timeout: 10000 });
    cy.contains(/monitor/i).first().click();
    cy.wait('@createFromScan');
    cy.contains(/monitoring/i).should('exist');
    cy.evidence('trade-monitor-created');
  });

  it('Monitor button is disabled after clicking (prevents double submit)', () => {
    cy.intercept('POST', '/api/elliott-suggestions/from-scan/101', {
      statusCode: 200,
      body: { id: 201, state: 'ANTICIPATORY', symbol: 'COALINDIA' },
    }).as('createFromScan');
    cy.fixture('scan-watching').then((data) => {
      cy.intercept('GET', '/api/scan', { statusCode: 200, body: data }).as('getScans');
      cy.intercept('GET', '/api/scan*', { statusCode: 200, body: data }).as('getScansWild');
    });
    cy.visitAuth('/scan');
    cy.wait('@getScansWild', { timeout: 10000 });
    cy.contains(/monitor/i).first().click();
    cy.wait('@createFromScan');
    // After success, button should show "✓ Monitoring" and be disabled
    cy.contains(/✓ monitoring/i).should('have.attr', 'disabled');
    cy.evidence('trade-monitor-button-disabled-after-success');
  });
});
