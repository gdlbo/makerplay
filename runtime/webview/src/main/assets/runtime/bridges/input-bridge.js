(() => {
  "use strict";
  if (globalThis.__makerplayInputBridge) return;
  const ACTION_DEFAULT_KEYS = {
    up: { a: 19, d: 38, k: "ArrowUp", c: "ArrowUp" },
    down: { a: 20, d: 40, k: "ArrowDown", c: "ArrowDown" },
    left: { a: 21, d: 37, k: "ArrowLeft", c: "ArrowLeft" },
    right: { a: 22, d: 39, k: "ArrowRight", c: "ArrowRight" },
    ok: { a: 66, d: 13, k: "Enter", c: "Enter" },
    cancel: { a: 111, d: 27, k: "Escape", c: "Escape" },
    shift: { a: 59, d: 16, k: "Shift", c: "ShiftLeft", l: 1 },
    control: { a: 113, d: 17, k: "Control", c: "ControlLeft", l: 1 },
    tab: { a: 61, d: 9, k: "Tab", c: "Tab" },
    pageup: { a: 92, d: 33, k: "PageUp", c: "PageUp" },
    pagedown: { a: 93, d: 34, k: "PageDown", c: "PageDown" },
  };
  const state = {
    actions: Object.create(null),
    keys: new Map(),
    pointers: new Map(),
    pointerTargets: new Map(),
    current: { v: 1, actions: [], keys: [], pointers: [] }
  };
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
    const nextKeys = new Map();
    for (const descriptor of payload.keys) {
      if (!descriptor || !Number.isInteger(descriptor.a) || !Number.isInteger(descriptor.d)) continue;
      nextKeys.set(descriptor.a, descriptor);
    }
    for (const name of payload.actions) {
      if (typeof name === "string" && /^[a-z]+$/.test(name)) {
        wanted[name] = true;
        const defaultKey = ACTION_DEFAULT_KEYS[name];
        if (defaultKey && !nextKeys.has(defaultKey.a)) {
          let hasDomCode = false;
          for (const [, existing] of nextKeys) {
            if (existing && existing.d === defaultKey.d) {
              hasDomCode = true;
              break;
            }
          }
          if (!hasDomCode) {
            nextKeys.set(defaultKey.a, defaultKey);
          }
        }
      }
    }
    const shiftKey = Array.from(nextKeys.values()).some(key => key && key.d === 16);
    const ctrlKey = Array.from(nextKeys.values()).some(key => key && key.d === 17);
    const altKey = Array.from(nextKeys.values()).some(key => key && key.d === 18);
    const dispatchKey = (descriptor, down) => {
      const event = new KeyboardEvent(down ? "keydown" : "keyup", {
        bubbles: true, cancelable: true, key: descriptor.k, code: descriptor.c,
        location: descriptor.l, shiftKey, ctrlKey, altKey,
      });
      Object.defineProperty(event, "keyCode", { get: () => descriptor.d });
      Object.defineProperty(event, "which", { get: () => descriptor.d });
      (globalThis.document || globalThis).dispatchEvent(event);
    };
    for (const [androidCode, descriptor] of nextKeys) {
      if (!state.keys.has(androidCode)) dispatchKey(descriptor, true);
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

    const viewportWidth = document.documentElement?.clientWidth || window.innerWidth || 0;
    const viewportHeight = document.documentElement?.clientHeight || window.innerHeight || 0;
    const nextPointers = new Map();
    for (const point of payload.pointers) {
      if (!point || typeof point.id !== "string" || point.id.length > 128 || !Number.isFinite(point.x) || !Number.isFinite(point.y)) continue;
      const clientX = Math.round(point.x * viewportWidth);
      const clientY = Math.round(point.y * viewportHeight);
      const canvasPoint = toCanvasPoint(point.x, point.y);
      nextPointers.set(point.id, { point, clientX, clientY, canvasPoint });
      const previous = state.pointers.get(point.id);
      if (!previous) {
        if (globalThis.TouchInput && typeof TouchInput._onTrigger === "function") {
          TouchInput._screenPressed = true;
          TouchInput._pressedTime = 0;
          TouchInput._onTrigger(canvasPoint.x, canvasPoint.y);
        }
        if (globalThis.document && typeof document.elementFromPoint === "function") {
          const target = document.elementFromPoint(clientX, clientY) || document.body || document.documentElement;
          state.pointerTargets.set(point.id, target);
          const mouseProps = { bubbles: true, cancelable: true, view: window, clientX, clientY, screenX: clientX, screenY: clientY, button: 0, buttons: 1 };
          if (typeof PointerEvent === "function") {
            try { target.dispatchEvent(new PointerEvent("pointerdown", { ...mouseProps, pointerId: 1, isPrimary: true })); } catch (_) {}
          }
          try { target.dispatchEvent(new MouseEvent("mousedown", mouseProps)); } catch (_) {}
        }
      }
      else if (previous.clientX !== clientX || previous.clientY !== clientY) {
        if (globalThis.TouchInput && typeof TouchInput._onMove === "function") {
          TouchInput._onMove(canvasPoint.x, canvasPoint.y);
        }
        const target = state.pointerTargets.get(point.id) || (globalThis.document && typeof document.elementFromPoint === "function" && document.elementFromPoint(clientX, clientY)) || document.body || document.documentElement;
        if (target) {
          const mouseProps = { bubbles: true, cancelable: true, view: window, clientX, clientY, screenX: clientX, screenY: clientY, button: 0, buttons: 1 };
          if (typeof PointerEvent === "function") {
            try { target.dispatchEvent(new PointerEvent("pointermove", { ...mouseProps, pointerId: 1, isPrimary: true })); } catch (_) {}
          }
          try { target.dispatchEvent(new MouseEvent("mousemove", mouseProps)); } catch (_) {}
        }
      }
    }
    for (const [id, previous] of state.pointers) if (!nextPointers.has(id)) {
      if (globalThis.TouchInput && typeof TouchInput._onRelease === "function") {
        TouchInput._onRelease(previous.canvasPoint.x, previous.canvasPoint.y);
      }
      const target = state.pointerTargets.get(id) || (globalThis.document && typeof document.elementFromPoint === "function" && document.elementFromPoint(previous.clientX, previous.clientY)) || document.body || document.documentElement;
      state.pointerTargets.delete(id);
      if (target) {
        const mouseProps = { bubbles: true, cancelable: true, view: window, clientX: previous.clientX, clientY: previous.clientY, screenX: previous.clientX, screenY: previous.clientY, button: 0, buttons: 0 };
        if (typeof PointerEvent === "function") {
          try { target.dispatchEvent(new PointerEvent("pointerup", { ...mouseProps, pointerId: 1, isPrimary: true })); } catch (_) {}
        }
        try { target.dispatchEvent(new MouseEvent("mouseup", mouseProps)); } catch (_) {}
        try { target.dispatchEvent(new MouseEvent("click", mouseProps)); } catch (_) {}
      }
    }
    if (globalThis.TouchInput) {
      TouchInput._screenPressed = nextPointers.size > 0;
    }
    state.pointers = nextPointers;
  };
  globalThis.__makerplayApplyInputSnapshot = apply;
  const pending = globalThis.__makerplayPendingInputSnapshots;
  delete globalThis.__makerplayPendingInputSnapshots;
  if (Array.isArray(pending)) for (const payload of pending) apply(payload);
  const clear = () => {
    state.pointerTargets.clear();
    apply({ v: 1, actions: [], keys: [], pointers: [] });
  };
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
