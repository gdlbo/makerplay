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
})();