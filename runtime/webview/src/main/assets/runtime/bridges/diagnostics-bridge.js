(function() {
  "use strict";
  if (window.__makerplayContextDiagnosticsInstalled) return;
  Object.defineProperty(window, "__makerplayContextDiagnosticsInstalled", { value: true });
  function send(type) {
    window.__MAKERPLAY_OBJECT_NAME__.postMessage(JSON.stringify({ v: 1, type: type }));
  }
  document.addEventListener("webglcontextlost", function(event) {
    event.preventDefault();
    send("lost");
  }, true);
  document.addEventListener("webglcontextrestored", function() {
    send("restored");
  }, true);
})();