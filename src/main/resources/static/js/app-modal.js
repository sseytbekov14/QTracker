(function () {
    const DEFAULT_AUTOCLOSE_MS = 2500;
    let autoCloseTimer = null;
    let currentModalInstance = null;
    let hiddenEventHandler = null;

    const headerVariants = {
        success: ['bg-success', 'text-white'],
        error: ['bg-danger', 'text-white'],
        warning: ['bg-warning', 'text-dark'],
        info: ['bg-info', 'text-white']
    };

    function clearTimer() {
        if (autoCloseTimer) {
            clearTimeout(autoCloseTimer);
            autoCloseTimer = null;
        }
    }

    function removeHiddenEventListener(modalEl) {
        if (hiddenEventHandler && modalEl) {
            modalEl.removeEventListener('hidden.bs.modal', hiddenEventHandler);
            hiddenEventHandler = null;
        }
    }

    function applyHeaderVariant(headerEl, variant) {
        if (!headerEl) {
            return;
        }
        Object.values(headerVariants).flat().forEach(cssClass => headerEl.classList.remove(cssClass));
        const classes = headerVariants[variant] || headerVariants.info;
        classes.forEach(cssClass => headerEl.classList.add(cssClass));
    }

    window.showAppModal = function (options) {
        const opts = options || {};
        const title = opts.title || 'Notice';
        const message = opts.message !== undefined ? opts.message : (opts.text || '');
        const variant = opts.variant || opts.type || 'info';
        const autoCloseMs = opts.autoCloseMs === undefined ? DEFAULT_AUTOCLOSE_MS : opts.autoCloseMs;
        const redirectUrl = opts.redirectUrl || null;
        const onClose = typeof opts.onClose === 'function' ? opts.onClose : null;
        const okText = opts.okText || 'OK';
        const allowHtml = Boolean(opts.allowHtml);

        const modalEl = document.getElementById('appNotificationModal');
        if (!modalEl || !window.bootstrap) {
            if (message) {
                alert(message);
            } else {
                alert(title);
            }
            if (onClose) {
                onClose();
            }
            if (redirectUrl) {
                window.location.href = redirectUrl;
            }
            return;
        }

        const titleEl = modalEl.querySelector('#appModalTitle');
        const messageEl = modalEl.querySelector('#appModalMessage');
        const headerEl = modalEl.querySelector('.app-modal-header');
        const okBtn = modalEl.querySelector('#appModalOkBtn');

        if (titleEl) {
            titleEl.textContent = title;
        }
        if (messageEl) {
            if (allowHtml) {
                messageEl.innerHTML = message;
            } else {
                messageEl.textContent = message;
            }
        }
        if (okBtn) {
            okBtn.textContent = okText;
        }

        applyHeaderVariant(headerEl, variant);
        clearTimer();

        // Remove any previously attached event listeners
        removeHiddenEventListener(modalEl);

        // Use getOrCreateInstance to avoid duplicate modal instances
        const modal = bootstrap.Modal.getOrCreateInstance(modalEl);

        // Handler for when modal is fully hidden (after animation)
        hiddenEventHandler = () => {
            clearTimer();
            removeHiddenEventListener(modalEl);
            // Remove closing class for next time
            modalEl.classList.remove('closing');
            // Reset progress bar
            const progressBar = modalEl.querySelector('.app-modal-progress-bar');
            if (progressBar) {
                progressBar.style.animation = 'none';
            }
            if (onClose) {
                onClose();
            }
            if (redirectUrl) {
                window.location.href = redirectUrl;
            }
        };

        modalEl.addEventListener('hidden.bs.modal', hiddenEventHandler, { once: true });

        // Enhanced close handler with smooth animation
        const closeModal = () => {
            clearTimer();
            // Remove progress animation
            const progressBar = modalEl.querySelector('.app-modal-progress-bar');
            if (progressBar) {
                progressBar.classList.remove('app-modal-progress-countdown');
                progressBar.style.animation = 'none';
            }
            // Remove auto-closing class
            modalEl.classList.remove('app-modal-auto-closing');
            // Add closing class to trigger close animation
            modalEl.classList.add('closing');
            // Wait for animation to complete before hiding (500ms)
            setTimeout(() => {
                modal.hide();
            }, 500);
        };

        if (okBtn) {
            okBtn.onclick = closeModal;
        }

        // Force a reflow to ensure animations work properly
        modalEl.offsetHeight;

        // Show the modal (this triggers the open animation via CSS)
        modal.show();

        // Start progress bar animation immediately with modal (synchronized)
        const progressBar = modalEl.querySelector('.app-modal-progress-bar');
        if (progressBar && autoCloseMs && autoCloseMs > 0) {
            // Set animation duration to match autoCloseMs
            progressBar.style.setProperty('--progress-duration', autoCloseMs + 'ms');
            // Remove old animation class
            progressBar.classList.remove('app-modal-progress-countdown');
            // Remove auto-closing class if it exists
            modalEl.classList.remove('app-modal-auto-closing');
            // Trigger reflow to ensure animation restarts
            void progressBar.offsetWidth;
            void modalEl.offsetWidth;
            // Add auto-closing class to start synchronized close animation
            modalEl.classList.add('app-modal-auto-closing');
            // Add animation class to start progress bar
            progressBar.classList.add('app-modal-progress-countdown');
            
            // Auto-close after animation completes (don't call closeModal to avoid duplicate animation)
            autoCloseTimer = setTimeout(() => {
                // Just hide the modal directly - CSS animation already handled the fade out
                modalEl.classList.remove('app-modal-auto-closing');
                modal.hide();
            }, autoCloseMs);
        }
    };

    /**
     * Styled replacement for window.confirm().
     * showConfirmModal({ title, message, confirmText, cancelText, variant: 'danger' | 'primary' })
     * returns a Promise that resolves to true (confirmed) or false (cancelled / closed).
     */
    const WARNING_ICON = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" '
        + 'stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">'
        + '<path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/>'
        + '<line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>';

    function getConfirmModalElement() {
        let modalEl = document.getElementById('appConfirmModal');
        if (modalEl) {
            return modalEl;
        }
        modalEl = document.createElement('div');
        modalEl.className = 'modal fade';
        modalEl.id = 'appConfirmModal';
        modalEl.tabIndex = -1;
        modalEl.setAttribute('aria-hidden', 'true');
        modalEl.setAttribute('aria-labelledby', 'appConfirmTitle');
        modalEl.setAttribute('aria-describedby', 'appConfirmMessage');
        modalEl.innerHTML =
            '<div class="modal-dialog modal-dialog-centered app-confirm-dialog">'
            + '<div class="modal-content app-confirm-content">'
            + '<div class="app-confirm-body">'
            + '<div class="app-confirm-icon">' + WARNING_ICON + '</div>'
            + '<div><h5 class="app-confirm-title" id="appConfirmTitle"></h5>'
            + '<p class="app-confirm-message" id="appConfirmMessage"></p></div>'
            + '</div>'
            + '<div class="app-confirm-footer">'
            + '<button type="button" class="btn app-confirm-cancel" data-bs-dismiss="modal"></button>'
            + '<button type="button" class="btn app-confirm-ok"></button>'
            + '</div>'
            + '</div></div>';
        document.body.appendChild(modalEl);
        return modalEl;
    }

    window.showConfirmModal = function (options) {
        const opts = options || {};
        const title = opts.title || 'Are you sure?';
        const message = opts.message || '';
        return new Promise(resolve => {
            if (!window.bootstrap) {
                resolve(window.confirm(message ? title + '\n\n' + message : title));
                return;
            }
            const modalEl = getConfirmModalElement();
            const okBtn = modalEl.querySelector('.app-confirm-ok');
            const cancelBtn = modalEl.querySelector('.app-confirm-cancel');
            const iconEl = modalEl.querySelector('.app-confirm-icon');
            const isPrimary = opts.variant === 'primary';

            modalEl.querySelector('#appConfirmTitle').textContent = title;
            modalEl.querySelector('#appConfirmMessage').textContent = message;
            okBtn.textContent = opts.confirmText || 'Confirm';
            cancelBtn.textContent = opts.cancelText || 'Cancel';
            okBtn.classList.toggle('is-primary', isPrimary);
            iconEl.classList.toggle('is-primary', isPrimary);

            // Asked over another open dialog (e.g. a form before it saves): shown above it, and when this one
            // closes Bootstrap's own clean-up must not unlock the page under the dialog that stays open
            const underlying = document.querySelector('.modal.show:not(#appConfirmModal)');
            modalEl.classList.toggle('is-stacked', Boolean(underlying));

            const modal = bootstrap.Modal.getOrCreateInstance(modalEl);
            let confirmed = false;
            okBtn.onclick = () => {
                confirmed = true;
                modal.hide();
            };
            // Safe default: focus the cancelling button
            modalEl.addEventListener('shown.bs.modal', () => cancelBtn.focus(), { once: true });
            modalEl.addEventListener('hidden.bs.modal', () => {
                if (underlying && underlying.classList.contains('show')) {
                    document.body.classList.add('modal-open');
                }
                resolve(confirmed);
            }, { once: true });
            modal.show();
        });
    };
})();
