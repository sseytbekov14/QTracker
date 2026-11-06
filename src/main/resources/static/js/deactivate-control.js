// Deactivate control (2026-11-06): the confirmation dialog of every button marked data-deactivate-control (View
// Control's More actions, the Performance Cycle page). It names the control, and its Deactivate button stays disabled
// until the user types the Control ID - any case, spaces at either end ignored, as AccessPolicy.deactivationIdMatches,
// which the server checks again. Only SoQM Admin Team get the buttons; the server refuses everyone else.
// Texts go in with textContent only.
(function () {
    'use strict';

    /** The same rule as AccessPolicy.deactivationIdMatches. */
    function matches(controlId, typed) {
        if (!controlId || !controlId.trim() || typed === null || typed === undefined) {
            return false;
        }
        return controlId.trim().toUpperCase() === String(typed).trim().toUpperCase();
    }

    function el(tag, className, text) {
        const node = document.createElement(tag);
        if (className) {
            node.className = className;
        }
        if (text !== undefined) {
            node.textContent = text;
        }
        return node;
    }

    let dialog = null;

    function build() {
        dialog = el('dialog', 'qt-deactivate-dialog');
        dialog.setAttribute('aria-labelledby', 'qtDeactivateTitle');
        dialog.setAttribute('aria-describedby', 'qtDeactivateText');

        const form = el('form', 'qt-deactivate-form');
        form.method = 'dialog';
        form.noValidate = true;

        const title = el('h2', 'qt-deactivate-title', 'Deactivate control');
        title.id = 'qtDeactivateTitle';
        const idLine = el('p', 'qt-deactivate-id');
        idLine.append('Control ID: ', el('strong', 'qt-deactivate-code'));
        const text = el('p', 'qt-deactivate-text', 'This deletes the control for good: its fields, people, workflow '
            + 'history, comments, notifications and its files. It cannot be undone; the Admin Panel keeps a record.');
        text.id = 'qtDeactivateText';

        const label = el('label', 'qt-deactivate-label', 'Type the Control ID to confirm');
        label.htmlFor = 'qtDeactivateInput';
        const input = el('input', 'form-control qt-deactivate-input');
        input.id = 'qtDeactivateInput';
        input.type = 'text';
        input.autocomplete = 'off';
        input.spellcheck = false;

        const error = el('p', 'qt-deactivate-error');
        error.setAttribute('role', 'alert');
        error.hidden = true;

        const actions = el('div', 'qt-deactivate-actions');
        const cancel = el('button', 'btn btn-outline-secondary', 'Cancel');
        cancel.type = 'button';
        cancel.value = 'cancel';
        const confirm = el('button', 'btn btn-danger qt-deactivate-confirm', 'Deactivate');
        confirm.type = 'submit';
        confirm.disabled = true;
        actions.append(cancel, confirm);

        form.append(title, idLine, text, label, input, error, actions);
        dialog.append(form);
        document.body.append(dialog);

        input.addEventListener('input', () => {
            confirm.disabled = !matches(dialog.dataset.controlId, input.value) || dialog.dataset.busy === 'true';
            error.hidden = true;
        });
        cancel.addEventListener('click', () => dialog.close());
        form.addEventListener('submit', (event) => {
            event.preventDefault();
            if (!matches(dialog.dataset.controlId, input.value) || dialog.dataset.busy === 'true') {
                return;
            }
            submit(input.value, confirm, error);
        });
    }

    async function submit(typed, confirm, error) {
        dialog.dataset.busy = 'true';
        confirm.disabled = true;
        confirm.textContent = 'Deactivating…';
        try {
            const response = await fetch('/api/controls/' + encodeURIComponent(dialog.dataset.id) + '/deactivate', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ confirmControlId: typed })
            });
            const data = await response.json().catch(() => ({}));
            if (response.ok && data.success) {
                window.location.href = data.redirect || '/component/ALL';
                return;
            }
            error.textContent = data.message || ('The control was not deactivated (' + response.status + ').');
        } catch (e) {
            error.textContent = 'The control was not deactivated: the server could not be reached.';
        }
        error.hidden = false;
        dialog.dataset.busy = 'false';
        confirm.textContent = 'Deactivate';
        confirm.disabled = !matches(dialog.dataset.controlId,
            dialog.querySelector('.qt-deactivate-input').value);
    }

    function open(button) {
        if (!dialog) {
            build();
        }
        dialog.dataset.id = button.dataset.id;
        dialog.dataset.controlId = button.dataset.controlId || '';
        dialog.dataset.busy = 'false';
        dialog.querySelector('.qt-deactivate-code').textContent = button.dataset.controlId || '';
        const input = dialog.querySelector('.qt-deactivate-input');
        input.value = '';
        dialog.querySelector('.qt-deactivate-error').hidden = true;
        const confirm = dialog.querySelector('.qt-deactivate-confirm');
        confirm.disabled = true;
        confirm.textContent = 'Deactivate';
        dialog.showModal();
        input.focus();
    }

    document.addEventListener('DOMContentLoaded', () => {
        document.querySelectorAll('[data-deactivate-control]').forEach((button) => {
            button.addEventListener('click', () => open(button));
        });
    });

    window.qtrackerDeactivateControl = { matches: matches };
})();
