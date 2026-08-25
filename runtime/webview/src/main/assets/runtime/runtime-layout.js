(() => {
  const patchCanvasReadback = (prototype) => {
    const nativeGetContext = prototype?.getContext;
    if (typeof nativeGetContext !== 'function' || nativeGetContext.__makerplayWillReadFrequently) return;
    const getContext = function(type, attributes) {
      if (type !== '2d') return nativeGetContext.apply(this, arguments);
      return nativeGetContext.call(this, type, {
        ...(attributes || {}),
        willReadFrequently: true
      });
    };
    getContext.__makerplayWillReadFrequently = true;
    prototype.getContext = getContext;
  };
  patchCanvasReadback(globalThis.HTMLCanvasElement?.prototype);
  patchCanvasReadback(globalThis.OffscreenCanvas?.prototype);

  const config = globalThis.__makerplayRuntimeConfig || {};
  const content = 'width=device-width,initial-scale=1,minimum-scale=1,maximum-scale=1,user-scalable=no';
  const gameElements = '#GameCanvas, #UpperCanvas, #GameVideo, #gameCanvas, #upperCanvas, #gameVideo';
  const installCanvasCoordinateMapping = () => {
    const graphics = globalThis.Graphics;
    if (!graphics || graphics.__makerplayCanvasCoordinateMapping ||
        typeof graphics.pageToCanvasX !== 'function' || typeof graphics.pageToCanvasY !== 'function') return false;
    const pageToCanvas = (pageCoordinate, axis) => {
      const canvas = graphics._canvas;
      if (!canvas) return 0;
      // getBoundingClientRect is viewport-relative; page coords include scroll.
      const rect = canvas.getBoundingClientRect();
      const displayedSize = axis === 'x' ? rect.width : rect.height;
      const canvasSize = axis === 'x' ? canvas.width : canvas.height;
      const clientCoordinate = pageCoordinate - (axis === 'x' ? window.scrollX : window.scrollY);
      const displayedOffset = axis === 'x' ? rect.left : rect.top;
      if (!displayedSize || !canvasSize) return 0;
      return Math.round((clientCoordinate - displayedOffset) * canvasSize / displayedSize);
    };
    graphics.pageToCanvasX = x => pageToCanvas(x, 'x');
    graphics.pageToCanvasY = y => pageToCanvas(y, 'y');
    graphics.__makerplayCanvasCoordinateMapping = true;
    return true;
  };
  const fitGameElements = () => {
    const viewportWidth = document.documentElement?.clientWidth || window.innerWidth;
    const viewportHeight = document.documentElement?.clientHeight || window.innerHeight;
    document.querySelectorAll(gameElements).forEach((element) => {
      const sourceWidth = Number(element.width) || Number(element.getAttribute('width'));
      const sourceHeight = Number(element.height) || Number(element.getAttribute('height'));
      if (!sourceWidth || !sourceHeight || !viewportWidth || !viewportHeight) return;
      const fitScale = Math.min(viewportWidth / sourceWidth, viewportHeight / sourceHeight);
      const integerScale = Math.floor(fitScale);
      const scale = config.scaleMode === 'INTEGER' && integerScale >= 1 ? integerScale : fitScale;
      element.style.setProperty('position', 'fixed', 'important');
      element.style.setProperty('left', '50%', 'important');
      element.style.setProperty('top', '50%', 'important');
      element.style.setProperty('right', 'auto', 'important');
      element.style.setProperty('bottom', 'auto', 'important');
      element.style.setProperty('margin', '0', 'important');
      element.style.setProperty('transform', 'translate(-50%, -50%)', 'important');
      const width = config.scaleMode === 'STRETCH' ? viewportWidth : sourceWidth * scale;
      const height = config.scaleMode === 'STRETCH' ? viewportHeight : sourceHeight * scale;
      element.style.setProperty('width', `${width}px`, 'important');
      element.style.setProperty('height', `${height}px`, 'important');
      element.style.setProperty('image-rendering', config.pixelSmoothing === false ? 'pixelated' : 'auto', 'important');
    });
  };
  const applyViewport = () => {
    installCanvasCoordinateMapping();
    let viewport = document.querySelector('meta[name="viewport"]');
    if (!viewport && document.head) {
      viewport = document.createElement('meta');
      viewport.name = 'viewport';
      document.head.appendChild(viewport);
    }
    if (viewport && viewport.content !== content) viewport.content = content;
    const root = document.documentElement;
    if (root) {
      root.style.width = '100%';
      root.style.height = '100%';
      root.style.margin = '0';
      root.style.overflow = 'hidden';
    }
    if (document.body) {
      document.body.style.width = '100%';
      document.body.style.height = '100%';
      document.body.style.margin = '0';
      document.body.style.overflow = 'hidden';
    }
    fitGameElements();
  };
  applyViewport();
  if (!globalThis.__makerplayViewportObserver) {
    const observer = new MutationObserver(applyViewport);
    observer.observe(document, { childList: true, subtree: true });
    globalThis.__makerplayViewportObserver = observer;
    window.addEventListener('DOMContentLoaded', () => {
      applyViewport();
      requestAnimationFrame(() => {
        window.dispatchEvent(new Event('resize'));
        fitGameElements();
      });
    }, { once: true });
    window.addEventListener('resize', () => requestAnimationFrame(fitGameElements), { passive: true });
  } else {
    requestAnimationFrame(() => {
      window.dispatchEvent(new Event('resize'));
      fitGameElements();
    });
  }
})();