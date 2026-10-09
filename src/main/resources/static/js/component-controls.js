// The Action Centre's list of a component's controls (component-controls.html).
// Sorting, search, filter and pages are links and a GET form; this only adds the row click and the
// status select that applies itself.
(function () {
    'use strict';

    // A row opens its control on View Control: click anywhere on it, or Enter when it has the focus.
    // The Control ID stays a real link (new tab, keyboard, no scripts).
    var table = document.getElementById('ccTable');
    if (table) {
        table.addEventListener('click', function (event) {
            var row = event.target.closest('tr.cc-row');
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
        table.addEventListener('keydown', function (event) {
            var row = event.target;
            if (event.key === 'Enter' && row.matches && row.matches('tr.cc-row')) {
                event.preventDefault();
                window.location.href = row.dataset.href;
            }
        });
    }

    // Phone: "Sort by" opens the list sorted that way
    var sortPhone = document.getElementById('ccSortPhone');
    if (sortPhone) {
        sortPhone.addEventListener('change', function () {
            window.location.href = sortPhone.value;
        });
    }

    // The status filter applies at once (the Apply button stays for the search and without scripts)
    var status = document.getElementById('ccStatus');
    if (status && status.form) {
        status.addEventListener('change', function () {
            status.form.submit();
        });
    }
})();
