// Fields of several values on View Control (Details tab): Other Related Controls and IT Applications. The server renders
// the values as chips (plain text; one no longer in the list is marked "not in list") inside a [data-mv-field]. In edit
// mode, for whoever may change the field (data-editable), each chip gets a remove button and a search opens a list to
// choose from: the field's own options (a <template data-mv-options>) or a list the server searches (data-source-url,
// ?q=&limit=). Save sends the values with Details joined by "; " (ValueList); outside edit mode nothing is sent and the
// server keeps what is stored.
const MultiValueFields = (function () {
    'use strict';

    const SEPARATOR = ';';
    const JOINER = '; ';
    // How many matches the open list shows at most; typing narrows it
    const SHOWN_AT_MOST = 50;
    const SEARCH_DELAY_MS = 150;

    const fields = [];

    const keyOf = (value) => String(value || '').replace(/\s+/g, '').toLowerCase();

    function textSpan(className, text) {
        const span = document.createElement('span');
        span.className = className;
        span.textContent = text;
        return span;
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

    // The text with every match of the query in <mark>, built as nodes (the values are not HTML)
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

    function message(text) {
        const item = document.createElement('li');
        item.className = 'sw-list-message';
        item.setAttribute('role', 'presentation');
        item.textContent = text;
        return item;
    }

    function createField(root) {
        const part = (name) => root.querySelector('[data-mv-' + name + ']');
        const chips = part('chips');
        const box = part('box');
        const searchWrap = part('search');
        const search = searchWrap ? searchWrap.querySelector('input') : null;
        const listbox = part('listbox');
        const emptyNote = part('empty');
        const count = part('count');
        const status = part('status');
        const errorBox = part('error');
        const unsaved = part('unsaved');
        const optionsTemplate = part('options');

        const label = root.dataset.label || '';
        const unit = root.dataset.unit || 'value';
        const units = root.dataset.units || unit + 's';
        const max = parseInt(root.dataset.max, 10) || 0;
        const sourceUrl = root.dataset.sourceUrl || '';
        const emptyListText = root.dataset.emptyList || 'There is nothing to choose from yet.';
        const editable = root.dataset.editable === 'true' && Boolean(search && listbox);
        const staticOptions = optionsTemplate
            ? Array.from(optionsTemplate.content.querySelectorAll('[data-value]')).map((el) => el.dataset.value)
            : null;

        let values = [];          // { value, notInList } in the order of the field
        let shown = [];           // the options in the open list
        let activeIndex = -1;
        let typedActive = false;  // the active option was picked by typing, not by the arrows: Enter only adds it
        let editing = false;
        let savedKey = '';
        let listEmpty = null;     // the server's list had nothing at all (remote source)
        let loading = false;
        let failed = false;
        let more = false;         // the server has more matches than shown
        let requestNo = 0;
        let searchTimer = null;

        const has = (value) => values.some((item) => keyOf(item.value) === keyOf(value));
        const amount = (n) => n + ' ' + (n === 1 ? unit : units);

        // -------------------------------------------------------------- the chips

        function readValues() {
            return Array.from(chips.querySelectorAll('.mv-chip')).map((chip) => ({
                value: chip.dataset.value || '',
                notInList: chip.dataset.notInList === 'true'
            }));
        }

        function chipElement(item, index) {
            const chip = document.createElement('li');
            chip.className = 'sw-chip mv-chip' + (item.notInList ? ' is-not-in-list' : '');
            chip.dataset.value = item.value;
            chip.dataset.notInList = item.notInList ? 'true' : 'false';
            const text = document.createElement('span');
            text.className = 'sw-chip-text';
            text.appendChild(textSpan('sw-chip-name', item.value));
            if (item.notInList) {
                text.appendChild(textSpan('sw-note mv-not-in-list', 'not in list'));
            }
            chip.appendChild(text);
            if (editing) {
                const remove = document.createElement('button');
                remove.type = 'button';
                remove.className = 'sw-chip-remove';
                remove.setAttribute('aria-label', 'Remove ' + item.value);
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

        function render() {
            chips.replaceChildren(...values.map(chipElement));
            if (emptyNote) {
                emptyNote.hidden = values.length > 0;
            }
            if (count) {
                count.hidden = values.length === 0;
                count.textContent = amount(values.length);
            }
            root.classList.toggle('is-editing', editing);
            if (searchWrap) {
                searchWrap.hidden = !editing;
            }
            if (unsaved) {
                unsaved.hidden = !isChanged();
            }
        }

        function announce(text) {
            if (!status) {
                return;
            }
            status.textContent = '';
            // A fresh text node so screen readers read the same message twice
            window.setTimeout(() => { status.textContent = text; }, 30);
        }

        function removeAt(index, fromButton) {
            const item = values[index];
            if (!item) {
                return;
            }
            values.splice(index, 1);
            clearError();
            render();
            announce('Removed ' + item.value + '. ' + amount(values.length) + '.');
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

        function add(value) {
            if (has(value)) {
                announce(value + ' is already chosen.');
                return false;
            }
            if (max && values.length >= max) {
                showError('At most ' + amount(max) + '.');
                announce('At most ' + amount(max) + '.');
                return false;
            }
            values.push({ value: value, notInList: false });
            clearError();
            render();
            announce('Added ' + value + '. ' + amount(values.length) + '.');
            return true;
        }

        function toggle(value) {
            const index = values.findIndex((item) => keyOf(item.value) === keyOf(value));
            if (index >= 0) {
                removeAt(index, false);
            } else {
                add(value);
            }
        }

        // -------------------------------------------------------------- the list

        function query() {
            return search ? search.value.trim().toLowerCase() : '';
        }

        function fetchOptions() {
            if (!sourceUrl) {
                return;
            }
            const q = query();
            const number = ++requestNo;
            loading = true;
            failed = false;
            fetch(sourceUrl + '?q=' + encodeURIComponent(q) + '&limit=' + SHOWN_AT_MOST)
                .then((response) => (response.ok ? response.json() : Promise.reject(new Error('HTTP ' + response.status))))
                .then((list) => {
                    if (number !== requestNo) {
                        return;
                    }
                    const found = (Array.isArray(list) ? list : []).filter((value) => typeof value === 'string' && value);
                    if (!q) {
                        listEmpty = found.length === 0;
                    }
                    more = found.length >= SHOWN_AT_MOST;
                    shown = found;
                    loading = false;
                    if (isOpen()) {
                        renderOptions();
                        activateFirstNew();
                    }
                })
                .catch((error) => {
                    if (number !== requestNo) {
                        return;
                    }
                    console.error(label + ': could not load the list', error);
                    loading = false;
                    failed = true;
                    shown = [];
                    if (isOpen()) {
                        renderOptions();
                    }
                });
        }

        function optionElement(value, index, q) {
            const item = document.createElement('li');
            item.id = root.id + '-option-' + index;
            item.className = 'sw-option';
            item.setAttribute('role', 'option');
            item.setAttribute('aria-selected', has(value) ? 'true' : 'false');
            if (index === activeIndex) {
                item.classList.add('is-active');
            }
            const check = document.createElement('span');
            check.className = 'sw-check';
            check.setAttribute('aria-hidden', 'true');
            const text = document.createElement('span');
            text.className = 'sw-option-text';
            text.appendChild(highlighted('sw-option-name', value, q));
            item.append(check, text);

            // The search keeps the focus; a click adds or removes the value
            item.addEventListener('mousedown', (event) => event.preventDefault());
            item.addEventListener('click', () => {
                activeIndex = index;
                toggle(value);
                renderOptions();
                search.focus();
            });
            return item;
        }

        function renderOptions() {
            if (!listbox) {
                return;
            }
            const q = query();
            if (staticOptions) {
                const all = staticOptions.filter((value) => !q || value.toLowerCase().includes(q));
                shown = all.slice(0, SHOWN_AT_MOST);
                more = all.length > shown.length;
                listEmpty = staticOptions.length === 0;
            }
            if (activeIndex >= shown.length) {
                activeIndex = shown.length - 1;
            }
            if (loading && shown.length === 0) {
                listbox.replaceChildren(message('Loading…'));
                setActive(-1);
                return;
            }
            if (failed) {
                listbox.replaceChildren(message('The list could not be loaded. Try again later.'));
                setActive(-1);
                return;
            }
            if (shown.length === 0) {
                listbox.replaceChildren(message(listEmpty || !q ? emptyListText
                    : 'Nothing matches "' + search.value.trim() + '"'));
                setActive(-1);
                return;
            }
            const items = shown.map((value, index) => optionElement(value, index, q));
            if (more) {
                items.push(message('Type more to see the others'));
            }
            listbox.replaceChildren(...items);
            setActive(activeIndex);
        }

        function setActive(index) {
            activeIndex = index;
            typedActive = false;
            listbox?.querySelectorAll('.sw-option').forEach((option, i) => option.classList.toggle('is-active', i === index));
            if (index >= 0 && shown[index] !== undefined) {
                const id = root.id + '-option-' + index;
                search.setAttribute('aria-activedescendant', id);
                document.getElementById(id)?.scrollIntoView({ block: 'nearest' });
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
            if (sourceUrl) {
                fetchOptions();
            }
            renderOptions();
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
            if (!query()) {
                setActive(-1);
                return;
            }
            const index = shown.findIndex((value) => !has(value));
            setActive(index >= 0 ? index : (shown.length ? 0 : -1));
            typedActive = true;
        }

        function onInput() {
            if (!isOpen()) {
                open();
            }
            activeIndex = -1;
            if (sourceUrl) {
                window.clearTimeout(searchTimer);
                loading = true;
                searchTimer = window.setTimeout(fetchOptions, SEARCH_DELAY_MS);
                return;
            }
            renderOptions();
            activateFirstNew();
        }

        function onKey(event) {
            switch (event.key) {
                case 'ArrowDown':
                    event.preventDefault();
                    if (!isOpen()) {
                        open();
                    }
                    if (shown.length) {
                        setActive(Math.min(activeIndex + 1, shown.length - 1));
                    }
                    break;
                case 'ArrowUp':
                    event.preventDefault();
                    if (isOpen() && shown.length) {
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
                    const value = shown[activeIndex];
                    if (value === undefined) {
                        break;
                    }
                    if (typedActive && has(value)) {
                        // Typed and Enter adds; no value is chosen twice
                        announce(value + ' is already chosen.');
                        break;
                    }
                    const typed = query() !== '';
                    toggle(value);
                    if (typed && has(value)) {
                        // Added from a search: ready for the next one
                        search.value = '';
                        activeIndex = -1;
                        if (sourceUrl) {
                            fetchOptions();
                        }
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
                    if (search.value === '' && values.length > 0) {
                        event.preventDefault();
                        removeAt(values.length - 1, false);
                    }
                    break;
                case 'Tab':
                    close();
                    break;
                default:
                    if (event.key === SEPARATOR) {
                        // ";" separates the values: it is never part of one
                        event.preventDefault();
                    }
                    break;
            }
        }

        // -------------------------------------------------------------- saving

        function keyOfValues(list) {
            return list.map((item) => keyOf(item.value)).join(SEPARATOR);
        }

        function isChanged() {
            return keyOfValues(values) !== savedKey;
        }

        function showError(text) {
            if (!errorBox) {
                return;
            }
            errorBox.textContent = text;
            errorBox.hidden = false;
            root.classList.add('has-error');
        }

        function clearError() {
            if (!errorBox || errorBox.hidden) {
                return;
            }
            errorBox.textContent = '';
            errorBox.hidden = true;
            root.classList.remove('has-error');
        }

        // ------------------------------------------------------------- setup

        values = readValues();
        savedKey = keyOfValues(values);
        if (editable) {
            search.addEventListener('focus', open);
            search.addEventListener('click', open);
            search.addEventListener('input', onInput);
            search.addEventListener('keydown', onKey);
            // A click on the box (not on a chip's button) goes to the search
            box?.addEventListener('click', (event) => {
                if (editing && !event.target.closest('button, a, input')) {
                    search.focus();
                }
            });
            // A click elsewhere closes the list. The path is taken as the click went: a clicked option is already
            // replaced by the new list when this runs, so it is no longer inside the field
            document.addEventListener('click', (event) => {
                if (isOpen() && !event.composedPath().includes(root)) {
                    close();
                }
            });
            root.addEventListener('focusout', (event) => {
                if (isOpen() && event.relatedTarget && !root.contains(event.relatedTarget)) {
                    close();
                }
            });
        }
        render();

        return {
            root: root,
            label: label,
            setEditing(on) {
                editing = Boolean(on) && editable;
                if (!editing) {
                    clearError();
                    close();
                    if (search) {
                        search.value = '';
                    }
                }
                render();
            },
            snapshot: () => values.map((item) => ({ ...item })),
            restore(saved) {
                if (!Array.isArray(saved)) {
                    return;
                }
                values = saved.map((item) => ({ ...item }));
                clearError();
                render();
            },
            isOpen: isOpen,
            close: close,
            isEditing: () => editing,
            isChanged: isChanged,
            showError: showError,
            markSaved() {
                savedKey = keyOfValues(values);
                if (unsaved) {
                    unsaved.hidden = true;
                }
            },
            valueForSave: () => (editing ? values.map((item) => item.value).join(JOINER) : null)
        };
    }

    function init() {
        document.querySelectorAll('[data-mv-field]').forEach((root) => fields.push(createField(root)));
        // Leaving the page with values added or removed and not saved
        window.addEventListener('beforeunload', (event) => {
            if (fields.some((field) => field.isEditing() && field.isChanged())) {
                event.preventDefault();
                event.returnValue = '';
            }
        });
    }

    const byId = (id) => fields.find((field) => field.root.id === id) || null;

    return {
        init: init,
        /** Edit mode on or off for every field; only a field the user may change (data-editable) is ever edited. */
        setEditing: (on) => fields.forEach((field) => field.setEditing(on)),
        snapshot: () => Object.fromEntries(fields.map((field) => [field.root.id, field.snapshot()])),
        restore(saved) {
            if (saved) {
                fields.forEach((field) => field.restore(saved[field.root.id]));
            }
        },
        isOpen: () => fields.some((field) => field.isOpen()),
        close: () => fields.forEach((field) => field.close()),
        /**
         * A refusal of the Details save that names one of the fields ("Other Related Controls: ..."), shown under it;
         * the values stay as chosen. Returns whether the message was a field's.
         */
        showServerError(text) {
            const message = String(text || '');
            const field = fields.find((f) => f.label && message.trim().startsWith(f.label + ':'));
            if (!field) {
                return false;
            }
            field.showError('Not saved: ' + message.trim().slice(field.label.length + 1).trim());
            return true;
        },
        /** The values as they are now were saved (the Details save went through). */
        markSaved: () => fields.forEach((field) => field.markSaved()),
        /** What Save sends for the field: its values joined by "; " in edit mode; null keeps what is stored. */
        valueForSave: (id) => byId(id)?.valueForSave() ?? null
    };
})();

document.addEventListener('DOMContentLoaded', () => MultiValueFields.init());
