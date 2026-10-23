// Release test pass, stage E: the browser's own date code, as shipped, for the cases on stdin.
// Usage: TZ=<zone> node release-dates.mjs <view-control.js> <date-format.js>
// stdin: {"schedule":[{"date":"2026-01-31","frequency":"Monthly"}], "typed":["31.02.2026", ...]}
// stdout: {"schedule":[{"deadline":"2026-02-13","next":"2026-02-28"}], "typed":["2026-02-..." | null]}
import { readFileSync } from 'node:fs';

const viewControl = readFileSync(process.argv[2], 'utf8');
const dateFormat = readFileSync(process.argv[3], 'utf8');

function extract(name) {
    const start = viewControl.indexOf(`function ${name}(`);
    if (start < 0) throw new Error(`${name} not found in view-control.js`);
    let depth = 0;
    for (let i = viewControl.indexOf('{', start); i < viewControl.length; i++) {
        if (viewControl[i] === '{') depth++;
        if (viewControl[i] === '}' && --depth === 0) return viewControl.slice(start, i + 1);
    }
    throw new Error(`${name} has no closing brace`);
}

const preview = new Function(
    extract('plusMonths') + '\n'
    + extract('normalizeControlFrequency') + '\n'
    + extract('calculateDeadline') + '\n'
    + extract('calculateNextOperationDate') + '\n'
    + 'return { calculateDeadline, calculateNextOperationDate };')();

const window = {};
new Function('window', dateFormat)(window);
const QTrackerDate = window.QTrackerDate;

const iso = (date) => date ? QTrackerDate.toIsoDate(date) || null : null;
const input = JSON.parse(readFileSync(0, 'utf8'));
const schedule = (input.schedule || []).map(({ date, frequency }) => {
    const operationDate = QTrackerDate.parseIsoDate(date);
    return {
        deadline: iso(preview.calculateDeadline(operationDate, frequency)),
        next: iso(preview.calculateNextOperationDate(operationDate, frequency)),
    };
});
const typed = (input.typed || []).map((text) => iso(QTrackerDate.parseDisplayDate(text)));
process.stdout.write(JSON.stringify({ schedule, typed }));
