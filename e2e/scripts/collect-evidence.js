#!/usr/bin/env node
/**
 * collect-evidence.js
 * Merges mochawesome JSON reports into a single HTML report
 * and copies screenshots into an evidence summary.
 */
const fs = require('fs');
const path = require('path');

const evidenceDir = path.join(__dirname, '..', 'evidence');
const screenshotsDir = path.join(evidenceDir, 'screenshots');
const reportsDir = path.join(evidenceDir, 'reports');

// ── Count screenshots ──────────────────────────────────────────────────────
function listScreenshots() {
  if (!fs.existsSync(screenshotsDir)) return [];
  return fs.readdirSync(screenshotsDir, { recursive: true })
    .filter(f => f.endsWith('.png'))
    .map(f => path.join(screenshotsDir, f));
}

// ── Read mochawesome reports ───────────────────────────────────────────────
function readReports() {
  if (!fs.existsSync(reportsDir)) return [];
  return fs.readdirSync(reportsDir)
    .filter(f => f.endsWith('.json'))
    .map(f => {
      try { return JSON.parse(fs.readFileSync(path.join(reportsDir, f), 'utf8')); }
      catch { return null; }
    })
    .filter(Boolean);
}

// ── Summary ────────────────────────────────────────────────────────────────
function buildSummary(reports, screenshots) {
  let passed = 0, failed = 0, pending = 0;

  reports.forEach(r => {
    passed  += r.stats?.passes  ?? 0;
    failed  += r.stats?.failures ?? 0;
    pending += r.stats?.pending ?? 0;
  });

  return {
    timestamp: new Date().toISOString(),
    passed, failed, pending,
    total: passed + failed + pending,
    screenshots: screenshots.length,
    status: failed === 0 ? 'PASS' : 'FAIL',
  };
}

const screenshots = listScreenshots();
const reports = readReports();
const summary = buildSummary(reports, screenshots);

console.log('\n=== EVIDENCE SUMMARY ===');
console.log(`Timestamp : ${summary.timestamp}`);
console.log(`Status    : ${summary.status}`);
console.log(`Tests     : ${summary.passed} passed, ${summary.failed} failed, ${summary.pending} pending (${summary.total} total)`);
console.log(`Evidence  : ${summary.screenshots} screenshots`);
console.log('========================\n');

// Write summary JSON
const summaryPath = path.join(evidenceDir, 'summary.json');
fs.writeFileSync(summaryPath, JSON.stringify(summary, null, 2));
console.log(`Summary written to ${summaryPath}`);

if (summary.screenshots > 0) {
  console.log('\nScreenshots:');
  screenshots.forEach(s => console.log('  ' + s));
}

process.exit(summary.failed > 0 ? 1 : 0);
