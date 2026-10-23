// The Action Centre's list of a component's controls (component-controls.html).
// Sorting and pages are links and the filters a GET form; this adds the row click, the status select that applies
// itself and the live search: the search (as one types) and the KDN box (at once) load the same page and put its
// counters, rows and pages in place, without touching the search field.
(function () {
    'use strict';

    // A row opens its control on View Control: click anywhere on it, or Enter when it has the focus.
    // The Control ID stays a real link (new tab, keyboard, no scripts). On the document: the rows are replaced.
    document.addEventListener('click', function (event) {
        var row = event.target.closest && event.target.closest('#ccTable tr.cc-row');
        if (!row || event.target.closest('a, button')) {
            return;
        }
        if (window.getSelection && String(window.getSelection()).length > 0) {
            return; // selecting text
        }
        if (event.ctrlKey || event.metaKey) {
            window.open(row.dataset.href, '_blank');
        } else {
            window.location.href = row.dataset.href;
        }
    });
    document.addEventListener('keydown', function (event) {
        var row = event.target;
        if (event.key === 'Enter' && row.matches && row.matches('#ccTable tr.cc-row')) {
            event.preventDefault();
            window.location.href = row.dataset.href;
        }
    });

    // Phone: "Sort by" opens the list sorted that way
    document.addEventListener('change', function (event) {
        if (event.target.id === 'ccSortPhone') {
            window.location.href = event.target.value;
        }
    });

    var form = document.getElementById('ccFilters');
    if (!form) {
        return;
    }

    // The status filter applies at once, as a new page
    var status = document.getElementById('ccStatus');
    if (status) {
        status.addEventListener('change', function () {
            form.submit();
        });
    }

    // Live search. The form's fields as the URL the form would send (no page: a new search starts on page 1)
    var SEARCH_DELAY = 300;
    var search = document.getElementById('ccSearch');
    var kdn = document.getElementById('ccKdn');
    var timer = null;
    var controller = null;
    var latest = 0;
    var shown = formUrl();

    function formUrl() {
        var params = new URLSearchParams();
        new FormData(form).forEach(function (value, name) {
            var text = String(value).trim();
            if (text !== '') {
                params.append(name, text);
            }
        });
        var query = params.toString();
        return form.getAttribute('action') + (query ? '?' + query : '');
    }

    function later() {
        clearTimeout(timer);
        timer = setTimeout(refresh, SEARCH_DELAY);
    }

    function refresh() {
        clearTimeout(timer);
        var url = formUrl();
        // Only the last request counts: the one before is cancelled, and an answer that still comes is dropped
        cancel();
        if (url === shown) {
            return; // typed back to what the page shows
        }
        controller = new AbortController();
        var request = ++latest;
        var results = document.getElementById('ccResults');
        if (results) {
            results.setAttribute('aria-busy', 'true');
        }
        window.fetch(url, { credentials: 'same-origin', signal: controller.signal, headers: { Accept: 'text/html' } })
            .then(function (response) {
                if (!response.ok) {
                    throw new Error('HTTP ' + response.status);
                }
                return response.text();
            })
            .then(function (html) {
                if (request !== latest) {
                    return;
                }
                controller = null;
                if (!show(new DOMParser().parseFromString(html, 'text/html'))) {
                    window.location.href = url; // not the list (signed out, an error): as the form would have gone
                    return;
                }
                shown = url;
                window.history.replaceState(null, '', url);
            })
            .catch(function (error) {
                if (request !== latest || (error && error.name === 'AbortError')) {
                    return;
                }
                controller = null;
                window.location.href = url;
            });
    }

    function cancel() {
        if (!controller) {
            return;
        }
        controller.abort();
        controller = null;
        latest++;
        var results = document.getElementById('ccResults');
        if (results) {
            results.removeAttribute('aria-busy');
        }
    }

    // The parts of the page the search and the KDN box change; the form's fields stay as they are (focus, caret)
    function show(page) {
        if (!page.getElementById('ccResults') || !page.getElementById('ccFilters')) {
            return false;
        }
        ['.page-header', '.stats-bar', '#ccSortPhoneField', '#ccActions', '#ccResults'].forEach(function (selector) {
            var current = document.querySelector(selector);
            var fresh = page.querySelector(selector);
            if (current && fresh) {
                current.replaceWith(document.importNode(fresh, true));
            }
        });
        return true;
    }

    if (search) {
        search.addEventListener('input', later);
    }
    if (kdn) {
        kdn.addEventListener('change', refresh);
    }
    // Enter in the search: at once, without leaving the page
    form.addEventListener('submit', function (event) {
        event.preventDefault();
        refresh();
    });
})();
