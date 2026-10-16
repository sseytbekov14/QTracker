// The people pickers of the Assignment tab: View Control's own userMatchesQuery (cut out of view-control.js as it is)
// finds a person by any part of the name or the e-mail, in any case, as Control Shared With does. Exit code 1 on a
// failed check.
// Usage: node people-search.mjs <view-control.js>
import { readFileSync } from 'node:fs';

const viewSource = readFileSync(process.argv[2], 'utf8');

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

const { userMatchesQuery } = new Function(extract('userMatchesQuery') + '\nreturn { userMatchesQuery };')();

const failures = [];
function check(condition, what) {
    if (!condition) failures.push(what);
}

const sultan = { displayName: 'Seytbekov, Sultan', mail: 'sseytbekov@kpmg.kz' };
for (const query of ['Seyt', 'seytbekov', 'Seytbekov, S', 'Sultan', 'sultan', 'SULTAN', 'bekov', 'tan',
    'sseyt', 'kpmg.kz', '@kpmg', '  sultan  ']) {
    check(userMatchesQuery(sultan, query), `"${query}" finds Seytbekov, Sultan`);
}
for (const query of ['Ivanov', 'Sultan Seytbekov', 'gmail']) {
    check(!userMatchesQuery(sultan, query), `"${query}" does not find Seytbekov, Sultan`);
}
check(userMatchesQuery(sultan, ''), 'an empty search keeps everyone');
check(userMatchesQuery(sultan, '   '), 'a blank search keeps everyone');
check(userMatchesQuery({ mail: 'only.mail@kpmg.kz' }, 'only'), 'a person without a name is found by the e-mail');
check(userMatchesQuery({ displayName: 'No Mail' }, 'mail'), 'a person without an e-mail is found by the name');
check(!userMatchesQuery(null, 'a'), 'no person is never found');

console.log(JSON.stringify({ failures }));
process.exit(failures.length ? 1 : 0);
