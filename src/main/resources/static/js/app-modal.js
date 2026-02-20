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
})();
