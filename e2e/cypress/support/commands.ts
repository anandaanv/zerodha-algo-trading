/// <reference types="cypress" />

// Auth user fixture injected into localStorage
const TEST_USER = {
  id: 1,
  username: 'admin',
  role: 'ADMIN',
  screenerAccess: true,
};
const TEST_TOKEN = 'stub-token-for-testing';

/**
 * Visit a page with auth pre-loaded in localStorage.
 * This is the correct pattern: set localStorage BEFORE the app boots.
 */
Cypress.Commands.add('visitAuth', (path: string) => {
  cy.visit(path, {
    onBeforeLoad(win) {
      win.localStorage.setItem('auth_token', TEST_TOKEN);
      win.localStorage.setItem('auth_user', JSON.stringify(TEST_USER));
    },
  });
});

/**
 * Stub the /api/auth/me endpoint (called on google OAuth flow).
 * Must be called before cy.visitAuth().
 */
Cypress.Commands.add('stubAuth', () => {
  cy.intercept('GET', '/api/auth/me', {
    statusCode: 200,
    body: TEST_USER,
  }).as('authMe');
});

Cypress.Commands.add('evidence', (name: string) => {
  cy.screenshot(name, { capture: 'fullPage' });
});

declare global {
  namespace Cypress {
    interface Chainable {
      visitAuth(path: string): Chainable<void>;
      stubAuth(): Chainable<void>;
      evidence(name: string): Chainable<void>;
    }
  }
}
