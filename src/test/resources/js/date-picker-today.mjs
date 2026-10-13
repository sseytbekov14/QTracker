// js/date-format.js + js/date-picker.js as shipped, on a made-up Control Operation Date field, then View Control's own
// readOperationDate (cut out of view-control.js as it is): what Save sends after Today, a picked day, typed text and
// Clear. Exit code 1 on a failed check.
// Usage: node date-picker-today.mjs <date-format.js> <date-picker.js> <view-control.js>
import { readFileSync } from 'node:fs';

const [formatSource, pickerSource, viewSource] = process.argv.slice(2).map(file => readFileSync(file, 'utf8'));

function classList() {
    const names = new Set();
    return {
        add: name => names.add(name),
        remove: name => names.delete(name),
        contains: name => names.has(name),
        toggle: (name, on) => (on ? names.add(name) : names.delete(name))
    };
}

function element(tag) {
    const listeners = {};
    return {
        tag, children: [], attributes: {}, dataset: {}, style: {}, classList: classList(), listeners,
        hidden: false, innerHTML: '', className: '', value: '', readOnly: false, disabled: false,
        offsetLeft: 0, offsetTop: 0, offsetHeight: 30, parentElement: null,
        setAttribute(name, value) { this.attributes[name] = value; },
        appendChild(child) { this.children.push(child); child.parentElement = this; },
        contains(other) { return other === this || this.children.includes(other); },
        addEventListener(type, listener) { (listeners[type] = listeners[type] || []).push(listener); },
        dispatchEvent(event) { (listeners[event.type] || []).forEach(listener => listener.call(this, event)); return true; },
        querySelector() { return null; },
        focus() {}
    };
}

const documentListeners = {};
const document = {
    activeElement: null,
    createElement: element,
    addEventListener(type, listener) { (documentListeners[type] = documentListeners[type] || []).push(listener); },
    querySelectorAll() { return []; }
};
const window = {};
new Function('window', 'document', formatSource)(window, document);
new Function('window', 'document', 'Event', pickerSource)(window, document, Event);

function extract(name) {
    const start = viewSource.indexOf(`function ${name}(`);
    if (start < 0) throw new Error(`${name} not found in view-control.js`);
    let depth = 0;
    for (let i = viewSource.indexOf('{', start); i < viewSource.length; i++) {
        if (viewSource[i] === '{') depth++;
        if (viewSource[i] === '}' && --depth === 0) return viewSource.slice(start, i + 1);
    }
    throw new Error(`${name} has no closing brace`);
}
const view = new Function('window', extract('toIsoDate') + '\n' + extract('readOperationDate') + '\n'
    + 'return { readOperationDate };')(window);

const failures = [];
function check(condition, what, actual) {
    if (!condition) failures.push(what + (actual === undefined ? '' : ' (got ' + JSON.stringify(actual) + ')'));
}

function field() {
    const host = element('div');
    const input = element('input');
    host.appendChild(input);
    window.QTrackerDatePicker.attach(input);
    const popup = host.children[1];
    const changes = [];
    input.addEventListener('change', () => changes.push(input.value));
    return { input, popup, changes };
}

// A click inside the opened calendar on a button with these data attributes
function clickButton(popup, dataset) {
    const button = { dataset };
    const target = { closest: selector => (selector === 'button' ? button : null) };
    popup.listeners.click.forEach(listener => listener({ target }));
}

function open(input) {
    input.listeners.click.forEach(listener => listener({}));
}

const pad = n => String(n).padStart(2, '0');
const now = new Date();
const todayShown = `${pad(now.getDate())}.${pad(now.getMonth() + 1)}.${now.getFullYear()}`;
const todayIso = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;

// Today on an empty field (a draft without a date)
{
    const { input, popup, changes } = field();
    open(input);
    check(popup.hidden === false, 'the calendar opens on an empty editable field');
    check(popup.innerHTML.includes('data-action="today"'), 'the calendar has a Today button');
    clickButton(popup, { action: 'today' });
    check(input.value === todayShown, 'Today shows the date as dd.mm.yyyy', input.value);
    check(input.dataset.isoValue === todayIso, 'Today keeps the ISO date', input.dataset.isoValue);
    check(popup.hidden === true, 'the calendar closes after Today');
    check(changes.length === 1, 'Today fires one change (the page recalculates the deadline)', changes);
    check(view.readOperationDate(input) === todayIso, 'Save reads Today as the ISO date', view.readOperationDate(input));
}

// A picked day replaces a stored date
{
    const { input, popup } = field();
    input.value = '12.09.2026';
    input.dataset.isoValue = '2026-09-12';
    open(input);
    check(popup.innerHTML.includes('September 2026'), 'the calendar opens on the month of the date in the field');
    clickButton(popup, { day: '20' });
    check(input.value === '20.09.2026', 'a picked day is shown as dd.mm.yyyy', input.value);
    check(input.dataset.isoValue === '2026-09-20', 'a picked day keeps the ISO date', input.dataset.isoValue);
    check(view.readOperationDate(input) === '2026-09-20', 'Save reads the picked day', view.readOperationDate(input));
}

// Typed text: a real date, a short one, text that is no date
{
    const { input } = field();
    for (const [typed, expected] of [['24.10.2026', '2026-10-24'], ['1.2.2026', '2026-02-01'], ['', ''],
        ['31.02.2026', null], ['2026-10-24', null], ['24/10/2026', null], ['24.10.26', null]]) {
        input.value = typed;
        input.listeners.input.forEach(listener => listener({ isTrusted: true }));
        check(view.readOperationDate(input) === expected, `Save reads "${typed}" as ${expected}`, view.readOperationDate(input));
    }
}

// Clear empties both the text and the hidden ISO value
{
    const { input, popup } = field();
    input.value = '12.09.2026';
    input.dataset.isoValue = '2026-09-12';
    open(input);
    clickButton(popup, { action: 'clear' });
    check(input.value === '' && input.dataset.isoValue === undefined, 'Clear empties the field', input.value);
    check(view.readOperationDate(input) === '', 'Save reads a cleared field as no date', view.readOperationDate(input));
}

// A read-only field (outside edit mode) never opens the calendar
{
    const { input, popup } = field();
    input.readOnly = true;
    popup.hidden = true;
    open(input);
    check(popup.hidden === true, 'a read-only field does not open the calendar');
}

console.log(JSON.stringify({ failures }));
process.exit(failures.length ? 1 : 0);
