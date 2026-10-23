// Create Control on View Control (business decision of 2026-10-14): the next cycle of a Monthly or Quarterly control,
// for SoQM Admin Team. The button is in the bar of every tab (Control, Details, Assignment, Documents; business decision
// of 2026-10-15) and gone in edit mode with the bar; the confirmation shows what the new control gets
// (GET /api/controls/{id}/next-cycle), its Control ID editable, and Create makes it (POST, the server checks again).
// After it is made the page opens the new control, which says "Created from <ID>".
// A next cycle already there does not stop it (business decision of 2026-10-21): the confirmation opens as always and
// says so; a Control ID that is already a control's is named under the field as it is typed (GET .../id-check), and
// Create waits for another one.
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
    const CHECK_DELAY_MS = 300;
    let problems = [];
    let busy = false;
    // The ID in the field is a control's already (Create waits), or is being checked
    let taken = false;
    let checking = false;
    let checkTimer = null;
    let checkSeq = 0;

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
        clearTimeout(checkTimer);
        checkSeq++;
        checking = false;
        setTaken(null);
        show('ccLoading', true);
        show('ccError', false);
        show('ccContent', false);
        show('ccProblems', false);
        showExistingNext(null);
        createBtn.hidden = false;
        createBtn.disabled = true;
        createBtn.textContent = 'Create Control';
    }

    function showError(message) {
        show('ccLoading', false);
        el('ccError').textContent = message || 'Something went wrong. Reload the page and try again.';
        show('ccError', true);
    }

    // The next cycle already made (the note) and the control whose ID is in the field (the red hint)
    let existingNext = null;
    let takenControl = null;

    /** The control that already has the ID in the field (null: none); the hint under the field says so. */
    function setTaken(existing) {
        takenControl = existing || null;
        taken = !!existing;
        idInput.classList.toggle('is-invalid', taken);
        idInput.setAttribute('aria-invalid', taken ? 'true' : 'false');
        if (existing) {
            el('ccIdTakenId').textContent = existing.controlId;
            el('ccIdTakenLink').href = existing.url;
        }
        show('ccIdTaken', taken);
        refreshNote();
        updateCreate();
    }

    function showExistingNext(existing) {
        existingNext = existing || null;
        if (existing) {
            const link = el('ccExistingNextLink');
            link.textContent = existing.controlId;
            link.href = existing.url;
        }
        refreshNote();
    }

    /** The note, unless the red hint already names the same control. */
    function refreshNote() {
        show('ccExistingNext', !!existingNext && !(takenControl && takenControl.id === existingNext.id));
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
        createBtn.disabled = busy || checking || taken || problems.length > 0 || idInput.value.trim() === '';
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

    /** Whether the ID in the field is already a control's: asked a moment after the last key, the latest answer counts. */
    function checkId() {
        clearTimeout(checkTimer);
        const typed = idInput.value.trim();
        const seq = ++checkSeq;
        if (typed === '') {
            checking = false;
            setTaken(null);
            return;
        }
        checking = true;
        updateCreate();
        checkTimer = setTimeout(async () => {
            try {
                const response = await fetch(url + '/id-check?controlId=' + encodeURIComponent(typed),
                    {headers: {'Accept': 'application/json'}, credentials: 'same-origin'});
                const data = await readBody(response);
                if (seq !== checkSeq) {
                    return;
                }
                checking = false;
                setTaken(response.ok && data.taken ? data.existing : null);
            } catch (e) {
                if (seq === checkSeq) {
                    // The server checks again on Create
                    checking = false;
                    setTaken(null);
                }
            }
        }, CHECK_DELAY_MS);
    }

    function fill(plan) {
        el('ccSource').textContent = plan.sourceControlId;
        idInput.value = plan.proposedControlId || '';
        el('ccControlIdHint').textContent = plan.proposedControlId
            ? 'Proposed from ' + plan.sourceControlId + ': only the period moves on. You can change it; the Control ID must be unique (ignoring case).'
            : (plan.noProposalReason || 'No Control ID can be proposed from ' + plan.sourceControlId + '.')
                + ' Enter the new Control ID; it must be unique (ignoring case).';
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
            if (field.required && (!field.people || !field.people.length)) {
                dd.classList.add('cc-empty');
            }
            row.append(dt, dd);
            people.appendChild(row);
        });
        fillList('ccLeftOutList', plan.sharedWithLeftOut);
        show('ccLeftOut', (plan.sharedWithLeftOut || []).length > 0);
        fillList('ccNotCopied', plan.notCopied);
        showExistingNext(plan.existingNext);
        setTaken(plan.proposedTaken);
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
        // The ID was taken meanwhile (someone else, or a second click): the hint under the field, the form stays
        if (response.status === 409 && data.existing) {
            setTaken(data.existing);
            idInput.focus();
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

    idInput.addEventListener('input', checkId);
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
