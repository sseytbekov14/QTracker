// js/session.js as shipped, in a made-up page with a clock the test moves: the banner on a 401 of /api/**, the
// keepalive while typing (at most once in 5 minutes), nothing reloaded or cleared. Exit code 1 on a failed check.
// Usage: node session-keepalive.mjs <session.js>
import { readFileSync } from 'node:fs';

const source = readFileSync(process.argv[2], 'utf8');
const MINUTE = 60 * 1000;

let now = 1_700_000_000_000;
let reloaded = false;
const requests = [];
const statuses = new Map();
const listeners = {};

function element(tag) {
    return {
        tag, children: [], attributes: {}, listeners: {}, textContent: '', className: '', id: '', type: '',
        setAttribute(name, value) { this.attributes[name] = value; },
        appendChild(child) { this.children.push(child); },
        addEventListener(type, listener) { this.listeners[type] = listener; }
    };
}

const body = element('body');
const field = { value: 'A long comment the person is typing' };
const document = {
    body,
    createElement: element,
    addEventListener(type, listener) { listeners[type] = listener; }
};
const window = {
    location: {
        href: 'https://qtracker.test/view-control/7',
        origin: 'https://qtracker.test',
        reload() { reloaded = true; }
    },
    fetch(input) {
        const url = String(input);
        requests.push(url);
        return Promise.resolve({ status: statuses.get(url) || 200 });
    }
};
const clock = { now: () => now };
class FakeRequest {}

new Function('window', 'document', 'Date', 'Request', source)(window, document, clock, FakeRequest);

const failures = [];
function check(condition, what) {
    if (!condition) {
        failures.push(what);
    }
}
function type() {
    listeners.input({ target: field });
}
function keepalives() {
    return requests.filter(url => url === '/api/session/keepalive').length;
}
const settle = () => new Promise(resolve => setImmediate(resolve));

check(typeof listeners.input === 'function', 'listens to typing');

type();
check(keepalives() === 0, 'no keepalive right after the page loaded');

now += 4 * MINUTE + 59 * 1000;
type();
check(keepalives() === 0, 'no keepalive before 5 minutes');

now += 2 * 1000;
type();
type();
type();
check(keepalives() === 1, 'one keepalive after 5 minutes of typing, not one per key');
await settle();

now += 3 * MINUTE;
await window.fetch('/api/control-details?controlId=7');
now += 4 * MINUTE;
type();
check(keepalives() === 1, 'another /api call counts as contact');
now += 1 * MINUTE + 1000;
type();
check(keepalives() === 2, 'keepalive again 5 minutes after the last contact');
await settle();

statuses.set('/view-control/7', 401);
await window.fetch('/view-control/7');
check(body.children.length === 0, 'a 401 outside /api/** shows nothing');

statuses.set('/api/control-details', 401);
await window.fetch('/api/control-details', { method: 'POST' });
await window.fetch('/api/control-details', { method: 'POST' });
check(body.children.length === 1, 'one banner, also after a second 401');
const banner = body.children[0] || element('none');
check(banner.attributes.role === 'alert', 'the banner is announced');
const title = (banner.children[0] && banner.children[0].children[0]) || {};
check(title.textContent === 'Session expired, reload the page', 'the banner text');
check(!reloaded, 'nothing reloads by itself');
check(field.value === 'A long comment the person is typing', 'the typed text stays');

now += 10 * MINUTE;
type();
check(keepalives() === 2, 'no keepalive once the session is known to be over');

const button = banner.children[1] || element('none');
button.listeners.click && button.listeners.click();
check(reloaded, 'Reload reloads when the person asks');

console.log(JSON.stringify({ requests: requests.length, keepalives: keepalives(), failures }));
process.exit(failures.length === 0 ? 0 : 1);
