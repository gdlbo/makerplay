(() => {
  "use strict";
  const eventScriptNames = [
    "set_text_sound_name",
    "set_text_sound_volume",
    "set_text_sound_pitch",
    "set_text_sound_interval",
    "set_text_sound_pan",
  ];
  const reportRecovery = (action, path, cause) => {
    console.warn("runtime.startup.recovery " + JSON.stringify({
      stage: "plugins", action, path: String(path), cause: String(cause),
    }));
  };
  // Only errors from scripts currently loading through PluginManager are isolated.
  // Core scripts and later gameplay errors retain the engine's normal handlers.
  const safeCause = error => {
    const message = String(error.message || "");
    // Extract identifiers only: never forward arbitrary values or source snippets.
    const member = message.match(/(?:reading|setting) ['"]([A-Za-z_$][\w$]*)['"]/) ||
      message.match(/^([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*) is not (?:defined|a function)/);
    return (error.name || "Error") + (member ? " member=" + member[1] : " check-script-at-location");
  };
  const loadingPlugins = globalThis.__makerplayLoadingPlugins || new Map();
  globalThis.__makerplayLoadingPlugins = loadingPlugins;
  if (!globalThis.__makerplayPluginErrorHandler && globalThis.addEventListener) {
    globalThis.__makerplayPluginErrorHandler = true;
    globalThis.addEventListener("error", event => {
      const script = loadingPlugins.get(event.filename);
      if (!script || !event.error) {
        if (event.error) {
          console.error("runtime.startup.failure " + JSON.stringify({
            stage: "script-execution", action: "engine-error-handler",
            path: event.filename || "unknown", cause: safeCause(event.error),
            line: event.lineno || 0, column: event.colno || 0,
          }));
        }
        return;
      }
      reportRecovery("skip-plugin", script.getAttribute("src"),
        safeCause(event.error) + " at " + (event.lineno || 0) + ":" + (event.colno || 0));
      event.preventDefault();
      event.stopImmediatePropagation();
    }, true);
  }
  const installPluginRecovery = manager => {
    if (typeof manager.loadScript !== "function" || manager.loadScript.__makerplayRecovery) return;
    // RPG Maker delegates plugin requests to loadScript; leave setup's parameter
    // registration and ordering intact, but do not enqueue failed optional scripts.
    manager.loadScript = function (name) {
      const script = document.createElement("script");
      const path = (this._path || "js/plugins/") + name;
      script.src = path;
      script.async = false;
      script._url = path;
      loadingPlugins.set(script.src, script);
      script.onload = () => loadingPlugins.delete(script.src);
      script.onerror = () => {
        loadingPlugins.delete(script.src);
        reportRecovery("skip-plugin", path, "resource-unavailable");
      };
      document.body.appendChild(script);
    };
    manager.loadScript.__makerplayRecovery = true;
  };
  const installEventScriptStubs = () => {
    eventScriptNames.forEach(name => {
      if (typeof globalThis[name] !== "function") {
        globalThis[name] = function () {
          if (!globalThis[name].__makerplayReported) {
            globalThis[name].__makerplayReported = true;
            reportRecovery("use-stub", name, "optional-text-sound-function-missing");
          }
        };
      }
      const Interpreter = globalThis.Game_Interpreter;
      if (
        Interpreter &&
        Interpreter.prototype &&
        typeof Interpreter.prototype[name] !== "function"
      ) {
        Interpreter.prototype[name] = function () {
          return globalThis[name].apply(this, arguments);
        };
      }
    });
  };
  const tryLoadUnlistedPlugin = name => {
    const manager = globalThis.PluginManager;
    if (!manager || typeof manager.loadScript !== "function") return;
    const scripts = manager._scripts || (manager._scripts = []);
    if (scripts.indexOf(name) >= 0) return;
    const root = globalThis.document && (document.body || document.head);
    if (!root || typeof document.createElement !== "function") return;
    installPluginRecovery(manager);
    manager.loadScript(name + ".js");
    scripts.push(name);
  };
  const bindKeyboardRequest = () => {
    const TouchInput = globalThis.TouchInput;
    if (!TouchInput || (TouchInput.requestKeyboard && TouchInput.requestKeyboard.__makerplay))
      return;
    TouchInput.requestKeyboard = function (variableId, title, defaultValue) {
      const bridge = globalThis.makerplayKeyboard;
      if (bridge && typeof bridge.request === "function") {
        bridge.request(
          String(title || "Input:"),
          defaultValue == null ? "" : String(defaultValue),
          String(variableId || 0),
        );
      }
      return "";
    };
    TouchInput.requestKeyboard.__makerplay = true;
  };
  const afterPlugins = () => {
    tryLoadUnlistedPlugin("MessageTextSound");
    installEventScriptStubs();
    bindKeyboardRequest();
    if (globalThis.Utils)
      Utils.isDesignMode = function () {
        return false;
      };
  };
  const hookPluginManager = () => {
    const manager = globalThis.PluginManager;
    if (!manager || typeof manager.setup !== "function") return false;
    if (manager.setup.__makerplayEventStubs) return true;
    installPluginRecovery(manager);
    const originalSetup = manager.setup;
    manager.setup = function (plugins) {
      const value = originalSetup.apply(this, arguments);
      afterPlugins();
      return value;
    };
    manager.setup.__makerplayEventStubs = true;
    return true;
  };
  // Script load events run after the managers script executes and before main.js
  // starts plugin setup. DOMContentLoaded alone is too late for parser scripts.
  if (globalThis.document && document.addEventListener && !globalThis.__makerplayPluginLoadHook) {
    globalThis.__makerplayPluginLoadHook = true;
    document.addEventListener("load", () => hookPluginManager(), true);
  }
  installEventScriptStubs();
  if (!hookPluginManager() && globalThis.document && document.addEventListener) {
    document.addEventListener("DOMContentLoaded", () => {
      hookPluginManager();
      afterPlugins();
    });
  }
  afterPlugins();
  if (globalThis.__makerplayLegacyCompatibilityInstalled) return;
  globalThis.__makerplayLegacyCompatibilityInstalled = true;

  // Chromium may forbid unload entirely. Keep legacy cleanup on pagehide,
  // which works under that policy and does not block the back/forward cache.
  if (globalThis.addEventListener && globalThis.removeEventListener) {
    const add = globalThis.addEventListener;
    const remove = globalThis.removeEventListener;
    const listeners = new WeakMap();
    let reported = false;
    globalThis.addEventListener = function (type, listener, options) {
      if (this !== globalThis || type !== "unload" ||
          !listener || (typeof listener !== "function" && typeof listener !== "object")) {
        return add.call(this, type, listener, options);
      }
      const capture = typeof options === "boolean" ? options : !!options?.capture;
      const once = !!(options && typeof options === "object" && options.once);
      let registrations = listeners.get(listener);
      if (!registrations) {
        registrations = new Map();
        listeners.set(listener, registrations);
      }
      let compatible = registrations.get(capture);
      if (!compatible) {
        compatible = function (event) {
          if (event.persisted) return;
          if (once) {
            remove.call(globalThis, "pagehide", compatible, capture);
            registrations.delete(capture);
          }
          if (typeof listener === "function") listener.call(this, event);
          else if (typeof listener.handleEvent === "function") listener.handleEvent(event);
        };
        registrations.set(capture, compatible);
      }
      if (!reported) {
        reported = true;
        console.warn("runtime.lifecycle.compatibility action=use-pagehide cause=legacy-unload-listener");
      }
      const compatibleOptions = options && typeof options === "object" ? { ...options, once: false } : options;
      return add.call(this, "pagehide", compatible, compatibleOptions);
    };
    globalThis.removeEventListener = function (type, listener, options) {
      if (this === globalThis && type === "unload" && listener && listeners.has(listener)) {
        const capture = typeof options === "boolean" ? options : !!options?.capture;
        const registrations = listeners.get(listener);
        const compatible = registrations.get(capture);
        registrations.delete(capture);
        return remove.call(this, "pagehide", compatible, options);
      }
      return remove.call(this, type, listener, options);
    };
  }

  if (!globalThis.AudioContext && globalThis.webkitAudioContext) {
    globalThis.AudioContext = globalThis.webkitAudioContext;
  }
  if (!globalThis.requestAnimationFrame) {
    globalThis.requestAnimationFrame = callback =>
      setTimeout(() => callback(performance.now()), 16);
    globalThis.cancelAnimationFrame = clearTimeout;
  }
  if (!Element.prototype.remove) {
    Element.prototype.remove = function remove() {
      this.parentNode?.removeChild(this);
    };
  }

  // This runs before rmmz_core.js creates Bitmap, so guard the Canvas setter
  // used by Bitmap.drawText and plugins instead of wrapping Bitmap itself.
  const canvasContext = globalThis.CanvasRenderingContext2D;
  const textAlign =
    canvasContext && Object.getOwnPropertyDescriptor(canvasContext.prototype, "textAlign");
  if (textAlign?.set && textAlign.configurable) {
    const validAlignments = ["left", "right", "center", "start", "end"];
    Object.defineProperty(canvasContext.prototype, "textAlign", {
      configurable: textAlign.configurable,
      enumerable: textAlign.enumerable,
      get: textAlign.get,
      set(value) {
        const compatibleValue =
          typeof value === "string" && validAlignments.indexOf(value) >= 0 ? value : "left";
        textAlign.set.call(this, compatibleValue);
      },
    });
  }

  // Older RPG Maker plugins pass scaled touch coordinates directly to
  // getImageData. Android WebView requires integer canvas coordinates.
  const getImageData = canvasContext?.prototype?.getImageData;
  if (typeof getImageData === "function" && !getImageData.__makerplayIntegerCoordinates) {
    const compatibleGetImageData = function (x, y) {
      const compatibleX = Number.isFinite(x) ? Math.trunc(x) : x;
      const compatibleY = Number.isFinite(y) ? Math.trunc(y) : y;
      return getImageData.call(
        this,
        compatibleX,
        compatibleY,
        ...Array.prototype.slice.call(arguments, 2),
      );
    };
    compatibleGetImageData.__makerplayIntegerCoordinates = true;
    canvasContext.prototype.getImageData = compatibleGetImageData;
  }

  // Polyfill Cordova keyboard plugin if missing
  if (!globalThis.cordova) globalThis.cordova = {};
  if (!globalThis.cordova.plugins) globalThis.cordova.plugins = {};
  if (!globalThis.cordova.plugins.Keyboard) {
    globalThis.cordova.plugins.Keyboard = {
      show: () => {},
      hide: () => {},
      isVisible: false,
    };
  }

  bindKeyboardRequest();
  const keyboardPoll = setInterval(() => {
    bindKeyboardRequest();
    if (
      globalThis.TouchInput &&
      TouchInput.requestKeyboard &&
      TouchInput.requestKeyboard.__makerplay
    ) {
      clearInterval(keyboardPoll);
    }
  }, 50);
  setTimeout(() => clearInterval(keyboardPoll), 10000);

  // Ensure game canvas element has id="gameCanvas" for DOM form positioning plugins
  const ensureCanvasId = () => {
    if (typeof document === "undefined") return;
    const canvas = document.querySelector("canvas");
    if (canvas && !canvas.id) {
      canvas.id = "gameCanvas";
    }
  };
  if (typeof document !== "undefined") {
    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", ensureCanvasId, { once: true });
    } else {
      ensureCanvasId();
    }
    document.addEventListener(
      "click",
      event => {
        const target = event.target;
        if (target && target.id === "_111_input") {
          const bridge = globalThis.makerplayKeyboard;
          if (bridge && typeof bridge.request === "function") {
            bridge.request("Input:", String(target.value || ""), "0");
          }
        }
      },
      true,
    );
    document.addEventListener(
      "focusin",
      event => {
        const target = event.target;
        if (!target || (target.tagName !== "INPUT" && target.tagName !== "TEXTAREA")) return;
        if (target.id === "textBox" || target.id === "_111_input") return;
        if (target.dataset && target.dataset.makerplayNative === "1") return;
        event.preventDefault();
        try {
          target.blur();
        } catch (_) {}
        const bridge = globalThis.makerplayKeyboard;
        if (bridge && typeof bridge.request === "function") {
          bridge.request(
            String(target.placeholder || target.name || "Input:"),
            String(target.value || ""),
            "0",
          );
        }
      },
      true,
    );
  }
})();
