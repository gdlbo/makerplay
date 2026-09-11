  // --- process ----------------------------------------------------------------------------
  // Node process surface: cwd/env/argv, exit, hrtime, memoryUsage, stdio and the stdin stub.

  // process is an EventEmitter so games can install exit / uncaughtException handlers.
  var processModule = new EventEmitter();
  processModule.browser = true;
  processModule.platform = "android";
  processModule.arch = "arm64";
  processModule.argv = ["/game", ""];
  processModule.env = { HOME: "/data", HOMEPATH: "/data", PWD: "/game", USER: "joiplay", LOGNAME: "joiplay", MAKERPLAY: "1", NODE_ENV: "production" };
  processModule.cwd = function() { return "/game"; };
  processModule.chdir = function() { throw new Error("process.chdir is not supported"); };
  processModule.nextTick = function(callback) { var args = Array.prototype.slice.call(arguments, 1); queueMicrotask(function() { callback.apply(null, args); }); };
  processModule.version = "v18.0.0";
  processModule.execPath = "/game";
  processModule.execArgv = [];
  processModule.pid = 1;
  processModule.title = "MakerPlay";
  processModule.exitCode = 0;
  // Heap numbers come from performance.memory when the WebView exposes it.
  processModule.memoryUsage = function() {
    var memory = typeof performance === "object" && performance ? performance.memory : null;
    var used = memory ? memory.usedJSHeapSize : 0;
    var total = memory ? memory.totalJSHeapSize : used;
    return { rss: used, heapTotal: total, heapUsed: used, external: 0, arrayBuffers: 0 };
  };
  processModule.umask = function() { return 0; };
  processModule.hasUncaughtExceptionCaptureCallback = function() { return false; };
  processModule.stdin = { isTTY: false, on: function() { return this; }, once: function() { return this; }, off: function() { return this; }, resume: function() { return this; }, pause: function() { return this; }, setEncoding: function() { return this; }, read: function() { return null; }, destroy: function() {} };
  processModule.exit = function(code) {
    code = code === undefined ? processModule.exitCode : Number(code) || 0;
    processModule.exitCode = code;
    processModule.emit("exit", code);
    var error = new Error("process.exit(" + code + ") requested by the game");
    error.code = "PROCESS_EXIT";
    error.exitCode = code;
    throw error;
  };
  processModule.emitWarning = function(message) { console.warn(message); };
  processModule._rawDebug = function() { console.debug.apply(console, arguments); };
  processModule.stdout = { isTTY: false, write: function(value) { console.log(String(value).replace(/\n$/, "")); return true; } };
  processModule.stderr = { isTTY: false, write: function(value) { console.error(String(value).replace(/\n$/, "")); return true; } };
  processModule.uptime = function() { return performance.now() / 1000; };
  processModule.hrtime = function(previous) {
    var now = performance.now();
    var seconds = Math.floor(now / 1000), nanos = Math.floor((now % 1000) * 1000000);
    if (!previous) return [seconds, nanos];
    var delta = seconds - previous[0], nanoDelta = nanos - previous[1];
    if (nanoDelta < 0) { delta--; nanoDelta += 1000000000; }
    return [delta, nanoDelta];
  };
  processModule.versions = { node: "18.0.0", nw: "0.0.0", chromium: navigator.userAgent };


  defineBuiltin("process", processModule);
