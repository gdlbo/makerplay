(() => {
  "use strict";
  const config = globalThis.__makerplayRuntimeConfig || {};
  const fpsLimit = Number(config.fpsLimit);
  const limitEnabled = [30, 60, 90, 120, 144].includes(fpsLimit);
  const showCounter = config.showFpsCounter === true;
  if ((!limitEnabled && !showCounter) || globalThis.__makerplayFrameRateInstalled) return;
  globalThis.__makerplayFrameRateInstalled = true;

  const nativeRequest = globalThis.requestAnimationFrame.bind(globalThis);
  const nativeCancel = globalThis.cancelAnimationFrame.bind(globalThis);
  const interval = limitEnabled ? 1000 / fpsLimit : 0;
  const callbacks = new Map();
  let sequence = 0;
  let nextFrameAt = performance.now();
  let nativeId = 0;
  let sampledFrames = 0;
  let sampleStartedAt = performance.now();

  const counterElement = () => {
    if (!showCounter) return null;
    let counter = document.getElementById('makerplay-fps-counter');
    if (counter) return counter;
    counter = document.createElement('div');
    counter.id = 'makerplay-fps-counter';
    counter.setAttribute('aria-hidden', 'true');
    counter.textContent = '-- FPS';
    Object.assign(counter.style, {
      position: 'fixed',
      top: 'calc(12px + env(safe-area-inset-top))',
      left: 'calc(12px + env(safe-area-inset-left))',
      zIndex: '2147483647',
      padding: '5px 8px',
      borderRadius: '4px',
      background: 'rgba(0, 0, 0, 0.72)',
      color: '#ffffff',
      font: '600 12px monospace',
      lineHeight: '16px',
      pointerEvents: 'none',
      userSelect: 'none',
    });
    document.documentElement?.appendChild(counter);
    return counter;
  };
  const updateCounter = () => {
    const timestamp = performance.now();
    const elapsed = timestamp - sampleStartedAt;
    const counter = counterElement();
    if (counter) counter.textContent = `${Math.round(sampledFrames * 1000 / elapsed)} FPS`;
    sampledFrames = 0;
    sampleStartedAt = timestamp;
  };
  if (showCounter) setInterval(updateCounter, 500);

  if (!limitEnabled) {
    const sampleNativeFrame = () => {
      sampledFrames += 1;
      nativeRequest(sampleNativeFrame);
    };
    nativeRequest(sampleNativeFrame);
    return;
  }

  const schedule = () => {
    if (!nativeId && callbacks.size) nativeId = nativeRequest(tick);
  };
  const tick = timestamp => {
    nativeId = 0;
    if (timestamp + 0.5 >= nextFrameAt) {
      do {
        nextFrameAt += interval;
      } while (nextFrameAt <= timestamp + 0.5);
      const pending = Array.from(callbacks.values());
      callbacks.clear();
      if (pending.length) sampledFrames += 1;
      pending.forEach(callback => callback(timestamp));
    }
    schedule();
  };

  globalThis.requestAnimationFrame = callback => {
    const id = ++sequence;
    callbacks.set(id, callback);
    schedule();
    return id;
  };
  globalThis.cancelAnimationFrame = id => {
    callbacks.delete(id);
    if (!callbacks.size && nativeId) {
      nativeCancel(nativeId);
      nativeId = 0;
    }
  };
})();