// Deactivate control's dialog (static/js/deactivate-control.js): its Deactivate button follows the same rule as
// AccessPolicy.deactivationIdMatches - the Control ID in any case, spaces at the ends ignored. Exit code 1 on a failure.
// Usage: node deactivate-control.mjs <deactivate-control.js>
import { readFileSync } from 'node:fs';

const source = readFileSync(process.argv[2], 'utf8');
const window = {};
const document = { addEventListener() {}, querySelectorAll() { return []; } };
new Function('window', 'document', source)(window, document);
const { matches } = window.qtrackerDeactivateControl;

const failures = [];
function check(condition, what) {
    if (!condition) failures.push(what);
}
const cases = [
    ['HR-FY27/1', 'HR-FY27/1', true], ['HR-FY27/1', 'hr-fy27/1', true], ['HR-FY27/1', '   Hr-Fy27/1  ', true],
    ['  KDN-5 ', 'kdn-5', true], ['HR-FY27/1', 'HR-FY27/1-X', false], ['HR-FY27/1', 'HR-FY27', false],
    ['HR-FY27/1', 'HR FY27/1', false], ['HR-FY27/1', '', false], ['HR-FY27/1', '   ', false],
    ['HR-FY27/1', null, false], ['HR-FY27/1', undefined, false], ['', '', false], [null, '', false]
];
for (const [controlId, typed, expected] of cases) {
    check(matches(controlId, typed) === expected, `"${typed}" for "${controlId}" -> ${expected}`);
}
// The dialog builds its text with textContent only
check(!/innerHTML|insertAdjacentHTML|outerHTML/.test(source), 'no HTML is inserted from strings');

console.log(JSON.stringify({ failures }));
process.exit(failures.length ? 1 : 0);
