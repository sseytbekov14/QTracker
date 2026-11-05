// The one escapeHtml of the pages that build HTML from strings (Dashboard, View Control): text and attribute values
// go into the HTML only through it, so a name, a comment or a file name is always shown as text, never run as HTML.
// Where the HTML is built with DOM calls, textContent does the same job.
function escapeHtml(value) {
    return String(value)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}
