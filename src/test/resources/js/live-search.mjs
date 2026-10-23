// js/component-controls.js as shipped, in a made-up list page with a clock and answers the test controls: the
// search applies 300 ms after the last key (one request, not one per key), the KDN box at once, a new search starts
// on page 1, an older answer never replaces a newer one, the search field is never replaced, the URL follows.
// Exit code 1 on a failed check.
// Usage: node live-search.mjs <component-controls.js>
import { readFileSync } from 'node:fs';

const source = readFileSync(process.argv[2], 'utf8');

// Clock
let now = 0;
let timers = [];
let nextTimer = 1;
function setTimeout(callback, delay) {
    const id = nextTimer++;
    timers.push({ id, at: now + delay, callback });
    return id;
}
function clearTimeout(id) {
    timers = timers.filter(timer => timer.id !== id);
}
function advance(ms) {
    now += ms;
    const due = timers.filter(timer => timer.at <= now);
    timers = timers.filter(timer => timer.at > now);
    due.forEach(timer => timer.callback());
}

// Page
function element(id, extra) {
    return Object.assign({
        id, listeners: {}, attributes: {},
        addEventListener(type, listener) { this.listeners[type] = listener; },
        setAttribute(name, value) { this.attributes[name] = value; },
        removeAttribute(name) { delete this.attributes[name]; },
        getAttribute(name) { return this.attributes[name]; }
    }, extra);
}
const search = element('ccSearch', { value: '' });
const kdn = element('ccKdn', { checked: false });
const status = element('ccStatus', { value: '' });
let submitted = 0;
const form = element('ccFilters', { submit() { submitted++; } });
form.attributes.action = '/component/ALL';
// The hidden fields of a list sorted by Operation Date, 50 rows a page (no page field: the form never sends it)
const hidden = [['sort', 'operationDate'], ['dir', 'desc'], ['size', '50']];
class FormData {
    constructor(of) {
        this.entries = of === form ? [['q', search.value], ['status', status.value], ...hidden]
            .concat(kdn.checked ? [['kdn', '1']] : []) : [];
    }
    forEach(callback) { this.entries.forEach(([name, value]) => callback(value, name)); }
}

// The replaceable parts: what is shown now, by selector
const parts = ['.page-header', '.stats-bar', '#ccSortPhoneField', '#ccActions', '#ccResults'];
const shown = {};
function part(selector, html) {
    const node = element(selector.replace(/^[#.]/, ''), { selector, html });
    node.replaceWith = fresh => { shown[selector] = fresh; };
    return node;
}
parts.forEach(selector => { shown[selector] = part(selector, 'first page'); });
const replaced = [];
const documentListeners = {};
const document = {
    getElementById(id) {
        return { ccFilters: form, ccSearch: search, ccKdn: kdn, ccStatus: status }[id] || shown['#' + id] || null;
    },
    querySelector(selector) { return shown[selector] || null; },
    importNode(node) { replaced.push(node.selector); return node; },
    addEventListener(type, listener) { documentListeners[type] = listener; }
};
// An answer is the page of its URL; "login" is a page without the list
class DOMParser {
    parseFromString(html) {
        const list = html !== 'login';
        return {
            getElementById(id) { return list && (id === 'ccResults' || id === 'ccFilters') ? {} : null; },
            querySelector(selector) { return list && parts.includes(selector) ? part(selector, html) : null; }
        };
    }
}

const requests = [];
const navigations = [];
const urls = [];
const window = {
    location: {
        set href(url) { navigations.push(url); },
        get href() { return 'https://qtracker.test/component/ALL?sort=operationDate&dir=desc&size=50&page=3'; }
    },
    history: { replaceState(state, title, url) { urls.push(url); } },
    fetch(url, options) {
        const request = { url, options };
        request.answer = new Promise((resolve, reject) => {
            request.resolve = body => resolve({ ok: true, status: 200, text: () => Promise.resolve(body ?? url) });
            options.signal.addEventListener('abort', () => {
                const error = new Error('aborted');
                error.name = 'AbortError';
                reject(error);
            });
        });
        requests.push(request);
        return request.answer;
    }
};

new Function('window', 'document', 'setTimeout', 'clearTimeout', 'FormData', 'DOMParser', source)(
    window, document, setTimeout, clearTimeout, FormData, DOMParser);

const failures = [];
function check(condition, what) {
    if (!condition) {
        failures.push(what);
    }
}
const settle = () => new Promise(resolve => setImmediate(resolve));
function type(text) {
    search.value = text;
    search.listeners.input({ target: search });
}
const BASE = '/component/ALL?sort=operationDate&dir=desc&size=50';

// Typing: one request 300 ms after the last key, with the sorting and the page size, on page 1
type('H');
advance(100);
type('HR');
advance(100);
type('HR-');
advance(299);
check(requests.length === 0, 'no request while typing, nor before 300 ms after the last key');
advance(1);
check(requests.length === 1, 'one request 300 ms after the last key, not one per key');
check(requests[0]?.url === '/component/ALL?q=HR-&sort=operationDate&dir=desc&size=50',
    'the request has the search, the sorting and the page size, and no page: ' + requests[0]?.url);
check(shown['#ccResults'].attributes['aria-busy'] === 'true', 'the rows are marked as loading');
requests[0].resolve();
await settle();
check(shown['#ccResults'].html === requests[0].url, 'the rows of the search are shown');
check(parts.every(selector => shown[selector].html === requests[0].url),
    'the counters, the Export link, the phone sorting and Clear follow the search');
check(!replaced.includes('#ccSearch') && !replaced.includes('#ccFilters'), 'the search field and the form stay');
check(search.value === 'HR-', 'the search field keeps what is typed');
check(urls.at(-1) === requests[0].url, 'the URL follows the search');
check(navigations.length === 0, 'the page is not left');

// Typing back to what is shown: nothing to load
type('HR');
type('HR-');
advance(300);
check(requests.length === 1, 'no request for the search already shown');

// The KDN box: at once, and it cancels a search still waiting
type('HR-1');
kdn.checked = true;
kdn.listeners.change({ target: kdn });
check(requests.length === 2, 'the KDN box applies at once');
check(requests[1]?.url === '/component/ALL?q=HR-1&sort=operationDate&dir=desc&size=50&kdn=1',
    'with the search typed so far and kdn=1: ' + requests[1]?.url);
advance(300);
check(requests.length === 2, 'the waiting search does not send a second request');
requests[1].resolve();
await settle();
check(urls.at(-1) === requests[1].url, 'the URL keeps the KDN box');

// An older answer never replaces a newer one
type('a');
advance(300);
type('ab');
advance(300);
check(requests.length === 4, 'two searches, two requests');
check(requests[2].options.signal.aborted, 'the older request is cancelled');
requests[3].resolve();
await settle();
requests[2].resolve();
await settle();
check(shown['#ccResults'].html === requests[3].url, 'the newer search stays shown: ' + shown['#ccResults'].html);
check(urls.at(-1) === requests[3].url, 'the URL is of the newer search');

// Enter in the search: at once, not a new page
type('abc');
let prevented = false;
form.listeners.submit({ preventDefault() { prevented = true; } });
check(prevented, 'Enter does not send the form as a new page');
check(requests.length === 5 && requests[4].url.includes('q=abc'), 'Enter searches at once');

// An answer that is not the list (signed out): the page goes where the form would have gone
requests[4].resolve('login');
await settle();
check(navigations.at(-1) === requests[4].url, 'not the list: the page goes to the URL of the search');

// The status filter is a new page, as before
status.listeners.change({ target: status });
check(submitted === 1, 'the status filter sends the form');
check(['click', 'keydown', 'change'].every(type => typeof documentListeners[type] === 'function'),
    'the row click and the phone sorting work on rows that came later');

console.log(JSON.stringify({ failures }));
process.exit(failures.length ? 1 : 0);
