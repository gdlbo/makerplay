(() => {
  "use strict";
  const eventScriptNames = [
    "set_text_sound_name",
    "set_text_sound_volume",
    "set_text_sound_pitch",
    "set_text_sound_interval",
    "set_text_sound_pan",
  ];
  const installEventScriptStubs = () => {
    eventScriptNames.forEach(name => {
      if (typeof globalThis[name] !== "function") {
        globalThis[name] = function () {};
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
    const script = document.createElement("script");
    script.src = (manager._path || "js/plugins/") + name + ".js";
    script.async = false;
    script.onerror = () => {
      if (script.parentNode) script.parentNode.removeChild(script);
    };
    root.appendChild(script);
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
    const originalSetup = manager.setup;
    manager.setup = function (plugins) {
      const value = originalSetup.apply(this, arguments);
      afterPlugins();
      return value;
    };
    manager.setup.__makerplayEventStubs = true;
    return true;
  };
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
