// View Control, SoQM only: the Move dialog (#soqmMoveModal) moves the control on or back for any role through
// POST /api/workflow/move. The server decides what is allowed (AccessPolicy.move) and checks the comment and
// the required fields again; the page only offers the moves it was given.
(function () {
    'use strict';

    const modalEl = document.getElementById('soqmMoveModal');
    if (!modalEl) {
        return;
    }
    const options = Array.from(modalEl.querySelectorAll('input[name="soqmMoveTarget"]'));
    const comment = document.getElementById('soqmMoveComment');
    const commentMark = document.getElementById('soqmMoveCommentMark');
    const note = document.getElementById('soqmMoveNote');
    const error = document.getElementById('soqmMoveError');
    const confirmBtn = document.getElementById('soqmMoveConfirmBtn');
    let sending = false;

    function selected() {
        return options.find(function (option) { return option.checked; }) || null;
    }

    function commentRequired() {
        const option = selected();
        return !!option && option.dataset.commentRequired === 'true';
    }

    function showError(message) {
        error.textContent = message || '';
        error.classList.toggle('d-none', !message);
    }

    function update() {
        const option = selected();
        const required = commentRequired();
        commentMark.classList.toggle('d-none', !required);
        comment.setAttribute('aria-required', required ? 'true' : 'false');
        if (!required) {
            comment.classList.remove('is-invalid');
        }
        if (option && option.dataset.onBehalf === 'true') {
            const assigned = option.dataset.assigned ? ' (' + option.dataset.assigned + ')' : '';
            note.textContent = 'You act for the ' + option.dataset.actingFor + assigned
                + '. The history names you and them; they get a copy of the notification.';
            note.classList.remove('d-none');
        } else {
            note.textContent = '';
            note.classList.add('d-none');
        }
        confirmBtn.disabled = !option || sending;
        confirmBtn.textContent = option && option.dataset.return === 'true' ? 'Return' : 'Move';
    }

    options.forEach(function (option) {
        option.addEventListener('change', function () {
            showError('');
            update();
        });
    });
    comment.addEventListener('input', function () {
        if (comment.value.trim()) {
            comment.classList.remove('is-invalid');
        }
    });

    modalEl.addEventListener('show.bs.modal', function () {
        options.forEach(function (option) { option.checked = false; });
        comment.value = '';
        comment.classList.remove('is-invalid');
        showError('');
        update();
    });

    confirmBtn.addEventListener('click', async function () {
        const option = selected();
        if (!option || sending) {
            return;
        }
        const text = comment.value.trim();
        if (commentRequired() && !text) {
            comment.classList.add('is-invalid');
            comment.focus();
            return;
        }
        const controlId = document.querySelector('input[name="id"]')?.value;
        const body = new URLSearchParams({ controlId: controlId, targetStatus: option.value });
        if (text) {
            body.append('comments', text);
        }
        sending = true;
        update();
        try {
            const response = await fetch('/api/workflow/move', {
                method: 'POST',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                body: body
            });
            const result = await response.json().catch(function () { return {}; });
            if (!response.ok || result.success === false) {
                showError(result.message || 'The control could not be moved (' + response.status + ').');
                return;
            }
            bootstrap.Modal.getInstance(modalEl)?.hide();
            showAppModal({
                variant: 'success',
                title: option.dataset.return === 'true' ? 'Control Returned' : 'Control Moved',
                message: result.message || 'The control was moved.',
                redirectUrl: window.location.pathname
            });
        } catch (e) {
            showError('The control could not be moved: ' + e.message);
        } finally {
            sending = false;
            update();
        }
    });
})();
