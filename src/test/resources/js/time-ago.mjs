// The E-mails page's relative time (static/js/time-ago.js). Exit code 1 on a failed check.
// Usage: node time-ago.mjs <time-ago.js>
import { readFileSync } from 'node:fs';

const window = {};
new Function('window', 'document', readFileSync(process.argv[2], 'utf8'))(window, undefined);
const { format } = window.QTrackerTimeAgo;

const failures = [];
function check(actual, expected) {
    if (actual !== expected) failures.push(`expected "${expected}", got "${actual}"`);
}
const now = '2026-11-10T14:05:30';
check(format('2026-11-10T14:05:10', now), 'just now');
check(format('2026-11-10T14:04', now), '1 min ago');
check(format('2026-11-10T13:20', now), '45 min ago');
check(format('2026-11-10T13:05', now), '1 hour ago');
check(format('2026-11-10T09:00', now), '5 hours ago');
check(format('2026-11-09T23:59', now), '14 hours ago');
check(format('2026-11-09T10:00', now), 'yesterday');
check(format('2026-11-07T10:00', now), '3 days ago');
check(format('2026-11-03T10:00', now), '3 Nov 2026');
check(format('2025-12-31T10:00', now), '31 Dec 2025');
check(format('', now), '');
check(format('nonsense', now), '');

console.log(JSON.stringify({ failures }));
process.exit(failures.length ? 1 : 0);
