// MakerPlay RPGM visual boosts: crisp pixel presentation, mild canvas filters,
// and Pixi nearest sampling. Does not mutate Graphics._realScale (that desyncs
// TouchInput vs canvas CSS and breaks Options gauges: volume, opacity, borders).
(function () {
  "use strict";
  if (globalThis.__makerplayVisualBoosts) return;
  globalThis.__makerplayVisualBoosts = true;

  const STYLE_ID = "makerplay-visual-boosts";
  const FILTER =
    "contrast(1.06) saturate(1.08) brightness(1.02)";

  function ensureStyle() {
    let style = document.getElementById(STYLE_ID);
    if (!style) {
      style = document.createElement("style");
      style.id = STYLE_ID;
      (document.head || document.documentElement).appendChild(style);
    }
    style.textContent = [
      "canvas#GameCanvas, canvas.game-canvas, #GameCanvas, .MakerPlayGameCanvas {",
      "  image-rendering: -webkit-optimize-contrast !important;",
      "  image-rendering: pixelated !important;",
      "  filter: " + FILTER + " !important;",
      "}",
      "body.makerplay-visual-boosts canvas {",
      "  image-rendering: pixelated !important;",
      "}",
    ].join("\n");
    document.documentElement.classList.add("makerplay-visual-boosts");
    if (document.body) document.body.classList.add("makerplay-visual-boosts");
  }

  function sharpenBitmapBlit() {
    const bitmap = globalThis.Bitmap;
    if (!bitmap || !bitmap.prototype || bitmap.prototype.__makerplayVisualBoostBlit) {
      return false;
    }
    const proto = bitmap.prototype;
    if (typeof proto.blt !== "function") return false;
    const original = proto.blt;
    proto.blt = function () {
      const ctx = this._context;
      if (ctx && typeof ctx.imageSmoothingEnabled === "boolean") {
        ctx.imageSmoothingEnabled = false;
      }
      return original.apply(this, arguments);
    };
    Object.defineProperty(proto, "__makerplayVisualBoostBlit", { value: true });
    return true;
  }

  function installPixiNearest() {
    const pixi = globalThis.PIXI;
    if (!pixi || pixi.__makerplayNearest) return false;
    try {
      if (pixi.settings) {
        if (pixi.SCALE_MODES && pixi.SCALE_MODES.NEAREST != null) {
          pixi.settings.SCALE_MODE = pixi.SCALE_MODES.NEAREST;
        }
        if (typeof pixi.settings.ROUND_PIXELS === "boolean") {
          pixi.settings.ROUND_PIXELS = true;
        }
      }
      if (pixi.BaseTexture && pixi.SCALE_MODES && pixi.SCALE_MODES.NEAREST != null) {
        pixi.BaseTexture.defaultOptions =
          pixi.BaseTexture.defaultOptions || {};
        pixi.BaseTexture.defaultOptions.scaleMode = pixi.SCALE_MODES.NEAREST;
      }
    } catch (_) {
      return false;
    }
    Object.defineProperty(pixi, "__makerplayNearest", { value: true });
    return true;
  }

  function keepPixelatedCanvas() {
    const graphics = globalThis.Graphics;
    if (!graphics || graphics.__makerplayVisualBoostRes) return false;
    if (typeof graphics._width !== "number" || typeof graphics._height !== "number") {
      return false;
    }
    try {
      if (typeof graphics._updateRealScale === "function" && !graphics.__makerplayScaleHook) {
        const original = graphics._updateRealScale;
        graphics._updateRealScale = function () {
          const result = original.apply(this, arguments);
          const canvas = this._canvas || document.getElementById("GameCanvas");
          if (canvas && canvas.style) {
            canvas.style.imageRendering = "pixelated";
            canvas.style.filter = FILTER;
          }
          return result;
        };
        Object.defineProperty(graphics, "__makerplayScaleHook", { value: true });
      }
      const canvas = graphics._canvas || document.getElementById("GameCanvas");
      if (canvas && canvas.style) {
        canvas.style.imageRendering = "pixelated";
        canvas.style.filter = FILTER;
      }
    } catch (_) {
      return false;
    }
    Object.defineProperty(graphics, "__makerplayVisualBoostRes", { value: true });
    return true;
  }

  function install() {
    ensureStyle();
    sharpenBitmapBlit();
    installPixiNearest();
    keepPixelatedCanvas();
    if (!globalThis.__makerplayVisualBoostsLogged) {
      globalThis.__makerplayVisualBoostsLogged = true;
      if (globalThis.console && console.info) {
        console.info(
          "MakerPlay visual-boosts active: pixelated canvas + Pixi nearest (scale untouched)",
        );
      }
    }
  }

  install();
  let attempts = 0;
  const timer = setInterval(function () {
    attempts += 1;
    install();
    if (attempts >= 300) clearInterval(timer);
  }, 16);
})();
