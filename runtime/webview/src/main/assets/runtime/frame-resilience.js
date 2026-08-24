
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

// MZ/MV gate scene updates on window focus/page visibility, neither of which
// is reliable inside an embedded Android WebView: DOM focus is never granted by
// Compose overlays, and the page reports "hidden" once WebView.onPause() fires
// (e.g. an audio-focus transition when a Live2D scene starts). The host already
// owns background pausing via the WebView lifecycle, so keep the game active here.
(function () {
  let attempts = 0;
  const timer = setInterval(() => {
    attempts += 1;
    const manager = globalThis.SceneManager;
    if (manager && typeof manager.isGameActive === "function" &&
        !manager.isGameActive.__makerplayAlwaysActive) {
      const alwaysActive = function () {
        return true;
      };
      alwaysActive.__makerplayAlwaysActive = true;
      manager.isGameActive = alwaysActive;
    }
    if ((manager && manager.isGameActive && manager.isGameActive.__makerplayAlwaysActive) ||
        attempts >= 600) {
      clearInterval(timer);
    }
  }, 4);
})();
