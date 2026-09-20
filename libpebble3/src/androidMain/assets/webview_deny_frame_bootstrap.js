/* Runs first in the sandboxed frame of a network-denied PebbleKit JS session (Android only). */
(function () {
    // Document-commit guard: native allows one bootstrap per session and ends the session on a second.
    if (!_Pebble.frameDocumentLoaded()) return;

    // An opaque-origin frame has no localStorage. This stand-in keeps the app's keys in memory,
    // seeded from the native store, and writes every change through to it.
    (function () {
        var items = new Map();
        var restored = JSON.parse(_localStorage.restoreState());
        Object.keys(restored).forEach(function (key) {
            items.set(key, String(restored[key]));
        });
        var methods = {
            getItem: function (key) {
                key = String(key);
                return items.has(key) ? items.get(key) : null;
            },
            setItem: function (key, value) {
                key = String(key);
                value = String(value);
                items.set(key, value);
                _localStorage.setItem(key, value);
            },
            removeItem: function (key) {
                key = String(key);
                if (items.delete(key)) _localStorage.removeItem(key);
            },
            clear: function () {
                items.clear();
                _localStorage.clear();
            },
            key: function (index) {
                var keys = Array.from(items.keys());
                index = Number(index) | 0;
                return index >= 0 && index < keys.length ? keys[index] : null;
            },
        };
        var isBuiltIn = function (prop) {
            return prop === "length" || Object.prototype.hasOwnProperty.call(methods, prop);
        };
        var storage = new Proxy({}, {
            get: function (target, prop) {
                if (typeof prop === "symbol") return undefined;
                if (prop === "length") return items.size;
                if (isBuiltIn(prop)) return methods[prop];
                return items.has(prop) ? items.get(prop) : undefined;
            },
            set: function (target, prop, value) {
                if (typeof prop !== "symbol" && !isBuiltIn(prop)) methods.setItem(prop, value);
                return true;
            },
            deleteProperty: function (target, prop) {
                if (typeof prop !== "symbol") methods.removeItem(prop);
                return true;
            },
            has: function (target, prop) {
                return typeof prop !== "symbol" && (isBuiltIn(prop) || items.has(prop));
            },
            ownKeys: function () {
                return Array.from(items.keys());
            },
            getOwnPropertyDescriptor: function (target, prop) {
                if (typeof prop === "symbol" || !items.has(prop)) return undefined;
                return { value: items.get(prop), writable: true, enumerable: true, configurable: true };
            },
        });
        Object.defineProperty(window, "localStorage", { value: storage, configurable: false, writable: false });
        window.__localStorageShimmed = true;
    })();

    // Native reaches the frame only through the host page, which forwards script text.
    window.addEventListener("message", function (event) {
        if (event.source !== window.parent || typeof event.data !== "string") return;
        (0, eval)(event.data);
    });

    // textContent is not parsed as HTML, and the text runs as a classic script.
    var startup = document.createElement("script");
    startup.textContent = _Pebble.readStartupScript();
    document.head.appendChild(startup);

    window.parent.postMessage("pkjs-frame-ready", "*");
    _Pebble.frameStartupScriptHasLoaded();
})();
