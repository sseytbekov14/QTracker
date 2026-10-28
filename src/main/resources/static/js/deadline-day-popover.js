// "+N more" of the Deadline Calendar on the dashboard: the day's controls in one popover on <body> with
// position: fixed. FullCalendar's own popover is placed inside the calendar (.fc-view-harness), where
// #deadlineCalendarWrapper (fixed height, overflow: hidden) cut it off, and it only ever opened downwards.
// This one is placed in the viewport: below the link, above it when there is no room below, moved left at the right
// edge; a list taller than the viewport scrolls inside it. One popover at a time; it closes on Esc, a click outside,
// a second click on the link, and when the link scrolls out of view or is drawn again (another month).
const DeadlineDayPopover = (function () {
    'use strict';

    const EDGE = 8;   // the least gap to the viewport edges
    const GAP = 2;    // between the link and the popover

    let popover = null;
    let anchor = null;
    let focusables = [];   // the close button and the control links, in Tab (document) order
    let opened = 0;

    function controlsText(count) {
        return count + (count === 1 ? ' control' : ' controls');
    }

    // Where a popover of this size goes for a link at anchorRect: below it when it fits, else above it, else
    // as high as the viewport needs (it is then cut to the viewport and its list scrolls). Moved left to stay
    // in the viewport.
    function place(anchorRect, size, viewport) {
        const maxHeight = viewport.height - 2 * EDGE;
        const height = Math.min(size.height, maxHeight);
        const width = Math.min(size.width, viewport.width - 2 * EDGE);
        const below = anchorRect.bottom + GAP;
        const above = anchorRect.top - GAP - height;
        let top;
        let placement;
        if (below + height <= viewport.height - EDGE) {
            top = below;
            placement = 'below';
        } else if (above >= EDGE) {
            top = above;
            placement = 'above';
        } else {
            top = Math.max(EDGE, Math.min(below, viewport.height - EDGE - height));
            placement = 'over';
        }
        const left = Math.max(EDGE, Math.min(anchorRect.left, viewport.width - EDGE - width));
        return { top: top, left: left, maxHeight: maxHeight, placement: placement };
    }

    // day: { title: 'Sat, 31.10.2026', controls: [{ title, url, color, textColor, tooltip }] }
    function build(day) {
        opened += 1;
        const id = 'deadlineDayPopover' + opened;
        const controls = Array.isArray(day.controls) ? day.controls : [];

        const root = document.createElement('div');
        root.id = id;
        root.className = 'deadline-day-popover';
        root.setAttribute('role', 'dialog');
        root.setAttribute('aria-labelledby', id + 'Title ' + id + 'Count');

        const header = document.createElement('div');
        header.className = 'deadline-day-popover-header';
        const title = document.createElement('span');
        title.id = id + 'Title';
        title.className = 'deadline-day-popover-title';
        title.textContent = day.title || '';
        const count = document.createElement('span');
        count.id = id + 'Count';
        count.className = 'deadline-day-popover-count';
        count.textContent = controlsText(controls.length);
        const closeButton = document.createElement('button');
        closeButton.type = 'button';
        closeButton.className = 'btn-close deadline-day-popover-close';
        closeButton.setAttribute('aria-label', 'Close');
        closeButton.addEventListener('click', function () { close(true); });
        header.appendChild(title);
        header.appendChild(count);
        header.appendChild(closeButton);

        const list = document.createElement('ul');
        list.className = 'deadline-day-popover-list';
        focusables = [closeButton];
        controls.forEach(function (control) {
            const item = document.createElement('li');
            const link = document.createElement('a');
            link.className = 'deadline-day-popover-event';
            if (control.url) {
                link.href = control.url;
            }
            link.textContent = control.title || 'Control';
            if (control.tooltip) {
                link.title = control.tooltip;
            }
            if (control.color) {
                link.style.backgroundColor = control.color;
                link.style.borderColor = control.color;
            }
            if (control.textColor) {
                link.style.color = control.textColor;
            }
            item.appendChild(link);
            list.appendChild(item);
            focusables.push(link);
        });

        root.appendChild(header);
        root.appendChild(list);
        root.addEventListener('keydown', onPopoverKeyDown);
        return root;
    }

    function viewport() {
        return { width: document.documentElement.clientWidth, height: document.documentElement.clientHeight };
    }

    // Follows the link; closes when the link is gone (the calendar drawn again) or out of view
    function position() {
        if (!popover) {
            return;
        }
        const view = viewport();
        const rect = anchor.isConnected ? anchor.getBoundingClientRect() : null;
        if (!rect || (rect.width === 0 && rect.height === 0) || rect.bottom <= 0 || rect.top >= view.height) {
            close(false);
            return;
        }
        popover.style.maxHeight = (view.height - 2 * EDGE) + 'px';
        const spot = place(rect, { width: popover.offsetWidth, height: popover.offsetHeight }, view);
        popover.style.top = spot.top + 'px';
        popover.style.left = spot.left + 'px';
        popover.setAttribute('data-placement', spot.placement);
    }

    function open(link, day) {
        close(false);
        anchor = link;
        popover = build(day);
        document.body.appendChild(popover);
        link.setAttribute('aria-expanded', 'true');
        link.setAttribute('aria-controls', popover.id);
        position();
        if (!popover) {
            return;
        }
        // The first control, or the close button of a day without any
        focusables[Math.min(1, focusables.length - 1)].focus({ preventScroll: true });
        document.addEventListener('mousedown', onDocumentMouseDown, true);
        document.addEventListener('keydown', onDocumentKeyDown);
        window.addEventListener('scroll', onScroll, true);
        window.addEventListener('resize', position);
    }

    // returnFocus: back to the "+N more" link (Esc, the close button, a second click on the link)
    function close(returnFocus) {
        if (!popover) {
            return;
        }
        document.removeEventListener('mousedown', onDocumentMouseDown, true);
        document.removeEventListener('keydown', onDocumentKeyDown);
        window.removeEventListener('scroll', onScroll, true);
        window.removeEventListener('resize', position);
        popover.remove();
        const link = anchor;
        popover = null;
        anchor = null;
        focusables = [];
        link.setAttribute('aria-expanded', 'false');
        link.removeAttribute('aria-controls');
        if (returnFocus && link.isConnected) {
            link.focus();
        }
    }

    // A second click on the open day's link closes it; any other link opens its day instead
    function toggle(link, day) {
        if (popover && anchor === link) {
            close(true);
        } else {
            open(link, day);
        }
    }

    // The link's own click toggles, so a press on it is not "outside"
    function onDocumentMouseDown(event) {
        if (popover && !popover.contains(event.target) && !anchor.contains(event.target)) {
            close(false);
        }
    }

    function onDocumentKeyDown(event) {
        if (event.key === 'Escape' && popover) {
            event.preventDefault();
            close(true);
        }
    }

    // Tab stays in the popover
    function onPopoverKeyDown(event) {
        if (event.key !== 'Tab' || focusables.length === 0) {
            return;
        }
        const first = focusables[0];
        const last = focusables[focusables.length - 1];
        if (event.shiftKey && document.activeElement === first) {
            event.preventDefault();
            last.focus();
        } else if (!event.shiftKey && document.activeElement === last) {
            event.preventDefault();
            first.focus();
        }
    }

    // The page (or a box around the calendar) scrolled; the popover's own list scrolling changes nothing
    function onScroll(event) {
        if (popover && !popover.contains(event.target)) {
            position();
        }
    }

    return {
        toggle: toggle,
        close: function () { close(false); },
        isOpen: function () { return popover !== null; },
        place: place
    };
})();
