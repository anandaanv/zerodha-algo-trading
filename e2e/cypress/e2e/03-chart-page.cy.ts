describe('Chart Page — Elliott Panel', () => {
  beforeEach(() => {
    cy.stubAuth();
    cy.intercept('GET', '/api/watchlists', { statusCode: 200, body: [] }).as('getWatchlists');
    cy.visitAuth('/chart');
  });

  it('loads the chart page (not redirected to login)', () => {
    cy.url().should('include', '/chart');
    cy.evidence('chart-page-loaded');
  });

  it('shows Elliott toggle button in tab bar', () => {
    cy.contains(/elliott/i).should('exist');
    cy.evidence('chart-elliott-toggle');
  });

  describe('Elliott panel after opening', () => {
    beforeEach(() => {
      cy.contains(/elliott/i).first().click();
      // Panel animates open — wait for it
      cy.contains('Elliott Analysis', { timeout: 5000 }).should('exist');
    });

    it('shows exactly Scan and Scan + AI buttons', () => {
      cy.contains('button', 'Scan').should('exist');
      cy.contains('button', /scan \+ ai/i).should('exist');
      cy.evidence('chart-two-buttons');
    });

    it('has no Full button', () => {
      cy.contains('button', /^full$/i).should('not.exist');
      cy.evidence('chart-no-full-button');
    });

    it('has no old Identification or AI Call labels', () => {
      cy.contains(/identification/i).should('not.exist');
      cy.contains(/ai call/i).should('not.exist');
      cy.evidence('chart-old-labels-gone');
    });

    it('Scan button calls full-elliott without aiRecommend', () => {
      cy.intercept('POST', '/api/analysis/full-elliott**', (req) => {
        expect(req.url).not.to.include('aiRecommend=true');
        req.reply({ statusCode: 200, body: { waveAnalysis: { waveCounts: [], scenarios: [], allPatterns: [] } } });
      }).as('scanCall');
      cy.contains('button', 'Scan').click();
      cy.wait('@scanCall');
      cy.evidence('chart-scan-called');
    });

    it('Scan + AI button calls full-elliott with aiRecommend=true', () => {
      cy.intercept('POST', '/api/analysis/full-elliott**', (req) => {
        expect(req.url).to.include('aiRecommend=true');
        req.reply({ statusCode: 200, body: { waveAnalysis: { waveCounts: [], scenarios: [], allPatterns: [] } } });
      }).as('scanAiCall');
      cy.contains('button', /scan \+ ai/i).click();
      cy.wait('@scanAiCall');
      cy.evidence('chart-scan-ai-called');
    });

    it('shows detected patterns in panel after scan returns patterns', () => {
      cy.intercept('POST', '/api/analysis/full-elliott**', {
        statusCode: 200,
        body: {
          waveAnalysis: {
            waveCounts: [],
            scenarios: [],
            allPatterns: [{
              type: 'TRIPLE_TOP', status: 'WATCHING', timeframe: '4h',
              resistance: 483.5, neckline: 455.0, target: 426.5,
              confidence: 78, rsiDivergence: true,
            }],
          },
        },
      }).as('scanWithPatterns');
      cy.contains('button', 'Scan').click();
      cy.wait('@scanWithPatterns');
      cy.contains(/triple top/i).should('exist');
      cy.contains(/watching/i).should('exist');
      cy.evidence('chart-patterns-in-panel');
    });
  });
});
