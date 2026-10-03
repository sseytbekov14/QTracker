// Adds the CSRF token (from <meta name="_csrf"> / <meta name="_csrf_header">, see fragments/csrf.html)
// to every fetch that changes data on this site. Requests to other origins never get the token.
(function() {
    if (!window.fetch || window.fetch.csrfToken) {
        return;
    }

    const SAFE_METHODS = ['GET', 'HEAD', 'OPTIONS', 'TRACE'];
    const originalFetch = window.fetch;

    function metaContent(name) {
        const meta = document.querySelector('meta[name="' + name + '"]');
        return meta ? meta.content : '';
    }

    function isSameOrigin(url) {
        try {
            return new URL(url, window.location.href).origin === window.location.origin;
        } catch (error) {
            return false;
        }
    }

    function csrfFetch(input, init) {
        const request = input instanceof Request ? input : null;
        const url = request ? request.url : String(input);
        const method = ((init && init.method) || (request && request.method) || 'GET').toUpperCase();
        const token = metaContent('_csrf');
        const headerName = metaContent('_csrf_header');

        if (SAFE_METHODS.indexOf(method) !== -1 || !token || !headerName || !isSameOrigin(url)) {
            return originalFetch.call(window, input, init);
        }

        const headers = new Headers((init && init.headers) || (request && request.headers) || undefined);
        if (!headers.has(headerName)) {
            headers.set(headerName, token);
        }
        return originalFetch.call(window, input, Object.assign({}, init, { headers: headers }));
    }

    csrfFetch.csrfToken = true;
    window.fetch = csrfFetch;
})();
