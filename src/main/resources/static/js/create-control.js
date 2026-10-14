// Create Control on View Control (business decision of 2026-10-14): the next cycle of a Monthly or Quarterly control,
// for SoQM Admin Team. The button shows on the Assignment tab only; the confirmation shows what the new control gets
// (GET /api/controls/{id}/next-cycle), its Control ID editable, and Create makes it (POST, the server checks again).
// After it is made the page opens the new control, which says "Created from <ID>".
(function () {
    const button = document.getElementById('createControlBtn');
    const modalEl = document.getElementById('createControlModal');
    if (!button || !modalEl || typeof bootstrap === 'undefined') {
        return;
    }
    const controlId = button.getAttribute('data-control-id');
    const url = '/api/controls/' + encodeURIComponent(controlId) + '/next-cycle';
    const el = id => document.getElementById(id);
    const createBtn = el('ccCreateBtn');
    const idInput = el('ccControlId');
    let problems = [];
    let busy = false;

    // Shown on the Assignment tab only (the bar is shared by all tabs)
    function syncVisibility() {
        const pane = document.getElementById('assignment-tab');
        button.hidden = !(pane && pane.classList.contains('active'));
    }
    document.addEventListener('shown.bs.tab', syncVisibility);
    syncVisibility();

    function show(id, visible) {
        el(id).hidden = !visible;
    }

    function fillList(listId, items) {
        const list = el(listId);
        list.replaceChildren();
        (items || []).forEach(text => {
            const li = document.createElement('li');
            li.textContent = text;
            list.appendChild(li);
        });
    }

    function reset() {
        problems = [];
        show('ccLoading', true);
        show('ccError', false);
        show('ccExisting', false);
        show('ccContent', false);
        show('ccProblems', false);
        createBtn.hidden = false;
        createBtn.disabled = true;
        createBtn.textContent = 'Create Control';
    }

    function showError(message) {
        show('ccLoading', false);
        el('ccError').textContent = message || 'Something went wrong. Reload the page and try again.';
        show('ccError', true);
    }

    function showExisting(data) {
        show('ccLoading', false);
        show('ccContent', false);
        el('ccExistingText').textContent = data.message || 'The next control already exists.';
        const link = el('ccExistingLink');
        if (data.existing && data.existing.url) {
            link.href = data.existing.url;
            link.textContent = 'Open ' + data.existing.controlId;
            link.hidden = false;
        } else {
            link.hidden = true;
        }
        show('ccExisting', true);
        createBtn.hidden = true;
    }

    function showProblems(title, items) {
        problems = items || [];
        if (title) {
            el('ccProblemsTitle').textContent = title;
        }
        fillList('ccProblemsList', problems);
        show('ccProblems', problems.length > 0);
        updateCreate();
    }

    function updateCreate() {
        createBtn.disabled = busy || problems.length > 0 || idInput.value.trim() === '';
    }

    async function readBody(response) {
        try {
            return await response.json();
        } catch (e) {
            return {};
        }
    }

    function sessionMessage(response, data) {
        if (response.status === 401) {
            return 'Your session has expired. Reload the page and sign in again.';
        }
        return data && data.message ? data.message : 'The server answered ' + response.status + '.';
    }

    function fill(plan) {
        el('ccSource').textContent = plan.sourceControlId;
        idInput.value = plan.proposedControlId || '';
        el('ccControlIdHint').textContent = plan.proposedControlId
            ? 'Proposed from ' + plan.sourceControlId + ': only the period moves on. You can change it; the Control ID must be unique (ignoring case).'
            : 'The Control ID of ' + plan.sourceControlId + ' is not in a form whose period moves on: enter the new Control ID. It must be unique (ignoring case).';
        el('ccOperationDate').textContent = plan.operationDate || 'Not set';
        el('ccDeadline').textContent = plan.deadline || 'Not set';
        el('ccNextDate').textContent = plan.nextOperationDate || 'Not set';
        el('ccSoqmYear').textContent = plan.soqmYear || 'Not set';
        if (plan.deadlineHint) {
            el('ccDeadlineHint').textContent = plan.deadlineHint.charAt(0).toLowerCase() + plan.deadlineHint.slice(1);
        }
        const people = el('ccPeople');
        people.replaceChildren();
        (plan.people || []).forEach(field => {
            const row = document.createElement('div');
            const dt = document.createElement('dt');
            dt.textContent = field.label;
            const dd = document.createElement('dd');
            dd.textContent = field.people && field.people.length ? field.people.join(', ') : '—';
            if (!field.people || !field.people.length) {
                dd.classList.add('cc-empty');
            }
            row.append(dt, dd);
            people.appendChild(row);
        });
        fillList('ccLeftOutList', plan.sharedWithLeftOut);
        show('ccLeftOut', (plan.sharedWithLeftOut || []).length > 0);
        fillList('ccNotCopied', plan.notCopied);
        show('ccLoading', false);
        show('ccContent', true);
        showProblems(null, plan.problems);
    }

    async function load() {
        reset();
        let response;
        try {
            response = await fetch(url, {headers: {'Accept': 'application/json'}, credentials: 'same-origin'});
        } catch (e) {
            showError('The server could not be reached. Try again.');
            return;
        }
        const data = await readBody(response);
        if (response.ok) {
            fill(data);
        } else if (response.status === 409) {
            showExisting(data);
        } else {
            showError(sessionMessage(response, data));
        }
    }

    async function create() {
        if (busy) {
            return;
        }
        busy = true;
        updateCreate();
        createBtn.textContent = 'Creating…';
        show('ccError', false);
        let response;
        try {
            response = await fetch(url, {
                method: 'POST',
                headers: {'Content-Type': 'application/json', 'Accept': 'application/json'},
                credentials: 'same-origin',
                body: JSON.stringify({controlId: idInput.value.trim()})
            });
        } catch (e) {
            busy = false;
            createBtn.textContent = 'Create Control';
            updateCreate();
            showError('The server could not be reached. Nothing was created; try again.');
            return;
        }
        const data = await readBody(response);
        if (response.ok && data.url) {
            window.location.href = data.url;
            return;
        }
        busy = false;
        createBtn.textContent = 'Create Control';
        if (response.status === 409 && data.existing && /already exists/.test(data.message || '')) {
            showExisting(data);
            return;
        }
        if (data.problems && data.problems.length) {
            showProblems(data.message, data.problems);
            return;
        }
        updateCreate();
        el('ccError').textContent = sessionMessage(response, data);
        show('ccError', true);
    }

    idInput.addEventListener('input', updateCreate);
    idInput.addEventListener('keydown', event => {
        if (event.key === 'Enter' && !createBtn.disabled) {
            event.preventDefault();
            create();
        }
    });
    createBtn.addEventListener('click', create);
    button.addEventListener('click', () => {
        bootstrap.Modal.getOrCreateInstance(modalEl).show();
        load();
    });
})();
