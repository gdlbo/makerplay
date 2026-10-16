(function () {
  "use strict";

  function trimSceneCaches() {
    try {
      if (globalThis.ImageManager && typeof ImageManager.clear === "function") {
        ImageManager.clear();
      }
    } catch (error) {
      /* corescript may not be installed yet */
    }
    try {
      var utils = globalThis.PIXI && PIXI.utils;
      if (utils && typeof utils.clearTextureCache === "function") {
        utils.clearTextureCache();
      }
    } catch (error) {
      /* Pixi is optional on some boots */
    }
    try {
      if (typeof globalThis.__makerplayResetPerfCaches === "function") {
        globalThis.__makerplayResetPerfCaches();
      }
    } catch (error) {
      /* performance script may be disabled */
    }
  }

  globalThis.__makerplayTrimSceneCaches = trimSceneCaches;

  function stabilizeFonts() {
    var manager = globalThis.FontManager;
    if (manager && !manager.__makerplayFontStable && typeof manager.load === "function") {
      var original = manager.load;
      var seen = Object.create(null);
      manager.load = function (family) {
        var key = String(family || "");
        if (key && seen[key]) return;
        if (key) seen[key] = true;
        return original.apply(this, arguments);
      };
      manager.__makerplayFontStable = true;
    }
  }

  function hook(owner, name) {
    if (!owner || typeof owner[name] !== "function" || owner[name].__makerplayMemoryHook) return;
    var original = owner[name];
    var wrapped = function () {
      var result = original.apply(this, arguments);
      trimSceneCaches();
      return result;
    };
    wrapped.__makerplayMemoryHook = true;
    owner[name] = wrapped;
  }

  function install() {
    stabilizeFonts();
    if (globalThis.Scene_Base && Scene_Base.prototype) hook(Scene_Base.prototype, "terminate");
    if (globalThis.SceneManager) {
      hook(SceneManager, "goto");
      hook(SceneManager, "push");
      hook(SceneManager, "pop");
    }
  }

  install();
  var tries = 0;
  var timer = setInterval(function () {
    install();
    tries += 1;
    var hooked =
      globalThis.SceneManager && SceneManager.goto && SceneManager.goto.__makerplayMemoryHook;
    if (hooked || tries > 40) clearInterval(timer);
  }, 250);
})();
