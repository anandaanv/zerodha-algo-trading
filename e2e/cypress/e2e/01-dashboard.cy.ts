describe('Dashboard', () => {
  beforeEach(() => {
    cy.stubAuth();
    cy.visitAuth('/dashboard');
  });

  it('renders the dashboard page', () => {
    cy.url().should('include', '/dashboard');
    cy.evidence('dashboard-loaded');
  });

  it('does NOT show Copilot navigation card', () => {
    cy.contains('Co-Pilot', { matchCase: false }).should('not.exist');
    cy.evidence('dashboard-no-copilot-card');
  });

  it('shows Charts navigation card', () => {
    cy.contains(/chart/i).should('exist');
    cy.evidence('dashboard-chart-card-present');
  });

  it('shows Elliott Scan navigation card', () => {
    cy.contains(/elliott scan/i).should('exist');
    cy.evidence('dashboard-scan-card-present');
  });
});
