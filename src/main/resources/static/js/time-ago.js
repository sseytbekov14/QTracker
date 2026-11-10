/* global window, document */
// "2 hours ago" for elements marked data-time (yyyy-mm-ddThh:mm[:ss], the server's local time, Almaty), counted from
// the server's "now" in data-now on an element around them plus the time since the page opened - so the browser's own
// time zone does not matter. The exact date and time stay on the page next to it. Used by the Admin Panel E-mails page.
(function () {
    function parse(value) {
        var match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?/.exec(value || '');
        if (!match) return null;
        return Date.UTC(+match[1], +match[2] - 1, +match[3], +match[4], +match[5], +(match[6] || 0));
    }

    var MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

    /** The time of then, counted from now (both as the server writes them). */
    function format(thenValue, nowValue) {
        var then = parse(thenValue);
        var now = parse(nowValue);
        if (then === null || now === null) return '';
        var minutes = Math.floor((now - then) / 60000);
        if (minutes < 1) return 'just now';
        if (minutes < 60) return minutes + ' min ago';
        var hours = Math.floor(minutes / 60);
        if (hours < 24) return hours === 1 ? '1 hour ago' : hours + ' hours ago';
        var thenDay = Math.floor(then / 86400000);
        var nowDay = Math.floor(now / 86400000);
        var days = nowDay - thenDay;
        if (days === 1) return 'yesterday';
        if (days < 7) return days + ' days ago';
        var date = new Date(then);
        return date.getUTCDate() + ' ' + MONTHS[date.getUTCMonth()] + ' ' + date.getUTCFullYear();
    }

    function pad(n) {
        return (n < 10 ? '0' : '') + n;
    }

    /** now as yyyy-mm-ddThh:mm:ss, moved on by the time since the page opened. */
    function nowFrom(serverNow, openedAt) {
        var base = parse(serverNow);
        if (base === null) return null;
        var date = new Date(base + (Date.now() - openedAt));
        return date.getUTCFullYear() + '-' + pad(date.getUTCMonth() + 1) + '-' + pad(date.getUTCDate()) + 'T'
            + pad(date.getUTCHours()) + ':' + pad(date.getUTCMinutes()) + ':' + pad(date.getUTCSeconds());
    }

    function refresh(root, openedAt) {
        var now = nowFrom(root.getAttribute('data-now'), openedAt);
        if (!now) return;
        root.querySelectorAll('[data-time]').forEach(function (element) {
            var text = format(element.getAttribute('data-time'), now);
            if (text) element.textContent = text;
        });
    }

    window.QTrackerTimeAgo = { format: format };
    if (typeof document !== 'undefined' && document.addEventListener) {
        document.addEventListener('DOMContentLoaded', function () {
            var openedAt = Date.now();
            document.querySelectorAll('[data-now]').forEach(function (root) {
                refresh(root, openedAt);
                setInterval(function () { refresh(root, openedAt); }, 60000);
            });
        });
    }
})();
