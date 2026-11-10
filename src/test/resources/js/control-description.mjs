// The Control Description in the page header (static/js/control-description.js), on a made-up page: a long text is
// folded to two lines with "Show more", opens and folds again; a short one gets no button. Exit code 1 on a failed check.
// Usage: node control-description.mjs <control-description.js>
import { readFileSync } from 'node:fs';

/** A made-up element: classes, attributes, hidden, a click listener; the text "cut off" when folded and long. */
function element() {
    const classes = new Set();
    const attributes = {};
    const listeners = {};
    return {
        hidden: true,
        textContent: '',
        children: {},
        classList: {
            add: c => classes.add(c),
            remove: c => classes.delete(c),
            contains: c => classes.has(c),
            toggle: (c, on) => (on ? classes.add(c) : classes.delete(c)),
        },
        setAttribute: (k, v) => { attributes[k] = String(v); },
        getAttribute: k => attributes[k] ?? null,
        addEventListener: (type, fn) => { listeners[type] = fn; },
        click() { listeners.click && listeners.click(); },
        querySelector(selector) { return this.children[selector] || null; },
    };
}

/** A header with a text of the given number of lines, two of them seen when folded. */
function header(lines) {
    const root = element();
    const text = element();
    const button = element();
    button.textContent = 'Show more';
    button.setAttribute('aria-expanded', 'false');
    const lineHeight = 18;
    Object.defineProperty(text, 'clientHeight', {
        get: () => (root.classList.contains('is-collapsible') && !root.classList.contains('is-open')
            ? Math.min(lines, 2) : lines) * lineHeight,
    });
    Object.defineProperty(text, 'scrollHeight', { get: () => lines * lineHeight });
    root.children['.ctl-desc-text'] = text;
    root.children['.ctl-desc-toggle'] = button;
    return { root, text, button };
}

const long = header(10);
const short = header(2);
const missing = element(); // no button in it: left alone
const document = { querySelectorAll: () => [long.root, short.root, missing] };
const window = {};
new Function('window', 'document', readFileSync(process.argv[2], 'utf8'))(window, undefined);
const api = window.QTrackerControlDescription;

const failures = [];
function check(label, actual, expected) {
    if (actual !== expected) failures.push(`${label}: expected "${expected}", got "${actual}"`);
}

api.init(document);
// Long: two lines, the button shown
check('long folded', long.root.classList.contains('is-collapsible'), true);
check('long open', long.root.classList.contains('is-open'), false);
check('long button shown', long.button.hidden, false);
check('long button text', long.button.textContent, 'Show more');
// Short: nothing folded, no button
check('short folded', short.root.classList.contains('is-collapsible'), false);
check('short button hidden', short.button.hidden, true);
// Show more: the whole text, "Show less", aria-expanded
long.button.click();
check('opened', long.root.classList.contains('is-open'), true);
check('opened text', long.button.textContent, 'Show less');
check('opened aria', long.button.getAttribute('aria-expanded'), 'true');
check('opened button still there', long.button.hidden, false);
// A resize while open does not fold it again
api.measure(long.root);
check('open after resize', long.root.classList.contains('is-open'), true);
// Show less: folded again
long.button.click();
check('closed', long.root.classList.contains('is-open'), false);
check('closed folded', long.root.classList.contains('is-collapsible'), true);
check('closed text', long.button.textContent, 'Show more');
check('closed aria', long.button.getAttribute('aria-expanded'), 'false');
check('closed button shown', long.button.hidden, false);

console.log(JSON.stringify({ failures }));
process.exit(failures.length ? 1 : 0);
