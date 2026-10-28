// The Deadline Calendar's "+N more" in Node: js/deadline-day-popover.js as shipped, with the dashboard's own
// deadlineDayPopoverContent (cut out of dashboard.html as it is) turning FullCalendar's day into the popover's
// content, in a made-up page. A day of N controls shows all N as links with the calendar's colours; the popover is
// placed in the viewport for every place of the day in the grid; Esc, a click outside, a second click, the link
// gone or out of view close it; one at a time; aria-expanded, focus in and back. Exit code 1 on a failed check.
// Usage: node deadline-day-popover.mjs <deadline-day-popover.js> <dashboard.html>
import { readFileSync } from 'node:fs';

const popoverSource = readFileSync(process.argv[2], 'utf8');
const dashboardSource = readFileSync(process.argv[3], 'utf8');

function extract(name) {
    const start = dashboardSource.indexOf(`function ${name}(`);
    if (start < 0) {
        throw new Error(`no function ${name} in dashboard.html`);
    }
    let depth = 0;
    for (let i = dashboardSource.indexOf('{', start); i < dashboardSource.length; i++) {
        if (dashboardSource[i] === '{') depth++;
        if (dashboardSource[i] === '}' && --depth === 0) return dashboardSource.slice(start, i + 1);
    }
    throw new Error(`function ${name} does not end`);
}

// Page: elements with what the popover uses; the size of the popover follows its rows
let document;
class Element {
    constructor(tag) {
        this.tagName = tag.toUpperCase();
        this.children = [];
        this.parentNode = null;
        this.attributes = {};
        this.style = {};
        this.listeners = {};
        this.textContent = '';
        this.id = '';
        this.className = '';
        this.rect = null;
    }
    appendChild(child) { child.parentNode = this; this.children.push(child); return child; }
    remove() {
        if (this.parentNode) {
            this.parentNode.children = this.parentNode.children.filter(child => child !== this);
            this.parentNode = null;
        }
    }
    setAttribute(name, value) { this.attributes[name] = String(value); }
    getAttribute(name) { return name in this.attributes ? this.attributes[name] : null; }
    removeAttribute(name) { delete this.attributes[name]; }
    addEventListener(type, listener) { (this.listeners[type] = this.listeners[type] || []).push(listener); }
    removeEventListener(type, listener) {
        this.listeners[type] = (this.listeners[type] || []).filter(known => known !== listener);
    }
    contains(node) {
        for (let at = node; at; at = at.parentNode) if (at === this) return true;
        return false;
    }
    focus() { document.activeElement = this; }
    get isConnected() {
        let at = this;
        while (at.parentNode) at = at.parentNode;
        return at === document.documentElement;
    }
    getBoundingClientRect() { return this.rect; }
    all() { return this.children.flatMap(child => [child, ...child.all()]); }
    byClass(name) { return this.all().filter(node => node.className.split(' ').includes(name)); }
    get offsetWidth() { return 240; }
    get offsetHeight() { return 34 + 22 * this.byClass('deadline-day-popover-event').length; }
}
function listenerTarget() {
    return {
        listeners: {},
        addEventListener(type, listener) { (this.listeners[type] = this.listeners[type] || []).push(listener); },
        removeEventListener(type, listener) {
            this.listeners[type] = (this.listeners[type] || []).filter(known => known !== listener);
        },
        fire(type, event) { (this.listeners[type] || []).slice().forEach(listener => listener(event)); }
    };
}
const html = new Element('html');
document = Object.assign(listenerTarget(), {
    documentElement: html,
    body: html.appendChild(new Element('body')),
    activeElement: null,
    createElement: tag => new Element(tag)
});
const window = listenerTarget();
html.clientWidth = 1440;
html.clientHeight = 900;
const calendar = document.body.appendChild(new Element('div'));
const outside = document.body.appendChild(new Element('button'));

const DeadlineDayPopover = new Function('document', 'window', popoverSource + '\nreturn DeadlineDayPopover;')(
    document, window);
const { deadlineDayPopoverContent } = new Function(
    ['pad2', 'formatDate', 'formatCalendarTooltip', 'deadlineDayPopoverContent'].map(extract).join('\n')
    + '\nreturn { deadlineDayPopoverContent };')();

const failures = [];
function check(condition, what) {
    if (!condition) failures.push(what);
}

// FullCalendar's moreLinkClick argument for a day of n controls, as the dashboard's events give them
const COLORS = ['#C8102E', '#D4A843', '#9BA3B5', '#005EB8'];
function day(date, n) {
    const allSegs = [];
    for (let i = 1; i <= n; i++) {
        const fullId = `CTRL-${String(i).padStart(2, '0')}/FY26/Central/OCT`;
        allSegs.push({
            event: {
                title: fullId.split('/')[0],
                url: '/view-control/' + i,
                backgroundColor: COLORS[i % COLORS.length],
                textColor: i % COLORS.length === 1 ? '#1a1f2e' : '#ffffff',
                start: new Date(date + 'T00:00:00'),
                extendedProps: { fullId }
            }
        });
    }
    return { date: new Date(date + 'T00:00:00'), allSegs };
}
// A "+N more" link in a cell of the grid: row 0..4, column 0 (Sunday)..6 (Saturday)
function link(row, column) {
    const node = calendar.appendChild(new Element('a'));
    node.className = 'fc-daygrid-more-link fc-more-link';
    const left = 660 + column * 103;
    const top = 650 + row * 45;
    node.rect = { left, right: left + 60, top, bottom: top + 16, width: 60, height: 16 };
    return node;
}
const popovers = () => document.body.byClass('deadline-day-popover');
const shown = () => popovers()[0];
const press = (key, extra) => {
    let prevented = false;
    const event = Object.assign({ key, preventDefault() { prevented = true; } }, extra);
    return { event, prevented: () => prevented };
};
function inViewport(node) {
    const top = parseFloat(node.style.top);
    const left = parseFloat(node.style.left);
    return top >= 8 && left >= 8 && left + node.offsetWidth <= html.clientWidth - 8
        && top + Math.min(node.offsetHeight, parseFloat(node.style.maxHeight)) <= html.clientHeight - 8;
}

// A day of N controls: all N, as links to the controls, with the calendar's colours and tooltip
for (const [n, row, column, date, title] of [
    [2, 0, 1, '2026-09-28', 'Mon, 28.09.2026'],
    [5, 0, 6, '2026-10-03', 'Sat, 03.10.2026'],
    [2, 1, 0, '2026-10-04', 'Sun, 04.10.2026'],
    [15, 4, 0, '2026-10-25', 'Sun, 25.10.2026'],
    [5, 4, 1, '2026-10-26', 'Mon, 26.10.2026'],
    [18, 4, 6, '2026-10-31', 'Sat, 31.10.2026'],
    [1, 2, 3, '2026-10-14', 'Wed, 14.10.2026']]) {
    const anchor = link(row, column);
    const info = day(date, n);
    DeadlineDayPopover.toggle(anchor, deadlineDayPopoverContent(info));
    const popover = shown();
    const links = popover ? popover.byClass('deadline-day-popover-event') : [];
    check(popovers().length === 1, `${date}: one popover`);
    check(popover && popover.parentNode === document.body, `${date}: the popover is on <body>, out of the calendar`);
    check(links.length === n, `${date}: ${n} controls listed, not ${links.length}`);
    links.forEach((control, i) => {
        const event = info.allSegs[i].event;
        check(control.tagName === 'A' && control.href === event.url, `${date}: control ${i + 1} links to ${event.url}`);
        check(control.textContent === event.title, `${date}: control ${i + 1} reads ${event.title}`);
        check(control.style.backgroundColor === event.backgroundColor && control.style.color === event.textColor,
            `${date}: control ${i + 1} has the calendar's colours`);
        check(control.title === event.extendedProps.fullId + ' | Deadline: ' + date.split('-').reverse().join('.'),
            `${date}: control ${i + 1} has the calendar's tooltip`);
    });
    check(popover && popover.byClass('deadline-day-popover-title')[0].textContent === title,
        `${date}: the title is ${title}`);
    check(popover && popover.byClass('deadline-day-popover-count')[0].textContent
        === n + (n === 1 ? ' control' : ' controls'), `${date}: the count says ${n}`);
    check(popover && popover.getAttribute('role') === 'dialog'
        && popover.getAttribute('aria-labelledby') === popover.id + 'Title ' + popover.id + 'Count',
        `${date}: a dialog named by its date and count`);
    check(anchor.getAttribute('aria-expanded') === 'true' && anchor.getAttribute('aria-controls') === popover.id,
        `${date}: the link is expanded and points at the popover`);
    check(document.activeElement === links[0], `${date}: the focus is on the first control`);
    check(popover && inViewport(popover), `${date}: the popover is in the viewport`);
    DeadlineDayPopover.toggle(anchor, deadlineDayPopoverContent(info));
    check(popovers().length === 0, `${date}: a second click closes it`);
    check(anchor.getAttribute('aria-expanded') === 'false' && anchor.getAttribute('aria-controls') === null,
        `${date}: the link is collapsed again`);
    check(document.activeElement === anchor, `${date}: the focus is back on the link`);
}

// Placement: below the link when it fits, above it when not, over it when neither; never past an edge
const { place } = DeadlineDayPopover;
const viewport = { width: 1440, height: 900 };
const at = (top, left) => ({ top, bottom: top + 16, left, right: left + 60 });
let spot = place(at(100, 300), { width: 240, height: 200 }, viewport);
check(spot.placement === 'below' && spot.top === 118 && spot.left === 300, 'room below: below the link');
spot = place(at(850, 300), { width: 240, height: 200 }, viewport);
check(spot.placement === 'above' && spot.top + 200 === 848, 'last row: above the link');
spot = place(at(450, 300), { width: 240, height: 700 }, viewport);
check(spot.placement === 'over' && spot.top >= 8 && spot.top + 700 <= 892, 'no room either side: over the link, in view');
spot = place(at(450, 300), { width: 240, height: 2000 }, viewport);
check(spot.top === 8 && spot.maxHeight === 884, 'taller than the viewport: cut to it (the list scrolls)');
spot = place(at(100, 1400), { width: 240, height: 200 }, viewport);
check(spot.left === 1440 - 8 - 240, 'last column: moved left to the viewport edge');
spot = place(at(100, 2), { width: 240, height: 200 }, { width: 200, height: 900 });
check(spot.left === 8, 'narrow window: from the left edge');

// Closing: Esc (focus back), a click outside (focus stays), a press on the link itself does not count as outside
let anchor = link(0, 3);
DeadlineDayPopover.toggle(anchor, deadlineDayPopoverContent(day('2026-09-30', 3)));
let key = press('Escape');
document.fire('keydown', key.event);
check(popovers().length === 0 && key.prevented(), 'Esc closes');
check(document.activeElement === anchor, 'Esc: the focus is back on the link');

DeadlineDayPopover.toggle(anchor, deadlineDayPopoverContent(day('2026-09-30', 3)));
document.fire('mousedown', { target: shown().byClass('deadline-day-popover-event')[1] });
check(popovers().length === 1, 'a click inside keeps it');
document.fire('mousedown', { target: anchor });
check(popovers().length === 1, 'a press on its link is left to the click (a second click closes)');
outside.focus();
document.fire('mousedown', { target: outside });
check(popovers().length === 0, 'a click outside closes');
check(document.activeElement === outside, 'a click outside leaves the focus where it went');
check(Object.values(document.listeners).every(list => list.length === 0)
    && Object.values(window.listeners).every(list => list.length === 0), 'closed: no listeners left');

// One at a time: another day's link moves the popover there
const first = link(1, 2);
const second = link(3, 5);
DeadlineDayPopover.toggle(first, deadlineDayPopoverContent(day('2026-10-06', 4)));
DeadlineDayPopover.toggle(second, deadlineDayPopoverContent(day('2026-10-22', 6)));
check(popovers().length === 1 && shown().byClass('deadline-day-popover-event').length === 6,
    'another link: one popover, of the new day');
check(first.getAttribute('aria-expanded') === 'false' && second.getAttribute('aria-expanded') === 'true',
    'another link: only the new link is expanded');

// Tab stays inside: from the last control to the close button, back from it to the last control
let controls = shown().byClass('deadline-day-popover-event');
const closeButton = shown().byClass('deadline-day-popover-close')[0];
controls[controls.length - 1].focus();
key = press('Tab', { shiftKey: false });
shown().listeners.keydown.forEach(listener => listener(key.event));
check(key.prevented() && document.activeElement === closeButton, 'Tab on the last control: to the close button');
key = press('Tab', { shiftKey: true });
shown().listeners.keydown.forEach(listener => listener(key.event));
check(key.prevented() && document.activeElement === controls[controls.length - 1],
    'Shift+Tab on the close button: to the last control');
controls[0].focus();
key = press('Tab', { shiftKey: false });
shown().listeners.keydown.forEach(listener => listener(key.event));
check(!key.prevented(), 'Tab between controls is left to the browser');

// The close button closes and gives the focus back
closeButton.listeners.click.forEach(listener => listener({}));
check(popovers().length === 0 && document.activeElement === second, 'the close button closes, focus on the link');

// The page scrolls: the popover follows its link; the link out of view or drawn again (another month): closed
anchor = link(0, 2);
DeadlineDayPopover.toggle(anchor, deadlineDayPopoverContent(day('2026-09-29', 2)));
const topBefore = parseFloat(shown().style.top);
anchor.rect = { ...anchor.rect, top: anchor.rect.top - 100, bottom: anchor.rect.bottom - 100 };
window.fire('scroll', { target: document });
check(parseFloat(shown().style.top) === topBefore - 100, 'page scroll: the popover follows the link');
window.fire('scroll', { target: shown().byClass('deadline-day-popover-list')[0] });
check(popovers().length === 1, 'its own list scrolling changes nothing');
anchor.rect = { ...anchor.rect, top: -40, bottom: -24 };
window.fire('scroll', { target: document });
check(popovers().length === 0, 'link scrolled out of view: closed');

anchor = link(0, 2);
DeadlineDayPopover.toggle(anchor, deadlineDayPopoverContent(day('2026-09-29', 2)));
anchor.remove();
window.fire('resize', {});
check(popovers().length === 0, 'link drawn again: closed');

anchor = link(0, 2);
DeadlineDayPopover.toggle(anchor, deadlineDayPopoverContent(day('2026-09-29', 2)));
DeadlineDayPopover.close();
check(popovers().length === 0 && !DeadlineDayPopover.isOpen(), 'close() (another month) closes it');

console.log(JSON.stringify({ failures }));
process.exit(failures.length ? 1 : 0);
