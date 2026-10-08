// Control Shared With on View Control (Assignment tab). The server renders the people as chips (name, e-mail and,
// for SoQM Team, a mark such as "Disabled"); what a place in the field gives a person is AccessPolicy.sharedAccess,
// never worked out here. For SoQM Team in edit mode this adds a remove button to each chip and a search with a list
// of the people the field accepts (/api/users/role/SHARED_WITH: enabled users only). The chosen addresses go to
// #controlSharedWithHidden as a JSON array, which the Assignment save sends.
const SharedWithField = (function () {
    'use strict';

    // More chips than this are folded behind "Show all N" while the field is only shown
    const FOLDED_COUNT = 6;

    let field = null;
    let chips = null;
    let searchWrap = null;
    let search = null;
    let listbox = null;
    let emptyNote = null;
    let count = null;
    let more = null;
    let hidden = null;
    let status = null;
    let errorBox = null;
    let unsaved = null;

    let people = [];          // { mail, name, access, note, refusal } in the order of the field
    let candidates = null;    // the people the picker offers, loaded once
    let loading = null;
    let shown = [];           // the candidates in the open list
    let activeIndex = -1;
    let typedActive = false;  // the active option was picked by typing, not by the arrows: Enter only adds it
    let editing = false;
    let expanded = false;
    let notesShown = false;
    let savedKey = '';        // the addresses as last saved, to tell an unsaved change

    const key = (mail) => String(mail || '').trim().toLowerCase();

    function isSelected(mail) {
        const wanted = key(mail);
        return people.some((person) => key(person.mail) === wanted);
    }

    // ------------------------------------------------------------------ the chips

    function readPeople() {
        return Array.from(chips.querySelectorAll('.sw-chip')).map((chip) => ({
            mail: chip.dataset.mail || '',
            name: chip.dataset.name || chip.dataset.mail || '',
            access: chip.dataset.access || '',
            note: chip.dataset.note || '',
            refusal: chip.dataset.refusal || ''
        }));
    }

    function accessClass(access) {
        return access ? 'is-' + access.toLowerCase().replace(/_/g, '-') : '';
    }

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

    function chipElement(person, index) {
        const chip = document.createElement('li');
        chip.className = 'sw-chip';
        if (notesShown && person.access) {
            chip.classList.add(accessClass(person.access));
        }
        if (notesShown && person.refusal) {
            chip.classList.add('is-refused');
        }
        chip.dataset.mail = person.mail;
        chip.dataset.name = person.name;
        if (notesShown) {
            chip.dataset.access = person.access || '';
            chip.dataset.note = person.note || '';
            chip.dataset.refusal = person.refusal || '';
        }

        const text = document.createElement('span');
        text.className = 'sw-chip-text';
        text.appendChild(textSpan('sw-chip-name', person.name));
        if (key(person.name) !== key(person.mail)) {
            text.appendChild(textSpan('sw-chip-mail', person.mail));
        }
        if (notesShown && person.note) {
            text.appendChild(textSpan('sw-note', person.note));
        }
        chip.appendChild(text);

        if (editing) {
            const remove = document.createElement('button');
            remove.type = 'button';
            remove.className = 'sw-chip-remove';
            remove.setAttribute('aria-label', 'Remove ' + person.name);
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

    function peopleCount(n) {
        return n === 1 ? '1 person' : n + ' people';
    }

    function writeHidden() {
        hidden.value = JSON.stringify(people.map((person) => person.mail));
        // Save sends the field only once it holds what the page shows (see saveAssignmentData)
        hidden.dataset.ready = 'true';
    }

    function render() {
        chips.replaceChildren(...people.map(chipElement));
        emptyNote.hidden = people.length > 0;
        count.hidden = people.length === 0;
        count.textContent = peopleCount(people.length);

        const foldable = !editing && people.length > FOLDED_COUNT;
        field.classList.toggle('is-collapsed', foldable && !expanded);
        if (more) {
            more.hidden = !foldable;
            more.textContent = expanded ? 'Show fewer' : 'Show all ' + people.length;
            more.setAttribute('aria-expanded', expanded ? 'true' : 'false');
        }

        field.classList.toggle('is-editing', editing);
        if (searchWrap) {
            searchWrap.hidden = !editing;
        }
        if (unsaved) {
            unsaved.hidden = !isChanged();
        }
        writeHidden();
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
        const person = people[index];
        if (!person) {
            return;
        }
        people.splice(index, 1);
        clearError();
        render();
        announce('Removed ' + person.name + '. ' + peopleCount(people.length) + '.');
        if (listbox && !listbox.hidden) {
            renderOptions();
        }
        if (fromButton) {
            // Focus stays in the field: the next chip's button, else the previous one, else the search
            const buttons = chips.querySelectorAll('.sw-chip-remove');
            const next = buttons[Math.min(index, buttons.length - 1)];
            (next || search)?.focus();
        }
    }

    function nameOf(user) {
        return user.displayName && user.displayName.trim() ? user.displayName.trim() : user.mail;
    }

    function add(user) {
        if (isSelected(user.mail)) {
            return false;
        }
        const name = nameOf(user);
        people.push({
            mail: user.mail,
            name: name,
            access: user.sharedAccess || '',
            note: user.sharedNote || '',
            refusal: ''
        });
        clearError();
        render();
        announce('Added ' + name + '. ' + peopleCount(people.length) + '.');
        return true;
    }

    function toggle(user) {
        const index = people.findIndex((person) => key(person.mail) === key(user.mail));
        if (index >= 0) {
            removeAt(index, false);
        } else {
            add(user);
        }
    }

    // ------------------------------------------------------------------ the list

    function loadCandidates() {
        if (candidates) {
            return Promise.resolve(candidates);
        }
        if (!loading) {
            const controlId = field.dataset.controlId || '';
            loading = fetch('/api/users/role/SHARED_WITH?controlId=' + encodeURIComponent(controlId))
                .then((response) => (response.ok ? response.json() : Promise.reject(new Error('HTTP ' + response.status))))
                .then((users) => {
                    // The server offers enabled users only; one entry per address
                    const seen = new Set();
                    candidates = (Array.isArray(users) ? users : [])
                        .filter((user) => user && user.mail && user.enabled !== false)
                        .filter((user) => !seen.has(key(user.mail)) && seen.add(key(user.mail)))
                        .sort((a, b) => String(a.displayName || a.mail).localeCompare(String(b.displayName || b.mail)));
                    return candidates;
                })
                .catch((error) => {
                    console.error('Shared With: could not load the people', error);
                    loading = null;
                    return null;
                });
        }
        return loading;
    }

    // The text with every match of the query in <mark>, built as nodes (names and addresses are not HTML)
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

    function optionElement(user, index, q) {
        const option = document.createElement('li');
        option.id = 'sharedWithOption-' + index;
        option.className = 'sw-option';
        option.setAttribute('role', 'option');
        option.setAttribute('aria-selected', isSelected(user.mail) ? 'true' : 'false');
        if (user.sharedAccess) {
            option.classList.add(accessClass(user.sharedAccess));
        }
        if (index === activeIndex) {
            option.classList.add('is-active');
        }

        const check = document.createElement('span');
        check.className = 'sw-check';
        check.setAttribute('aria-hidden', 'true');
        const text = document.createElement('span');
        text.className = 'sw-option-text';
        const name = nameOf(user);
        text.appendChild(highlighted('sw-option-name', name, q));
        if (key(name) !== key(user.mail)) {
            text.appendChild(highlighted('sw-option-mail', user.mail, q));
        }
        if (user.sharedNote) {
            text.appendChild(textSpan('sw-note', user.sharedNote));
        }
        option.append(check, text);

        // The search keeps the focus; a click adds or removes the person
        option.addEventListener('mousedown', (event) => event.preventDefault());
        option.addEventListener('click', () => {
            activeIndex = index;
            toggle(user);
            renderOptions();
            search.focus();
        });
        return option;
    }

    function renderOptions() {
        if (!listbox) {
            return;
        }
        if (!candidates) {
            listbox.replaceChildren(message(loading ? 'Loading people…' : 'The list of people could not be loaded.'));
            setActive(-1);
            return;
        }
        const q = query();
        shown = candidates.filter((user) => !q
            || String(user.displayName || '').toLowerCase().includes(q)
            || String(user.mail || '').toLowerCase().includes(q));
        if (activeIndex >= shown.length) {
            activeIndex = shown.length - 1;
        }
        if (shown.length === 0) {
            listbox.replaceChildren(message(q ? 'Nobody matches "' + search.value.trim() + '"' : 'There is nobody to add.'));
            setActive(-1);
            return;
        }
        listbox.replaceChildren(...shown.map((user, index) => optionElement(user, index, q)));
        setActive(activeIndex);
    }

    function setActive(index) {
        activeIndex = index;
        typedActive = false;
        listbox?.querySelectorAll('.sw-option').forEach((option, i) => option.classList.toggle('is-active', i === index));
        if (index >= 0 && shown[index]) {
            const option = document.getElementById('sharedWithOption-' + index);
            search.setAttribute('aria-activedescendant', 'sharedWithOption-' + index);
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
        // Started before the first render, so the list says "Loading people…" meanwhile
        const loaded = candidates ? null : loadCandidates();
        renderOptions();
        if (loaded) {
            loaded.then(() => {
                if (isOpen()) {
                    renderOptions();
                    // Typed while the people were loading: the first match is ready for Enter
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
        const index = shown.findIndex((user) => !isSelected(user.mail));
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
                const user = shown[activeIndex];
                if (!user) {
                    break;
                }
                const typed = query() !== '';
                if (typedActive && isSelected(user.mail)) {
                    // Typed and Enter adds; nobody is in the list twice
                    announce(nameOf(user) + ' is already in the list.');
                    break;
                }
                toggle(user);
                if (typed && isSelected(user.mail)) {
                    // Added from a search: ready for the next name
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
                if (search.value === '' && people.length > 0) {
                    event.preventDefault();
                    removeAt(people.length - 1, false);
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
        return list.map((person) => key(person.mail)).join(',');
    }

    function isChanged() {
        return keyOf(people) !== savedKey;
    }

    function chipOf(mail) {
        const wanted = key(mail);
        return Array.from(chips.querySelectorAll('.sw-chip')).find((chip) => key(chip.dataset.mail) === wanted) || null;
    }

    function showError(message, mails) {
        if (!errorBox) {
            return;
        }
        errorBox.textContent = message;
        errorBox.hidden = false;
        field.classList.add('has-error');
        chips.querySelectorAll('.sw-chip.is-invalid').forEach((chip) => chip.classList.remove('is-invalid'));
        (mails || []).forEach((mail) => chipOf(mail)?.classList.add('is-invalid'));
        if (search) {
            search.setAttribute('aria-invalid', 'true');
            search.setAttribute('aria-describedby', 'sharedWithError sharedWithKeys');
        }
    }

    function clearError() {
        if (!errorBox || errorBox.hidden) {
            return;
        }
        errorBox.textContent = '';
        errorBox.hidden = true;
        field.classList.remove('has-error');
        chips.querySelectorAll('.sw-chip.is-invalid').forEach((chip) => chip.classList.remove('is-invalid'));
        if (search) {
            search.removeAttribute('aria-invalid');
            search.setAttribute('aria-describedby', 'sharedWithKeys');
        }
    }

    /**
     * Before Save sends anything: the people the server would refuse in this field (data-refusal, from
     * AccessPolicy.assignmentRefusal) are named under it. Returns the message, or null when the field can be saved.
     */
    function validate() {
        if (!field || !editing) {
            return null;
        }
        const refused = people.filter((person) => person.refusal);
        if (refused.length === 0) {
            clearError();
            return null;
        }
        const reasons = refused.map((person) => person.mail + ' ' + person.refusal).join('; ');
        const message = 'Remove before saving: ' + reasons + '.';
        showError(message, refused.map((person) => person.mail));
        return message;
    }

    /**
     * A refusal of the Assignment save that names this field ("Control Shared With: <mail> <reason>"), shown
     * under it; the people stay as chosen. Returns whether the message was this field's.
     */
    function showServerError(text) {
        const match = /^\s*Control Shared With:\s*(\S+)\s+(.*)$/.exec(String(text || ''));
        if (!field || !match) {
            return false;
        }
        showError('Not saved: ' + match[1] + ' ' + match[2].replace(/\.$/, '') + '.', [match[1]]);
        return true;
    }

    /** The people as they are now were saved (the Assignment save went through). */
    function markSaved() {
        savedKey = keyOf(people);
        if (unsaved) {
            unsaved.hidden = true;
        }
    }

    function focusTarget() {
        return search && editing ? search : field;
    }

    // ------------------------------------------------------------------ setup and the page's calls

    function init() {
        field = document.getElementById('sharedWithField');
        if (!field) {
            return;
        }
        chips = document.getElementById('sharedWithChips');
        searchWrap = document.getElementById('sharedWithSearch');
        search = document.getElementById('sharedWithSearchInput');
        listbox = document.getElementById('sharedWithListbox');
        emptyNote = document.getElementById('sharedWithEmpty');
        count = document.getElementById('sharedWithCount');
        more = document.getElementById('sharedWithMore');
        hidden = document.getElementById('controlSharedWithHidden');
        status = document.getElementById('sharedWithStatus');
        errorBox = document.getElementById('sharedWithError');
        unsaved = document.getElementById('sharedWithUnsaved');
        notesShown = field.dataset.notes === 'true';
        people = readPeople();
        savedKey = keyOf(people);

        // Leaving the page with people added or removed and not saved
        window.addEventListener('beforeunload', (event) => {
            if (editing && isChanged()) {
                event.preventDefault();
                event.returnValue = '';
            }
        });

        more?.addEventListener('click', () => {
            expanded = !expanded;
            render();
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
            // A click on the box (not on a chip's button) goes to the search
            document.getElementById('sharedWithBox')?.addEventListener('click', (event) => {
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

    /** Edit mode on or off; only SoQM Team (data-editable) ever edits the field. */
    function setEditing(on) {
        if (!field) {
            return;
        }
        editing = Boolean(on) && field.dataset.editable === 'true' && Boolean(search);
        expanded = false;
        if (!editing) {
            clearError();
            close();
            if (search) {
                search.value = '';
            }
        }
        render();
    }

    function mails() {
        return people.map((person) => person.mail);
    }

    function snapshot() {
        return people.map((person) => ({ ...person }));
    }

    function restore(saved) {
        if (!field || !Array.isArray(saved)) {
            return;
        }
        people = saved.map((person) => ({ ...person }));
        clearError();
        render();
    }

    return {
        init: init,
        setEditing: setEditing,
        mails: mails,
        snapshot: snapshot,
        restore: restore,
        isOpen: isOpen,
        close: close,
        validate: validate,
        showServerError: showServerError,
        markSaved: markSaved,
        isChanged: isChanged,
        focusTarget: focusTarget
    };
})();

document.addEventListener('DOMContentLoaded', () => SharedWithField.init());
