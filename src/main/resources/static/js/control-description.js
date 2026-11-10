/* global window, document */
// The Control Description in the header of View Control and Performance cycle (2026-11-10): a long text shows its
// first two lines with "Show more"; the button opens the whole text in place, line breaks kept, and "Show less" folds
// it again. A text that fits in two lines gets no button. Without this script the whole text is shown.
// Markup: <div data-control-description><p class="ctl-desc-text" id="..">text</p>
//         <button type="button" class="ctl-desc-toggle" aria-controls=".." aria-expanded="false" hidden>Show more</button></div>
(function () {
    var COLLAPSIBLE = 'is-collapsible';
    var OPEN = 'is-open';

    /** Folds the text to two lines; keeps the fold and shows the button only if some text is cut off. */
    function measure(root) {
        var text = root.querySelector('.ctl-desc-text');
        var button = root.querySelector('.ctl-desc-toggle');
        if (!text || !button || root.classList.contains(OPEN)) return;
        root.classList.add(COLLAPSIBLE);
        var cut = text.scrollHeight > text.clientHeight + 1;
        if (!cut) root.classList.remove(COLLAPSIBLE);
        button.hidden = !cut;
    }

    function toggle(root) {
        var button = root.querySelector('.ctl-desc-toggle');
        var open = !root.classList.contains(OPEN);
        root.classList.toggle(OPEN, open);
        button.setAttribute('aria-expanded', open ? 'true' : 'false');
        button.textContent = open ? 'Show less' : 'Show more';
        if (!open) measure(root);
    }

    function init(scope) {
        var roots = (scope || document).querySelectorAll('[data-control-description]');
        roots.forEach(function (root) {
            var button = root.querySelector('.ctl-desc-toggle');
            if (!button) return;
            button.addEventListener('click', function () { toggle(root); });
            measure(root);
        });
        return roots;
    }

    window.QTrackerControlDescription = { init: init, measure: measure, toggle: toggle };
    if (typeof document !== 'undefined' && document.addEventListener) {
        var start = function () {
            var roots = init(document);
            var timer = null;
            // A narrower or wider window cuts the text at another place
            window.addEventListener('resize', function () {
                clearTimeout(timer);
                timer = setTimeout(function () { roots.forEach(measure); }, 150);
            });
        };
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', start);
        } else {
            start();
        }
    }
})();
