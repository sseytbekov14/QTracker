// Runs View Control's own deadline / next-date preview for the cases on stdin.
// Usage: node schedule-preview.mjs <path to view-control.js>   stdin: [{"date":"2026-01-31","frequency":"Monthly"}]
// stdout: [{"deadline":"2026-02-07","next":"2026-02-28"}] (null where the preview leaves the field alone)
// The functions are cut out of view-control.js as they are, so the test checks the shipped code.
import { readFileSync } from 'node:fs';

const source = readFileSync(process.argv[2], 'utf8');

function extract(name) {
    const start = source.indexOf(`function ${name}(`);
    if (start < 0) throw new Error(`${name} not found in view-control.js`);
    let depth = 0;
    for (let i = source.indexOf('{', start); i < source.length; i++) {
        if (source[i] === '{') depth++;
        if (source[i] === '}' && --depth === 0) return source.slice(start, i + 1);
    }
    throw new Error(`${name} has no closing brace`);
}

const preview = new Function(
    extract('plusMonths') + '\n'
    + extract('normalizeControlFrequency') + '\n'
    + extract('calculateDeadline') + '\n'
    + extract('calculateNextOperationDate') + '\n'
    + 'return { calculateDeadline, calculateNextOperationDate };')();

const iso = date => date
    ? `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
    : null;

const cases = JSON.parse(readFileSync(0, 'utf8'));
const results = cases.map(({ date, frequency }) => {
    const [y, m, d] = date.split('-').map(Number);
    const operationDate = new Date(y, m - 1, d);
    return {
        deadline: iso(preview.calculateDeadline(operationDate, frequency)),
        next: iso(preview.calculateNextOperationDate(operationDate, frequency))
    };
});
process.stdout.write(JSON.stringify(results));
