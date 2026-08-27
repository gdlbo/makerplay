(() => {
  "use strict";
  if (globalThis.__makerplayLegacyCompatibilityInstalled) return;
  globalThis.__makerplayLegacyCompatibilityInstalled = true;

  if (!globalThis.AudioContext && globalThis.webkitAudioContext) {
    globalThis.AudioContext = globalThis.webkitAudioContext;
  }
  if (!globalThis.requestAnimationFrame) {
    globalThis.requestAnimationFrame = callback => setTimeout(() => callback(performance.now()), 16);
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
  const textAlign = canvasContext &&
    Object.getOwnPropertyDescriptor(canvasContext.prototype, "textAlign");
  if (textAlign?.set && textAlign.configurable) {
    const validAlignments = ["left", "right", "center", "start", "end"];
    Object.defineProperty(canvasContext.prototype, "textAlign", {
      configurable: textAlign.configurable,
      enumerable: textAlign.enumerable,
      get: textAlign.get,
      set(value) {
        const compatibleValue = typeof value === "string" && validAlignments.indexOf(value) >= 0
          ? value
          : "left";
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
      return getImageData.call(this, compatibleX, compatibleY, ...Array.prototype.slice.call(arguments, 2));
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

  // Provide TouchInput.requestKeyboard support for plugins requesting text input
  if (!globalThis.TouchInput) globalThis.TouchInput = {};
  if (typeof globalThis.TouchInput.requestKeyboard !== "function") {
    globalThis.TouchInput.requestKeyboard = function (variableId, title, defaultValue) {
      const promptTitle = typeof title === "string" ? title : "Input:";
      const initialValue = typeof defaultValue === "string" ? defaultValue : "";
      const result = globalThis.prompt(promptTitle, initialValue);
      if (result !== null && result !== undefined) {
        if (variableId && globalThis.$gameVariables && typeof globalThis.$gameVariables.setValue === "function") {
          globalThis.$gameVariables.setValue(variableId, result);
        }
      }
      return result;
    };
  }

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
    document.addEventListener("click", event => {
      const target = event.target;
      if (target && target.id === "_111_input") {
        const current = target.value || "";
        const result = globalThis.prompt("Input:", current);
        if (result !== null && result !== undefined) {
          target.value = result;
        }
      }
    }, true);
  }
})();
