/* global window, document, Quill, MutationObserver, FormData, fetch */
// The rich-text editor of the ten long fields of a control (2026-11-23): bold, italic, underline, strike-through,
// headings, lists, quotes, links and pictures (Quill 2, js/vendor/quill). The field itself (textarea or input,
// marked data-rich-text) stays in its form, hidden, and holds the editor's HTML, so the page's own code saves it as
// before; a value the page sets on the field is shown in the editor. The editor follows the field's readonly /
// disabled state, which the page sets from the user's rights. A picture - the toolbar button, a paste (Ctrl+V) or a
// drop - is uploaded to /api/controls/{id}/rich-images and only its address goes into the text; without a control
// (New Control) pictures are not offered. The server cleans every text again (RichText).
(function () {
    var EDITOR_HTML = /^\s*<(p|h[1-3]|ol|ul|blockquote|pre)[\s>]/i;
    var instances = [];

    function isHtml(value) {
        return EDITOR_HTML.test(value || '');
    }

    function escapeHtml(text) {
        return String(text).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

    /** Old plain text (line breaks kept) as paragraphs. */
    function plainToHtml(text) {
        return String(text || '').replace(/\r\n/g, '\n').split('\n')
            .map(function (line) { return '<p>' + (line ? escapeHtml(line) : '<br>') + '</p>'; }).join('');
    }

    function editorValue(quill) {
        var hasPicture = !!quill.root.querySelector('img');
        if (!quill.getText().trim() && !hasPicture) {
            return '';
        }
        return quill.root.innerHTML;
    }

    function uploadPicture(controlId, file) {
        var data = new FormData();
        data.append('image', file, file.name || 'picture.png');
        return fetch('/api/controls/' + encodeURIComponent(controlId) + '/rich-images', { method: 'POST', body: data, credentials: 'same-origin' })
            .then(function (response) { return response.json().then(function (body) { return { ok: response.ok, body: body }; }); })
            .then(function (answer) {
                if (!answer.ok || !answer.body || !answer.body.url) {
                    throw new Error((answer.body && answer.body.message) || 'The picture could not be uploaded');
                }
                return answer.body.url;
            });
    }

    function tell(message) {
        if (typeof window.showToast === 'function') {
            window.showToast(message, 'error', 6000);
        } else if (window.console) {
            window.console.warn(message);
        }
    }

    function attach(field, options) {
        if (!field || field.dataset.richTextReady === '1' || typeof Quill === 'undefined') {
            return null;
        }
        field.dataset.richTextReady = '1';
        var controlId = (options && options.controlId) || field.dataset.controlId || null;
        var max = parseInt(field.getAttribute('maxlength'), 10) || 0;
        var single = field.tagName === 'INPUT';

        var wrap = document.createElement('div');
        wrap.className = 'rt-wrap' + (single ? ' rt-single' : '');
        var host = document.createElement('div');
        host.className = 'rt-editor';
        wrap.appendChild(host);
        var counter = document.createElement('div');
        counter.className = 'rt-counter';
        wrap.appendChild(counter);
        if (field.dataset.counter) {
            counter.style.display = 'none'; // the page has its own counter for this field
        }
        field.parentNode.insertBefore(wrap, field.nextSibling);
        field.classList.add('rt-source');
        field.style.display = 'none';

        var toolbar = [[{ header: [1, 2, 3, false] }], ['bold', 'italic', 'underline', 'strike'],
            [{ list: 'ordered' }, { list: 'bullet' }], ['blockquote', 'link']];
        if (controlId) {
            toolbar.push(['image']);
        }
        toolbar.push(['clean']);

        var quill = new Quill(host, {
            theme: 'snow',
            placeholder: field.getAttribute('placeholder') || '',
            modules: {
                toolbar: { container: toolbar, handlers: { image: function () { choosePicture(); } } },
                uploader: {
                    mimetypes: ['image/png', 'image/jpeg', 'image/gif'],
                    handler: function (range, files) { insertPictures(range, files); }
                }
            }
        });
        if (single) {
            // One line: Enter does not start a paragraph
            quill.keyboard.addBinding({ key: 'Enter' }, function () { return false; });
        }

        var nativeValue = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(field), 'value');
        var writing = false;

        function load(value) {
            var html = isHtml(value) ? value : plainToHtml(value);
            quill.setContents(quill.clipboard.convert({ html: value ? html : '' }), 'silent');
            showCount();
        }

        function showCount() {
            var length = editorValue(quill).length;
            counter.textContent = max ? length + ' / ' + max : '';
            counter.classList.toggle('is-over', max > 0 && length > max);
        }

        function write() {
            writing = true;
            nativeValue.set.call(field, editorValue(quill));
            field.dispatchEvent(new Event('input', { bubbles: true }));
            field.dispatchEvent(new Event('change', { bubbles: true }));
            writing = false;
            showCount();
        }

        // The page's own code reads and sets field.value: the editor follows what it sets
        Object.defineProperty(field, 'value', {
            configurable: true,
            get: function () { return nativeValue.get.call(field); },
            set: function (v) {
                nativeValue.set.call(field, v);
                if (!writing) {
                    load(v);
                }
            }
        });

        quill.on('text-change', function (delta, old, source) {
            if (source === 'user') {
                write();
            }
        });

        // A pasted picture that is not one of ours (e.g. inside copied HTML): uploaded, or left out
        quill.clipboard.addMatcher('IMG', function (node, delta) {
            var src = node.getAttribute('src') || '';
            if (/^\/api\/controls\/\d+\/rich-images\//.test(src)) {
                return delta;
            }
            if (controlId && src.indexOf('data:image/') === 0) {
                fetch(src).then(function (r) { return r.blob(); }).then(function (blob) {
                    var range = quill.getSelection(true);
                    insertPictures(range, [new File([blob], 'pasted.' + (blob.type.split('/')[1] || 'png'), { type: blob.type })]);
                });
            }
            var Delta = Quill.import('delta');
            return new Delta();
        });

        function insertPictures(range, files) {
            if (!controlId) {
                tell('Save the control first: pictures can be added to a saved control.');
                return;
            }
            var at = range ? range.index : quill.getLength();
            Array.prototype.forEach.call(files, function (file) {
                uploadPicture(controlId, file).then(function (url) {
                    quill.insertEmbed(at, 'image', url, 'user');
                    at += 1;
                    quill.setSelection(at, 0, 'silent');
                }).catch(function (error) { tell(error.message); });
            });
        }

        function choosePicture() {
            var input = document.createElement('input');
            input.type = 'file';
            input.accept = 'image/png,image/jpeg,image/gif';
            input.addEventListener('change', function () {
                if (input.files && input.files.length) {
                    insertPictures(quill.getSelection(true), input.files);
                }
            });
            input.click();
        }

        // Read only while the field is: the page sets readonly / disabled from the user's rights
        function followState() {
            var locked = field.readOnly || field.disabled || field.classList.contains('readonly-field');
            quill.enable(!locked);
            wrap.classList.toggle('is-readonly', locked);
        }
        new MutationObserver(followState).observe(field, { attributes: true, attributeFilter: ['readonly', 'disabled', 'class'] });
        // readOnly / disabled set as properties do not always show as attributes: look again now and then
        var lastState = null;
        window.setInterval(function () {
            var state = field.readOnly + '|' + field.disabled + '|' + field.className;
            if (state !== lastState) {
                lastState = state;
                followState();
            }
        }, 400);

        load(nativeValue.get.call(field));
        followState();
        var instance = { field: field, quill: quill, load: load };
        instances.push(instance);
        return instance;
    }

    function attachAll(scope) {
        var controlIdInput = document.querySelector('input[name="id"]');
        var pageControlId = controlIdInput && /^\d+$/.test(controlIdInput.value) ? controlIdInput.value : null;
        (scope || document).querySelectorAll('[data-rich-text]').forEach(function (field) {
            attach(field, { controlId: field.dataset.controlId || pageControlId });
        });
    }

    window.QTrackerRichText = { attach: attach, attachAll: attachAll, isHtml: isHtml, plainToHtml: plainToHtml, instances: instances };
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function () { attachAll(document); });
    } else {
        attachAll(document);
    }
})();
