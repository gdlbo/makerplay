(function(workerBudget) {
  "use strict";
  globalThis.__makerplayRuntimeWorkerBudget = workerBudget;
  try {
    Object.defineProperty(navigator, "hardwareConcurrency", {
      configurable: true,
      enumerable: true,
      get: () => workerBudget
    });
  } catch (_) {
    // Older WebView builds may expose this property as non-configurable.
  }
})(__MAKERPLAY_WORKER_BUDGET__);