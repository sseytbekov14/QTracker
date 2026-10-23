// View Control, SoQM only: the Move dialog (#soqmMoveModal) moves the control on or back for any role through
// POST /api/workflow/move. The server decides what is allowed (AccessPolicy.move) and checks the comment and
// the required fields again; the page only offers the moves it was given. At SoQM's own step (SoQM Head Review) the
// dialog opens from the Return menu with its one move chosen.
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
        confirmBtn.textContent = presetReturn ? 'Return Control'
            : option && option.dataset.return === 'true' ? 'Return' : 'Move';
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

    // Opened from the Return menu (data-soqm-move-target): that one move, its own title and question, no list
    const title = document.getElementById('soqmMoveTitle');
    const titleText = title.textContent;
    const intro = document.getElementById('soqmMoveIntro');
    const presetIntro = document.getElementById('soqmMovePresetIntro');
    const targets = document.getElementById('soqmMoveTargets');
    // ...and it looks like the other return dialogs: the return icon, "Reason for the return", an amber Return Control
    const icon = modalEl.querySelector('.vc-modal-icon');
    const iconGlyph = icon ? icon.querySelector('.bi') : null;
    const commentLabel = document.getElementById('soqmMoveCommentLabel');
    const commentPlaceholder = comment.getAttribute('placeholder');
    let presetReturn = false;

    function setReturnLook(on) {
        presetReturn = on;
        modalEl.classList.toggle('vc-move-return', on);
        if (icon) {
            icon.classList.toggle('vc-modal-icon-return', on);
            icon.classList.toggle('vc-modal-icon-send', !on);
        }
        if (iconGlyph) {
            iconGlyph.className = on ? 'bi bi-arrow-return-left' : 'bi bi-arrow-left-right';
        }
        commentLabel.textContent = on ? 'Reason for the return' : 'Comment';
        comment.setAttribute('placeholder', on ? 'What needs to change' : commentPlaceholder);
        confirmBtn.classList.toggle('btn-warning', on);
        confirmBtn.classList.toggle('btn-primary', !on);
    }

    modalEl.addEventListener('show.bs.modal', function (event) {
        const opener = event.relatedTarget;
        const preset = opener && opener.dataset ? opener.dataset.soqmMoveTarget || '' : '';
        const presetOption = preset ? options.find(function (option) { return option.value === preset; }) : null;
        setReturnLook(!!presetOption && presetOption.dataset.return === 'true');
        options.forEach(function (option) { option.checked = option === presetOption; });
        targets.hidden = !!presetOption;
        intro.hidden = !!presetOption;
        presetIntro.hidden = !presetOption;
        presetIntro.textContent = presetOption ? opener.dataset.soqmMoveIntro || '' : '';
        title.textContent = presetOption ? opener.dataset.soqmMoveTitle || titleText : titleText;
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
