(() => {
  "use strict";
  if (globalThis.__makerplayInputBridge) return;
  const state = { actions: Object.create(null), keys: new Map(), pointers: new Map(), current: { v: 1, actions: [], keys: [], pointers: [] } };
  // Host overlay pointers are normalized 0..1 fractions of the fullscreen overlay /
  // WebView viewport. Expand to CSS client space, then through GameCanvas's client
  // rect so letterboxed/transformed hits match the finger without density drift.
  const toCanvasPoint = (normX, normY) => {
    const viewportWidth = document.documentElement?.clientWidth || window.innerWidth || 0;
    const viewportHeight = document.documentElement?.clientHeight || window.innerHeight || 0;
    const x = normX * viewportWidth;
    const y = normY * viewportHeight;
    const graphics = globalThis.Graphics;
    const canvas = graphics && graphics._canvas;
    if (canvas) {
      const rect = canvas.getBoundingClientRect();
      if (rect.width > 0 && rect.height > 0) {
        return {
          x: Math.round((x - rect.left) * canvas.width / rect.width),
          y: Math.round((y - rect.top) * canvas.height / rect.height),
        };
      }
    }
    if (graphics && typeof graphics.pageToCanvasX === "function" && typeof graphics.pageToCanvasY === "function") {
      return { x: graphics.pageToCanvasX(x), y: graphics.pageToCanvasY(y) };
    }
    return { x: Math.round(x), y: Math.round(y) };
  };
  const apply = payload => {
    if (!payload || payload.v !== 1 || !Array.isArray(payload.actions) || !Array.isArray(payload.keys) || !Array.isArray(payload.pointers)) return;
    if (payload.actions.length > 16 || payload.keys.length > 80 || payload.pointers.length > 16) return;
    state.current = payload;
    // Some MV/MZ plugins poll input during boot before game objects exist.
    // Keep the latest snapshot and apply it on the next engine input poll.
    if (!globalThis.$gameVariables || typeof globalThis.$gameVariables.setValue !== "function") return;
    const wanted = Object.create(null);
    for (const name of payload.actions) {
      if (typeof name === "string" && /^[a-z]+$/.test(name)) wanted[name] = true;
    }
    const nextKeys = new Map();
    const shiftKey = payload.keys.some(key => key && key.d === 16);
    const ctrlKey = payload.keys.some(key => key && key.d === 17);
    const altKey = payload.keys.some(key => key && key.d === 18);
    const dispatchKey = (descriptor, down) => {
      const event = new KeyboardEvent(down ? "keydown" : "keyup", {
        bubbles: true, cancelable: true, key: descriptor.k, code: descriptor.c,
        location: descriptor.l, shiftKey, ctrlKey, altKey,
      });
      Object.defineProperty(event, "keyCode", { get: () => descriptor.d });
      Object.defineProperty(event, "which", { get: () => descriptor.d });
      (globalThis.document || globalThis).dispatchEvent(event);
    };
    for (const descriptor of payload.keys) {
      if (!descriptor || !Number.isInteger(descriptor.a) || !Number.isInteger(descriptor.d)) continue;
      nextKeys.set(descriptor.a, descriptor);
      if (!state.keys.has(descriptor.a)) dispatchKey(descriptor, true);
      if (globalThis.Input && Input.keyMapper) {
        const name = Input.keyMapper[descriptor.d];
        if (typeof name === "string" && /^[a-z]+$/.test(name)) wanted[name] = true;
      }
    }
    for (const [androidCode, descriptor] of state.keys) if (!nextKeys.has(androidCode)) dispatchKey(descriptor, false);
    state.keys = nextKeys;
    if (globalThis.Input && Input._currentState) {
      for (const name in state.actions) if (!wanted[name]) Input._currentState[name] = false;
      for (const name in wanted) Input._currentState[name] = true;
    }
    state.actions = wanted;
    if (globalThis.TouchInput && typeof TouchInput._onTrigger === "function") {
      const next = new Map();
      for (const point of payload.pointers) {
        if (!point || typeof point.id !== "string" || point.id.length > 128 || !Number.isFinite(point.x) || !Number.isFinite(point.y)) continue;
        const canvasPoint = toCanvasPoint(point.x, point.y);
        next.set(point.id, point);
        const previous = state.pointers.get(point.id);
        if (!previous) {
          TouchInput._screenPressed = true;
          TouchInput._pressedTime = 0;
          TouchInput._onTrigger(canvasPoint.x, canvasPoint.y);
        }
        else if (previous.x !== point.x || previous.y !== point.y) {
          TouchInput._onMove(canvasPoint.x, canvasPoint.y);
        }
      }
      for (const [id, previous] of state.pointers) if (!next.has(id)) {
        const release = toCanvasPoint(previous.x, previous.y);
        TouchInput._onRelease(release.x, release.y);
      }
      TouchInput._screenPressed = next.size > 0;
      state.pointers = next;
    }
  };
  globalThis.__makerplayApplyInputSnapshot = apply;
  const pending = globalThis.__makerplayPendingInputSnapshots;
  delete globalThis.__makerplayPendingInputSnapshots;
  if (Array.isArray(pending)) for (const payload of pending) apply(payload);
  const clear = () => apply({ v: 1, actions: [], keys: [], pointers: [] });
  globalThis.addEventListener("blur", clear);
  globalThis.addEventListener("pagehide", clear);
  if (globalThis.document) globalThis.document.addEventListener("visibilitychange", () => {
    if (globalThis.document.hidden) clear();
  });
  const install = () => {
    if (!globalThis.Input || typeof Input._pollGamepads !== "function") return false;
    if (Input.__makerplayInputWrapped) return true;
    const pollGamepads = Input._pollGamepads;
    Input._pollGamepads = function() {
      const result = pollGamepads.call(this);
      apply(state.current);
      return result;
    };
    Input.__makerplayInputWrapped = true;
    apply(state.current);
    return true;
  };
  if (!install()) {
    let attempts = 0;
    const timer = setInterval(() => {
      attempts += 1;
      if (install() || attempts >= 200) clearInterval(timer);
    }, 50);
  }
  globalThis.__makerplayInputBridge = true;
})();
