/* global window, document */
// English date picker for text inputs marked data-date-picker (dd.mm.yyyy).
// The native <input type="date"> follows the browser's language, so it showed Russian.
// The input keeps the display value; the ISO date is in input.dataset.isoValue. Typing a date works too.
// It opens only while the input is editable (not read-only, not disabled, no readonly-field class).
(function () {
    var MONTHS = ['January', 'February', 'March', 'April', 'May', 'June',
        'July', 'August', 'September', 'October', 'November', 'December'];
    var WEEKDAYS = ['Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa', 'Su'];
    var D = window.QTrackerDate;

    function isEditable(input) {
        return !(input.readOnly || input.disabled || input.classList.contains('readonly-field'));
    }

    function currentDate(input) {
        return D.parseDisplayDate(input.value) || D.parseIsoDate(input.dataset.isoValue || '');
    }

    function sameDay(a, b) {
        return a && b && a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
    }

    function attach(input) {
        if (!input || input.dataset.datePickerAttached === 'true' || !D) return;
        input.dataset.datePickerAttached = 'true';
        input.setAttribute('autocomplete', 'off');

        var host = input.parentElement;
        host.classList.add('qt-datepicker-host');
        var popup = document.createElement('div');
        popup.className = 'qt-datepicker';
        popup.hidden = true;
        popup.setAttribute('role', 'dialog');
        popup.setAttribute('aria-label', 'Choose a date');
        host.appendChild(popup);

        var shown = null; // first day of the month on screen

        function setDate(date) {
            if (date) {
                input.value = D.formatDisplayDate(date);
                input.dataset.isoValue = D.toIsoDate(date);
            } else {
                input.value = '';
                delete input.dataset.isoValue;
            }
            input.dispatchEvent(new Event('input', { bubbles: true }));
            input.dispatchEvent(new Event('change', { bubbles: true }));
        }

        function render() {
            var selected = currentDate(input);
            var today = new Date();
            var year = shown.getFullYear();
            var month = shown.getMonth();
            var html = '<div class="qt-datepicker-head">'
                + '<button type="button" class="qt-datepicker-nav" data-step="-1" aria-label="Previous month">&lsaquo;</button>'
                + '<span class="qt-datepicker-title">' + MONTHS[month] + ' ' + year + '</span>'
                + '<button type="button" class="qt-datepicker-nav" data-step="1" aria-label="Next month">&rsaquo;</button>'
                + '</div><div class="qt-datepicker-grid">';
            WEEKDAYS.forEach(function (day) {
                html += '<span class="qt-datepicker-weekday">' + day + '</span>';
            });
            var offset = (new Date(year, month, 1).getDay() + 6) % 7; // Monday first
            for (var i = 0; i < offset; i++) {
                html += '<span></span>';
            }
            var days = new Date(year, month + 1, 0).getDate();
            for (var d = 1; d <= days; d++) {
                var date = new Date(year, month, d);
                var classes = 'qt-datepicker-day'
                    + (sameDay(date, selected) ? ' is-selected' : '')
                    + (sameDay(date, today) ? ' is-today' : '');
                html += '<button type="button" class="' + classes + '" data-day="' + d + '"'
                    + (sameDay(date, selected) ? ' aria-pressed="true"' : '')
                    + ' aria-label="' + d + ' ' + MONTHS[month] + ' ' + year + '">' + d + '</button>';
            }
            html += '</div><div class="qt-datepicker-foot">'
                + '<button type="button" class="qt-datepicker-link" data-action="today">Today</button>'
                + '<button type="button" class="qt-datepicker-link" data-action="clear">Clear</button>'
                + '</div>';
            popup.innerHTML = html;
        }

        function open() {
            if (!popup.hidden || !isEditable(input)) return;
            var selected = currentDate(input) || new Date();
            shown = new Date(selected.getFullYear(), selected.getMonth(), 1);
            render();
            // Right under the input, whatever padding or label its container has
            popup.style.left = input.offsetLeft + 'px';
            popup.style.top = (input.offsetTop + input.offsetHeight) + 'px';
            popup.hidden = false;
        }

        function close() {
            popup.hidden = true;
        }

        input.addEventListener('click', open);
        input.addEventListener('focus', open);
        input.addEventListener('keydown', function (event) {
            if (event.key === 'Escape') close();
            if (event.key === 'ArrowDown') {
                event.preventDefault();
                open();
                var target = popup.querySelector('.is-selected') || popup.querySelector('.qt-datepicker-day');
                if (target) target.focus();
            }
        });
        // A typed date: keep the ISO value in step, so clearing the text also clears the date
        input.addEventListener('input', function (event) {
            if (!event.isTrusted) return;
            var typed = D.parseDisplayDate(input.value);
            if (typed) {
                input.dataset.isoValue = D.toIsoDate(typed);
            } else if (!input.value.trim()) {
                delete input.dataset.isoValue;
            }
            if (!popup.hidden && typed) {
                shown = new Date(typed.getFullYear(), typed.getMonth(), 1);
                render();
            }
        });

        // mousedown would blur the input before the click lands
        popup.addEventListener('mousedown', function (event) {
            event.preventDefault();
        });
        popup.addEventListener('click', function (event) {
            var button = event.target.closest('button');
            if (!button) return;
            if (button.dataset.step) {
                shown = new Date(shown.getFullYear(), shown.getMonth() + Number(button.dataset.step), 1);
                render();
            } else if (button.dataset.day) {
                setDate(new Date(shown.getFullYear(), shown.getMonth(), Number(button.dataset.day)));
                close();
            } else if (button.dataset.action === 'today') {
                setDate(new Date());
                close();
            } else if (button.dataset.action === 'clear') {
                setDate(null);
                close();
            }
        });
        popup.addEventListener('keydown', function (event) {
            if (event.key === 'Escape') {
                close();
                input.focus();
            }
        });
        popup.addEventListener('focusout', function (event) {
            if (!host.contains(event.relatedTarget)) close();
        });

        document.addEventListener('mousedown', function (event) {
            if (!popup.hidden && !host.contains(event.target)) close();
        });
        input.addEventListener('blur', function () {
            setTimeout(function () {
                if (!popup.contains(document.activeElement)) close();
            }, 0);
        });
    }

    window.QTrackerDatePicker = { attach: attach };
    document.addEventListener('DOMContentLoaded', function () {
        document.querySelectorAll('input[data-date-picker]').forEach(attach);
    });
})();
