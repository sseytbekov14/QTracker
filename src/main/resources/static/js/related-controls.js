// Other Related Controls on View Control (Details tab), SoQM Admin Team only. The server renders the linked controls as
// chips, each a link to that control's page (RelatedControls). In edit mode this adds a remove button to each chip and a
// search with a list of every other control (/api/controls/{id}/related-options); Save sends the chosen ids with
// Details as relatedControlIds (buildDetailsPayload), in the order chosen.
const RelatedControlsField = (function () {
    'use strict';

    let field = null;
    let chips = null;
    let searchWrap = null;
    let search = null;
    let listbox = null;
    let emptyNote = null;
    let count = null;
    let status = null;
    let errorBox = null;
    let unsaved = null;

    let related = [];         // { id, controlId, status } in the order of the field
    let candidates = null;    // the controls the picker offers, loaded once
    let loading = null;
    let shown = [];           // the candidates in the open list
    let activeIndex = -1;
    let typedActive = false;  // the active option was picked by typing, not by the arrows: Enter only adds it
    let editing = false;
    let savedKey = '';        // the ids as last saved, to tell an unsaved change

    // How many matches the open list shows at most; typing narrows it
    const SHOWN_AT_MOST = 100;

    const idOf = (value) => String(value);

    function isSelected(id) {
        return related.some((control) => idOf(control.id) === idOf(id));
    }

    // ------------------------------------------------------------------ the chips

    function readRelated() {
        return Array.from(chips.querySelectorAll('.rc-chip')).map((chip) => ({
            id: Number(chip.dataset.id),
            controlId: chip.dataset.controlId || '',
            status: chip.dataset.status || ''
        }));
    }

    function textSpan(className, text) {
        const span = document.createElement('span');
        span.className = className;
        span.textContent = text;
        return span;
    }

    function linkIcon(className) {
        const icon = document.createElement('i');
        icon.className = 'bi bi-link-45deg ' + className;
        icon.setAttribute('aria-hidden', 'true');
        return icon;
    }

    function removeIcon() {
        const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
        svg.setAttribute('viewBox', '0 0 16 16');
        svg.setAttribute('width', '14');
        svg.setAttribute('height', '14');
        svg.setAttribute('aria-hidden', 'true');
        svg.setAttribute('focusable', 'false');
        const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
        path.setAttribute('d', 'M4 4l8 8M12 4l-8 8');
        path.setAttribute('stroke', 'currentColor');
        path.setAttribute('stroke-width', '1.8');
        path.setAttribute('stroke-linecap', 'round');
        svg.appendChild(path);
        return svg;
    }

    function chipElement(control, index) {
        const chip = document.createElement('li');
        chip.className = 'sw-chip rc-chip';
        chip.dataset.id = control.id;
        chip.dataset.controlId = control.controlId;
        chip.dataset.status = control.status;

        const link = document.createElement('a');
        link.className = 'rc-chip-link';
        link.href = '/view-control/' + encodeURIComponent(control.id);
        link.title = 'Open ' + control.controlId;
        const text = document.createElement('span');
        text.className = 'sw-chip-text';
        text.appendChild(textSpan('sw-chip-name', control.controlId));
        if (control.status) {
            text.appendChild(textSpan('sw-chip-mail', control.status));
        }
        link.append(linkIcon('rc-chip-icon'), text);
        chip.appendChild(link);

        if (editing) {
            const remove = document.createElement('button');
            remove.type = 'button';
            remove.className = 'sw-chip-remove';
            remove.setAttribute('aria-label', 'Remove ' + control.controlId);
            remove.title = 'Remove';
            remove.appendChild(removeIcon());
            remove.addEventListener('click', (event) => {
                event.preventDefault();
                removeAt(index, true);
            });
            chip.appendChild(remove);
        }
        return chip;
    }

    function controlsCount(n) {
        return n === 1 ? '1 control' : n + ' controls';
    }

    function render() {
        chips.replaceChildren(...related.map(chipElement));
        emptyNote.hidden = related.length > 0;
        count.hidden = related.length === 0;
        count.textContent = controlsCount(related.length);
        field.classList.toggle('is-editing', editing);
        if (searchWrap) {
            searchWrap.hidden = !editing;
        }
        if (unsaved) {
            unsaved.hidden = !isChanged();
        }
    }

    function announce(message) {
        if (!status) {
            return;
        }
        status.textContent = '';
        // A fresh text node so screen readers read the same message twice
        window.setTimeout(() => { status.textContent = message; }, 30);
    }

    function removeAt(index, fromButton) {
        const control = related[index];
        if (!control) {
            return;
        }
        related.splice(index, 1);
        clearError();
        render();
        announce('Removed ' + control.controlId + '. ' + controlsCount(related.length) + '.');
        if (isOpen()) {
            renderOptions();
        }
        if (fromButton) {
            // Focus stays in the field: the next chip's button, else the previous one, else the search
            const buttons = chips.querySelectorAll('.sw-chip-remove');
            const next = buttons[Math.min(index, buttons.length - 1)];
            (next || search)?.focus();
        }
    }

    function add(option) {
        if (isSelected(option.id)) {
            return false;
        }
        related.push({ id: option.id, controlId: option.controlId, status: option.status || '' });
        clearError();
        render();
        announce('Added ' + option.controlId + '. ' + controlsCount(related.length) + '.');
        return true;
    }

    function toggle(option) {
        const index = related.findIndex((control) => idOf(control.id) === idOf(option.id));
        if (index >= 0) {
            removeAt(index, false);
        } else {
            add(option);
        }
    }

    // ------------------------------------------------------------------ the list

    function loadCandidates() {
        if (candidates) {
            return Promise.resolve(candidates);
        }
        if (!loading) {
            const controlId = field.dataset.controlId || '';
            loading = fetch('/api/controls/' + encodeURIComponent(controlId) + '/related-options')
                .then((response) => (response.ok ? response.json() : Promise.reject(new Error('HTTP ' + response.status))))
                .then((options) => {
                    candidates = (Array.isArray(options) ? options : []).filter((option) => option && option.id != null);
                    return candidates;
                })
                .catch((error) => {
                    console.error('Other Related Controls: could not load the controls', error);
                    loading = null;
                    return null;
                });
        }
        return loading;
    }

    // The text with every match of the query in <mark>, built as nodes (IDs and descriptions are not HTML)
    function highlighted(className, text, query) {
        const span = document.createElement('span');
        span.className = className;
        const value = String(text || '');
        if (!query) {
            span.textContent = value;
            return span;
        }
        const lower = value.toLowerCase();
        let from = 0;
        let at = lower.indexOf(query, from);
        while (at >= 0) {
            span.appendChild(document.createTextNode(value.slice(from, at)));
            const mark = document.createElement('mark');
            mark.textContent = value.slice(at, at + query.length);
            span.appendChild(mark);
            from = at + query.length;
            at = lower.indexOf(query, from);
        }
        span.appendChild(document.createTextNode(value.slice(from)));
        return span;
    }

    function query() {
        return search ? search.value.trim().toLowerCase() : '';
    }

    function message(text) {
        const item = document.createElement('li');
        item.className = 'sw-list-message';
        item.setAttribute('role', 'presentation');
        item.textContent = text;
        return item;
    }

    function optionElement(option, index, q) {
        const item = document.createElement('li');
        item.id = 'relatedControlsOption-' + index;
        item.className = 'sw-option';
        item.setAttribute('role', 'option');
        item.setAttribute('aria-selected', isSelected(option.id) ? 'true' : 'false');
        if (index === activeIndex) {
            item.classList.add('is-active');
        }

        const check = document.createElement('span');
        check.className = 'sw-check';
        check.setAttribute('aria-hidden', 'true');
        const text = document.createElement('span');
        text.className = 'sw-option-text';
        text.appendChild(highlighted('sw-option-name', option.controlId, q));
        const details = [option.status, option.description].filter(Boolean).join(' — ');
        if (details) {
            text.appendChild(highlighted('sw-option-mail', details, q));
        }
        item.append(check, text);

        // The search keeps the focus; a click adds or removes the control
        item.addEventListener('mousedown', (event) => event.preventDefault());
        item.addEventListener('click', () => {
            activeIndex = index;
            toggle(option);
            renderOptions();
            search.focus();
        });
        return item;
    }

    function matches(option, q) {
        return !q
            || String(option.controlId || '').toLowerCase().includes(q)
            || String(option.description || '').toLowerCase().includes(q);
    }

    function renderOptions() {
        if (!listbox) {
            return;
        }
        if (!candidates) {
            listbox.replaceChildren(message(loading ? 'Loading controls…' : 'The list of controls could not be loaded.'));
            setActive(-1);
            return;
        }
        const q = query();
        const all = candidates.filter((option) => matches(option, q));
        shown = all.slice(0, SHOWN_AT_MOST);
        if (activeIndex >= shown.length) {
            activeIndex = shown.length - 1;
        }
        if (shown.length === 0) {
            listbox.replaceChildren(message(q ? 'No control matches "' + search.value.trim() + '"' : 'There is no other control.'));
            setActive(-1);
            return;
        }
        const items = shown.map((option, index) => optionElement(option, index, q));
        if (all.length > shown.length) {
            items.push(message((all.length - shown.length) + ' more: type more of the Control ID'));
        }
        listbox.replaceChildren(...items);
        setActive(activeIndex);
    }

    function setActive(index) {
        activeIndex = index;
        typedActive = false;
        listbox?.querySelectorAll('.sw-option').forEach((option, i) => option.classList.toggle('is-active', i === index));
        if (index >= 0 && shown[index]) {
            const option = document.getElementById('relatedControlsOption-' + index);
            search.setAttribute('aria-activedescendant', 'relatedControlsOption-' + index);
            option?.scrollIntoView({ block: 'nearest' });
        } else {
            search?.removeAttribute('aria-activedescendant');
        }
    }

    function isOpen() {
        return Boolean(listbox && !listbox.hidden);
    }

    function open() {
        if (!editing || !listbox || isOpen()) {
            return;
        }
        listbox.hidden = false;
        search.setAttribute('aria-expanded', 'true');
        activeIndex = -1;
        // Started before the first render, so the list says "Loading controls…" meanwhile
        const loaded = candidates ? null : loadCandidates();
        renderOptions();
        if (loaded) {
            loaded.then(() => {
                if (isOpen()) {
                    renderOptions();
                    // Typed while the controls were loading: the first match is ready for Enter
                    activateFirstNew();
                }
            });
        }
    }

    function close() {
        if (!listbox || listbox.hidden) {
            return;
        }
        listbox.hidden = true;
        search.setAttribute('aria-expanded', 'false');
        setActive(-1);
    }

    // While typing, the first match not chosen yet is the one Enter adds
    function activateFirstNew() {
        const q = query();
        if (!q) {
            setActive(-1);
            return;
        }
        const index = shown.findIndex((option) => !isSelected(option.id));
        setActive(index >= 0 ? index : (shown.length ? 0 : -1));
        typedActive = true;
    }

    function onSearchKey(event) {
        switch (event.key) {
            case 'ArrowDown':
                event.preventDefault();
                if (!isOpen()) {
                    open();
                }
                if (shown.length) {
                    typedActive = false;
                    setActive(Math.min(activeIndex + 1, shown.length - 1));
                }
                break;
            case 'ArrowUp':
                event.preventDefault();
                if (isOpen() && shown.length) {
                    typedActive = false;
                    setActive(Math.max(activeIndex - 1, 0));
                }
                break;
            case 'Enter': {
                // Never the form's submit
                event.preventDefault();
                if (!isOpen()) {
                    open();
                    break;
                }
                const option = shown[activeIndex];
                if (!option) {
                    break;
                }
                const typed = query() !== '';
                if (typedActive && isSelected(option.id)) {
                    // Typed and Enter adds; no control is in the list twice
                    announce(option.controlId + ' is already in the list.');
                    break;
                }
                toggle(option);
                if (typed && isSelected(option.id)) {
                    // Added from a search: ready for the next one
                    search.value = '';
                    activeIndex = -1;
                }
                renderOptions();
                break;
            }
            case 'Escape':
                if (isOpen()) {
                    event.preventDefault();
                    event.stopPropagation();
                    close();
                }
                break;
            case 'Backspace':
                if (search.value === '' && related.length > 0) {
                    event.preventDefault();
                    removeAt(related.length - 1, false);
                }
                break;
            case 'Tab':
                close();
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ saving

    function keyOf(list) {
        return list.map((control) => idOf(control.id)).join(',');
    }

    function isChanged() {
        return keyOf(related) !== savedKey;
    }

    function clearError() {
        if (!errorBox || errorBox.hidden) {
            return;
        }
        errorBox.textContent = '';
        errorBox.hidden = true;
        field.classList.remove('has-error');
    }

    /**
     * A refusal of the Details save that names this field ("Other Related Controls: ..."), shown under it; the
     * controls stay as chosen. Returns whether the message was this field's.
     */
    function showServerError(text) {
        const match = /^\s*Other Related Controls:\s*(.*)$/.exec(String(text || ''));
        if (!field || !errorBox || !match) {
            return false;
        }
        errorBox.textContent = 'Not saved: ' + match[1];
        errorBox.hidden = false;
        field.classList.add('has-error');
        return true;
    }

    /** The controls as they are now were saved (the Details save went through). */
    function markSaved() {
        savedKey = keyOf(related);
        if (unsaved) {
            unsaved.hidden = true;
        }
    }

    /** What Save sends: the ids in their order while SoQM Admin Team edit; null keeps the stored links. */
    function idsForSave() {
        if (!field || !editing) {
            return null;
        }
        return related.map((control) => control.id);
    }

    // ------------------------------------------------------------------ setup and the page's calls

    function init() {
        field = document.getElementById('relatedControlsField');
        if (!field) {
            return;
        }
        chips = document.getElementById('relatedControlsChips');
        searchWrap = document.getElementById('relatedControlsSearch');
        search = document.getElementById('relatedControlsSearchInput');
        listbox = document.getElementById('relatedControlsListbox');
        emptyNote = document.getElementById('relatedControlsEmpty');
        count = document.getElementById('relatedControlsCount');
        status = document.getElementById('relatedControlsStatus');
        errorBox = document.getElementById('relatedControlsError');
        unsaved = document.getElementById('relatedControlsUnsaved');
        related = readRelated();
        savedKey = keyOf(related);

        // Leaving the page with controls added or removed and not saved
        window.addEventListener('beforeunload', (event) => {
            if (editing && isChanged()) {
                event.preventDefault();
                event.returnValue = '';
            }
        });

        if (search && listbox) {
            search.addEventListener('focus', open);
            search.addEventListener('click', open);
            search.addEventListener('input', () => {
                if (!isOpen()) {
                    open();
                }
                activeIndex = -1;
                renderOptions();
                activateFirstNew();
            });
            search.addEventListener('keydown', onSearchKey);
            // A click on the box (not on a chip's link or button) goes to the search
            document.getElementById('relatedControlsBox')?.addEventListener('click', (event) => {
                if (editing && !event.target.closest('button, a, input')) {
                    search.focus();
                }
            });
            document.addEventListener('click', (event) => {
                if (isOpen() && !field.contains(event.target)) {
                    close();
                }
            });
            field.addEventListener('focusout', (event) => {
                if (isOpen() && event.relatedTarget && !field.contains(event.relatedTarget)) {
                    close();
                }
            });
        }
        render();
    }

    /** Edit mode on or off; only SoQM Admin Team (data-editable) ever edits the field. */
    function setEditing(on) {
        if (!field) {
            return;
        }
        editing = Boolean(on) && field.dataset.editable === 'true' && Boolean(search);
        if (!editing) {
            clearError();
            close();
            if (search) {
                search.value = '';
            }
        }
        render();
    }

    function snapshot() {
        return related.map((control) => ({ ...control }));
    }

    function restore(saved) {
        if (!field || !Array.isArray(saved)) {
            return;
        }
        related = saved.map((control) => ({ ...control }));
        clearError();
        render();
    }

    return {
        init: init,
        setEditing: setEditing,
        snapshot: snapshot,
        restore: restore,
        isOpen: isOpen,
        close: close,
        showServerError: showServerError,
        markSaved: markSaved,
        isChanged: isChanged,
        idsForSave: idsForSave
    };
})();

document.addEventListener('DOMContentLoaded', () => RelatedControlsField.init());
