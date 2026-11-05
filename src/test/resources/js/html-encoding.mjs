// Output encoding in the browser: the shared escapeHtml (static/js/html-escape.js) and View Control's people picker
// item (createCompactUserItem, cut out of view-control.js as it is), which must put a person's name and e-mail in as
// text, never as HTML. Exit code 1 on a failed check.
// Usage: node html-encoding.mjs <html-escape.js> <view-control.js>
import { readFileSync } from 'node:fs';

const escapeSource = readFileSync(process.argv[2], 'utf8');
const viewSource = readFileSync(process.argv[3], 'utf8');

function extract(name) {
    const start = viewSource.indexOf(`function ${name}(`);
    if (start < 0) {
        throw new Error(`no function ${name} in view-control.js`);
    }
    let depth = 0;
    for (let i = viewSource.indexOf('{', start); i < viewSource.length; i++) {
        if (viewSource[i] === '{') depth++;
        if (viewSource[i] === '}' && --depth === 0) return viewSource.slice(start, i + 1);
    }
    throw new Error(`function ${name} does not end`);
}

const failures = [];
function check(condition, what) {
    if (!condition) failures.push(what);
}

// escapeHtml
const escapeHtml = new Function(escapeSource + '\nreturn escapeHtml;')();
check(escapeHtml('<img src=x onerror="alert(1)">') === '&lt;img src=x onerror=&quot;alert(1)&quot;&gt;',
    'tags and double quotes are escaped');
check(escapeHtml("Tom & 'Jerry'") === 'Tom &amp; &#39;Jerry&#39;', 'ampersand and single quotes are escaped');
check(escapeHtml('&lt;') === '&amp;lt;', 'an already escaped text is escaped once more, not left as markup');
check(escapeHtml('Seytbekov, Sultan') === 'Seytbekov, Sultan', 'plain text stays as it is');
check(escapeHtml(42) === '42', 'a number becomes its text');

// A minimal DOM: records what is set; any innerHTML is a failure here
let innerHtmlWrites = 0;
function element(tag) {
    return {
        tag, className: '', dataset: {}, children: [], textContent: '',
        set innerHTML(value) { innerHtmlWrites++; },
        append(...nodes) { this.children.push(...nodes); }
    };
}
const document = { createElement: element };
const { createCompactUserItem } = new Function('document',
    extract('createCompactUserItem') + '\n' + extract('textDiv') + '\nreturn { createCompactUserItem };')(document);

function shown(item) {
    const [initials, details] = item.children;
    const [name, mail] = details ? details.children : [];
    return {
        initials: initials && initials.className + '=' + initials.textContent,
        details: details && details.className,
        name: name && name.className + '=' + name.textContent,
        mail: mail && mail.className + '=' + mail.textContent
    };
}

const attack = '<img src=x onerror=alert(1)>';
const bad = createCompactUserItem({ displayName: attack, mail: '"><script>x()</script>@kpmg.kz' });
const badShown = shown(bad);
check(innerHtmlWrites === 0, 'no innerHTML is written for a person');
check(badShown.name === 'user-name=' + attack, 'a name with a tag is shown as that text');
check(badShown.mail === 'user-email="><script>x()</script>@kpmg.kz', 'an e-mail with a tag is shown as that text');
check(badShown.initials === 'user-initials=<S', 'initials are text too');

const sultan = createCompactUserItem({ displayName: 'Seytbekov, Sultan', mail: 'sseytbekov@kpmg.kz' }, true);
const sultanShown = shown(sultan);
check(sultan.className.trim() === 'compact-user-item selected', 'a selected person keeps the selected class');
check(sultan.dataset.email === 'sseytbekov@kpmg.kz', 'the e-mail stays on the item for the click handler');
check(sultanShown.initials === 'user-initials=SS', 'initials as before');
check(sultanShown.details === 'user-details', 'name and e-mail sit in user-details as before');
check(sultanShown.name === 'user-name=Seytbekov, Sultan', 'the name as before');
check(sultanShown.mail === 'user-email=sseytbekov@kpmg.kz', 'the e-mail as before');
check(createCompactUserItem({ displayName: 'Anna Petrova', mail: 'a@kpmg.kz' }).className.trim() === 'compact-user-item',
    'a person not selected has no selected class');

console.log(JSON.stringify({ failures }));
process.exit(failures.length ? 1 : 0);
