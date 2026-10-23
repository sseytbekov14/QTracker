// The end of a session without losing what was typed. Without a session an /api/** call answers 401 with
// {"code":"SESSION_EXPIRED"} (SignInEntryPoint) instead of the sign-in page: then a banner says
// "Session expired, reload the page" and nothing else happens - no reload, no field is cleared, the person can
// copy their text first. While the person types, a keepalive (GET /api/session/keepalive) at most once in
// 5 minutes keeps the session open (it ends after 15 minutes without a request); any other /api call counts too.
// Loaded after csrf.js (fragments/csrf.html) and wraps the same window.fetch.
(function() {
    if (!window.fetch || window.fetch.qtrackerSession) {
        return;
    }

    const KEEPALIVE_URL = '/api/session/keepalive';
    const KEEPALIVE_EVERY_MS = 5 * 60 * 1000;
    const MESSAGE = 'Session expired, reload the page';
    const HINT = 'What you typed is still on this page: copy it before reloading.';

    const originalFetch = window.fetch;
    // Loading the page was a request
    let lastContact = Date.now();
    let expired = false;

    function isApi(url) {
        try {
            const parsed = new URL(url, window.location.href);
            return parsed.origin === window.location.origin && parsed.pathname.indexOf('/api/') === 0;
        } catch (error) {
            return false;
        }
    }

    function showExpired() {
        if (expired) {
            return;
        }
        expired = true;
        const banner = document.createElement('div');
        banner.id = 'sessionExpiredBanner';
        banner.className = 'session-expired-banner';
        banner.setAttribute('role', 'alert');
        const text = document.createElement('div');
        const title = document.createElement('strong');
        title.textContent = MESSAGE;
        const hint = document.createElement('span');
        hint.textContent = HINT;
        text.appendChild(title);
        text.appendChild(hint);
        const reload = document.createElement('button');
        reload.type = 'button';
        reload.textContent = 'Reload';
        reload.addEventListener('click', function() {
            window.location.reload();
        });
        banner.appendChild(text);
        banner.appendChild(reload);
        document.body.appendChild(banner);
    }

    function sessionFetch(input, init) {
        const url = input instanceof Request ? input.url : String(input);
        const api = isApi(url);
        return originalFetch.call(window, input, init).then(function(response) {
            if (api && response.status === 401) {
                showExpired();
            } else if (api) {
                lastContact = Date.now();
            }
            return response;
        });
    }

    function keepalive() {
        if (expired || Date.now() - lastContact < KEEPALIVE_EVERY_MS) {
            return;
        }
        // Counted at once, so fast typing sends one request, not one per key
        lastContact = Date.now();
        window.fetch(KEEPALIVE_URL, { credentials: 'same-origin', cache: 'no-store' }).catch(function() {
            // Offline for a moment: the next keystroke after 5 minutes tries again
        });
    }

    sessionFetch.qtrackerSession = true;
    // csrf.js must not wrap again
    sessionFetch.csrfToken = originalFetch.csrfToken;
    window.fetch = sessionFetch;
    document.addEventListener('input', keepalive, true);
})();
