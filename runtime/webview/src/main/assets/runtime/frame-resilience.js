
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

// Compose overlays do not give Android WebView DOM focus even while the game
// page is visible. MZ treats that as an inactive window and stops scene updates.
(function () {
  let attempts = 0;
  const timer = setInterval(() => {
    attempts += 1;
    const manager = globalThis.SceneManager;
    if (manager && typeof manager.isGameActive === "function" &&
        !manager.isGameActive.__makerplayVisibilityActive) {
      const isVisible = function () {
        return document.visibilityState !== "hidden";
      };
      isVisible.__makerplayVisibilityActive = true;
      manager.isGameActive = isVisible;
    }
    if ((manager && manager.isGameActive && manager.isGameActive.__makerplayVisibilityActive) ||
        attempts >= 600) {
      clearInterval(timer);
    }
  }, 4);
})();
