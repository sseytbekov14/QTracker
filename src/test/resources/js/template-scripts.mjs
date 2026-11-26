// Every inline <script> of the Thymeleaf templates must parse (2026-11-26: a lost backslash broke the whole
// Admin Panel script and no test saw it). Thymeleaf's natural inlining /*[[...]]*/ 'default' is already valid
// JavaScript; a bare [[...]] or [(...)] is replaced by null first. Prints one line per broken script, "OK n" at the end.
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';

const dir = process.argv[2];
let checked = 0;
const problems = [];
for (const name of fs.readdirSync(dir).filter(f => f.endsWith('.html')).sort()) {
    const html = fs.readFileSync(path.join(dir, name), 'utf8');
    const re = /<script(\s[^>]*)?>([\s\S]*?)<\/script>/gi;
    let m;
    while ((m = re.exec(html)) !== null) {
        const attrs = m[1] || '';
        if (/\bsrc\s*=/.test(attrs) || /type\s*=\s*["'](?!text\/javascript|module)[^"']*["']/i.test(attrs)) {
            continue;
        }
        const line = html.slice(0, m.index).split('\n').length;
        const code = m[2]
            .replace(/\/\*\[\[[\s\S]*?\]\]\*\//g, '')
            .replace(/\[\[[^\n]*?\]\]|\[\([^\n]*?\)\]/g, 'null');
        checked++;
        try {
            new vm.Script(/\btype\s*=\s*["']module["']/i.test(attrs) ? '' : code, { filename: name + ':' + line });
        } catch (error) {
            problems.push(name + ':' + line + ' ' + error.message);
        }
    }
}
problems.forEach(p => console.log('BROKEN ' + p));
console.log('OK ' + checked);
