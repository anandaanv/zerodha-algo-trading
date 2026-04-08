import { defineConfig } from 'cypress';

export default defineConfig({
  e2e: {
    baseUrl: 'http://localhost:5173',
    viewportWidth: 1440,
    viewportHeight: 900,
    video: true,
    screenshotOnRunFailure: true,
    screenshotsFolder: 'evidence/screenshots',
    videosFolder: 'evidence/videos',
    defaultCommandTimeout: 10000,
    requestTimeout: 15000,
    responseTimeout: 30000,
    setupNodeEvents(on, config) {
      // Collect evidence on each test
      on('after:screenshot', (details) => {
        console.log('[Evidence]', details.path);
      });

      // Task for logging messages from test code
      on('task', {
        log(message: string) {
          console.log(message);
          return null;
        },
      });
    },
  },
  env: {
    // Override via cypress.env.json (gitignored) for real credentials
    apiBase: 'http://localhost:8080',
    testUser: 'admin',
    testPassword: 'admin',
  },
});
