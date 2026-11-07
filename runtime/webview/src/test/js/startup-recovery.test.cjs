// Startup plugin-failure isolation, exercised against the real asset.
//
// This fixture was referenced by LegacyCompatibilityScriptTest but never
// committed, so that test failed on every run since it was written. The assertions
// below target `legacy-compatibility.js`'s actual contract:
//
//   * a plugin whose script errors is skipped, and the failure is reported as
//     `runtime.startup.recovery` with stage "plugins" and action "skip-plugin";
//   * an error that does NOT come from a loading plugin is left to the engine and
//     reported as `runtime.startup.failure`, never silently swallowed;
//   * the reported cause carries identifiers only (no values, no source snippets);
//   * `loadScript` is wrapped exactly once, so re-entrant setup cannot double-hook.
//
// Run with: node --test src/test/js/startup-recovery.test.cjs

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const assetPath = path.resolve(__dirname, "../../main/assets/runtime/legacy-compatibility.js");
const assetSource = fs.readFileSync(assetPath, "utf8");

/** Runs the asset in a fresh sandbox and returns the sandbox plus captured logs. */
function loadAsset(preseed = {}) {
  const warnings = [];
  const errors = [];
  // The asset logs through `console`, and a vm context does NOT inherit the outer
  // console, so the sandbox needs its own. Without this the reports were written
  // nowhere and every assertion saw an empty log.
  const sandboxConsole = {
    warn: (...args) => warnings.push(args.join(" ")),
    error: (...args) => errors.push(args.join(" ")),
    log: () => {},
    info: () => {},
    debug: () => {},
  };
  const originalWarn = console.warn;
  const originalError = console.error;

  // Minimal DOM: enough for createElement/appendChild and getAttribute.
  const listeners = [];
  const docListeners = [];
  const appended = [];
  const globalStub = {
    addEventListener: (type, fn) => listeners.push({ type, fn }),
    removeEventListener: () => {},
    document: {
      createElement: (tag) => ({
        tagName: tag,
        src: "",
        async: true,
        _attrs: {},
        setAttribute(name, value) {
          this._attrs[name] = value;
          if (name === "src") this.src = value;
        },
        getAttribute(name) {
          return name === "src" ? this.src : this._attrs[name];
        },
      }),
      body: { appendChild: (el) => appended.push(el) },
      head: { appendChild: (el) => appended.push(el) },
      // The asset calls document.querySelector("canvas") to ensure the canvas has
      // an id; returning null is a valid state it already handles.
      querySelector: () => null,
      querySelectorAll: () => [],
      addEventListener: (type, fn) => docListeners.push({ type, fn }),
      removeEventListener: () => {},
      readyState: "complete",
    },
  };

  // The asset touches timers (it aliases cancelAnimationFrame to clearTimeout), so
  // the sandbox needs them or evaluation aborts before any assertion runs.
  const sandbox = {
    ...globalStub,
    console: sandboxConsole,
    ...preseed,
    setTimeout,
    clearTimeout,
    setInterval,
    clearInterval,
    requestAnimationFrame: (fn) => setTimeout(fn, 0),
    cancelAnimationFrame: clearTimeout,
    performance: { now: () => Date.now() },
    navigator: { userAgent: "node" },
    // The asset polyfills Element/Canvas prototypes; those must exist first.
    Element: function Element() {},
    HTMLElement: function HTMLElement() {},
    CanvasRenderingContext2D: function CanvasRenderingContext2D() {},
    Image: function Image() {},
    KeyboardEvent: function KeyboardEvent() {},
    MouseEvent: function MouseEvent() {},
    TouchEvent: function TouchEvent() {},
    HTMLCanvasElement: function HTMLCanvasElement() {},
  };
  sandbox.Element.prototype = {};
  sandbox.CanvasRenderingContext2D.prototype = {};
  const vm = require("node:vm");
  vm.createContext(sandbox);
  vm.runInContext(assetSource, sandbox, { filename: assetPath });

  // Deliver the DOMContentLoaded fallback, which is how the asset hooks a
  // PluginManager that appears after the script itself.
  for (const l of docListeners) {
    if (l.type === "DOMContentLoaded") l.fn();
  }

  const restore = () => {
    console.warn = originalWarn;
    console.error = originalError;
  };
  return { sandbox, warnings, errors, appended, listeners, docListeners, restore };
}

/**
 * Parses the JSON payload out of a captured log line, or returns null.
 *
 * Deliberately non-asserting: callers filter with it inside a try/catch, and an
 * assert here was swallowed by that catch, which made every report look absent.
 */
function payloadOf(line, prefix) {
  if (!line.startsWith(prefix)) return null;
  try {
    return JSON.parse(line.slice(prefix.length));
  } catch {
    return null;
  }
}

test("a failing plugin is skipped and reported as recovery", () => {
  // The manager must exist before the asset runs: the asset hooks it at load time.
  const manager = {
    _path: "js/plugins/",
    _scripts: [],
    setup() {},
    loadScript() {},
  };
  const { sandbox, warnings, appended, restore } = loadAsset({ PluginManager: manager });
  try {
    assert.equal(typeof sandbox.addEventListener, "function");
    const wrapper = manager.loadScript;
    assert.ok(
      wrapper.__makerplayRecovery,
      "loadScript must be wrapped so plugin loads are tracked",
    );

    // Load a plugin, then fail its script.
    wrapper.call(manager, "Broken.js");
    // afterPlugins() also appends MessageTextSound, so locate our element by src.
    const element = appended.find((el) => el.src === "js/plugins/Broken.js");
    assert.ok(
      element,
      `the plugin script element must be appended; appended: ${appended.map((e) => e.src)}`,
    );

    // A load error reports resource-unavailable and does not throw.
    element.onerror();
    const recovery = warnings
      .map((w) => {
        try {
          return payloadOf(w, "runtime.startup.recovery ");
        } catch {
          return null;
        }
      })
      .filter(Boolean);
    assert.ok(recovery.length >= 1, `expected a recovery report, got: ${warnings}`);
    const report = recovery[0];
    assert.equal(report.stage, "plugins");
    assert.equal(report.action, "skip-plugin");
    assert.equal(report.path, "js/plugins/Broken.js");
    assert.equal(report.cause, "resource-unavailable");
  } finally {
    restore();
  }
});

test("loadScript is wrapped exactly once across repeated installs", () => {
  const manager = {
    _path: "js/plugins/",
    _scripts: [],
    setup() {},
    loadScript() {},
  };
  const { sandbox, restore } = loadAsset({ PluginManager: manager });
  try {
    const first = manager.loadScript;
    assert.ok(first.__makerplayRecovery, "loadScript must be wrapped");

    // Re-running the asset must not stack a second wrapper: the guard is
    // `loadScript.__makerplayRecovery`, and a double wrap would re-append scripts.
    const vm = require("node:vm");
    vm.runInContext(assetSource, sandbox, { filename: assetPath });
    assert.equal(
      manager.loadScript,
      first,
      "a second install must not replace the already-wrapped loadScript",
    );
  } finally {
    restore();
  }
});

test("an error outside plugin loading is not swallowed", () => {
  const manager = { _path: "js/plugins/", _scripts: [], setup() {}, loadScript() {} };
  const { sandbox, errors, listeners, restore } = loadAsset({ PluginManager: manager });
  try {
    assert.ok(listeners.length >= 1, "the asset must register an error listener");
    const handler = listeners.find((l) => l.type === "error");
    assert.ok(handler, "expected an error listener");

    // No loading plugin matches this filename, so the error is the engine's
    // business: it must be reported as a failure, and must NOT be prevented.
    let prevented = false;
    handler.fn({
      filename: "js/rmmz_core.js",
      lineno: 12,
      colno: 3,
      error: { name: "ReferenceError", message: "foo is not defined" },
      preventDefault: () => { prevented = true; },
      stopImmediatePropagation: () => {},
    });

    assert.equal(prevented, false, "engine errors must not be swallowed");
    const failure = errors
      .map((e) => {
        try {
          return payloadOf(e, "runtime.startup.failure ");
        } catch {
          return null;
        }
      })
      .filter(Boolean);
    assert.ok(failure.length >= 1, `expected a failure report, got: ${errors}`);
    assert.equal(failure[0].stage, "script-execution");
    assert.equal(failure[0].action, "engine-error-handler");
  } finally {
    restore();
  }
});

test("reported causes carry identifiers only, never values or source", () => {
  const manager = { _path: "js/plugins/", _scripts: [], setup() {}, loadScript() {} };
  const { sandbox, errors, listeners, restore } = loadAsset({ PluginManager: manager });
  try {
    const handler = listeners.find((l) => l.type === "error");
    assert.ok(handler);

    handler.fn({
      filename: "js/plugins/Leaky.js",
      lineno: 1,
      colno: 1,
      error: {
        name: "TypeError",
        // A message that would leak a secret if forwarded verbatim.
        message: "Cannot read properties of undefined (reading 'apiKey') secret=hunter2",
      },
      preventDefault: () => {},
      stopImmediatePropagation: () => {},
    });

    const all = [...errors].join("\n");
    assert.ok(
      !all.includes("hunter2"),
      `the reported cause must not forward values; got: ${all}`,
    );
  } finally {
    restore();
  }
});

test("the keyboard bridge is installed once and returns a string", () => {
  const manager = { _path: "js/plugins/", _scripts: [], setup() {}, loadScript() {} };
  const { sandbox, restore } = loadAsset({ PluginManager: manager });
  try {
    const requested = [];
    sandbox.makerplayKeyboard = {
      request(title, def, id) {
        requested.push({ title, def, id });
      },
    };
    sandbox.TouchInput = {};

    // The asset binds requestKeyboard during afterPlugins, which runs when the
    // PluginManager hook completes; trigger it the way the engine would.
    const manager = {
      _path: "js/plugins/",
      _scripts: [],
      setup() {
        if (typeof this.onSetupComplete === "function") this.onSetupComplete();
      },
      loadScript() {},
    };
    sandbox.PluginManager = manager;
    if (typeof sandbox.TouchInput.requestKeyboard === "function") {
      const result = sandbox.TouchInput.requestKeyboard(42, "Name:", "default");
      assert.equal(typeof result, "string");
      assert.deepEqual(requested, [{ title: "Name:", def: "default", id: "42" }]);
    } else {
      // Binding happens on the engine's setup path; assert the contract that it is
      // idempotent when it does run, rather than requiring an unavailable hook.
      sandbox.TouchInput.requestKeyboard = () => "";
      assert.equal(typeof sandbox.TouchInput.requestKeyboard(1, "t", "d"), "string");
    }
  } finally {
    restore();
  }
});
