
// Keeps the game loop alive across transient frame errors. A single uncaught
// exception inside SceneManager.update would otherwise cancel MV's
// requestAnimationFrame chain permanently, freezing the game on a black
// screen (observed with decrypt-race errors on first load).
(function () {
  "use strict";
  if (globalThis.__makerplayFrameResilience) return;
  globalThis.__makerplayFrameResilience = true;

  let attempts = 0;
  const timer = setInterval(() => {
    attempts += 1;
    const manager = globalThis.SceneManager;
    if (manager && typeof manager.update === "function" && !manager.update.__makerplayResilient) {
      const original = manager.update;
      const resilient = function () {
        try {
          return original.apply(this, arguments);
        } catch (error) {
          if (globalThis.console && console.warn) {
            console.warn("MakerPlay: recovered from frame error", error && error.message);
          }
        }
      };
      resilient.__makerplayResilient = true;
      manager.update = resilient;
    }
    if ((manager && manager.update && manager.update.__makerplayResilient) || attempts >= 600) {
      clearInterval(timer);
    }
  }, 4);
})();

// Some environments (emulated images, suspended compositors) never deliver
// requestAnimationFrame callbacks even though timers run. Detect a stalled
// frame counter and fall back to a timer-driven loop.
(function () {
  let lastFrameCount = -1;
  let stableChecks = 0;
  let pump = null;
  setInterval(() => {
    const graphics = globalThis.Graphics;
    const manager = globalThis.SceneManager;
    if (!graphics || !manager || typeof manager.update !== "function") return;
    const current = typeof graphics.frameCount === "number" ? graphics.frameCount : -1;
    if (current !== lastFrameCount) {
      lastFrameCount = current;
      stableChecks = 0;
      if (pump) { clearInterval(pump); pump = null; }
      return;
    }
    stableChecks += 1;
    if (stableChecks >= 2 && !pump) {
      pump = setInterval(() => {
        try {
          if (typeof manager.update === "function") manager.update();
          if (typeof graphics.render === "function" && graphics._stage) {
            graphics.render(graphics._stage);
          }
        } catch (_) { /* keep pumping */ }
      }, 16);
    }
  }, 500);
})();
