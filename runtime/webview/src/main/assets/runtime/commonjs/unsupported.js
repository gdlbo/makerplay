  // --- unsupported APIs -------------------------------------------------------------------
  // Modules the sandbox refuses to emulate. They fail with ENOSYS instead of silently doing
  // nothing: child_process (no host processes), http/https (no sockets) and worker_threads.

  function unsupportedApi(message) {
    var error = new Error(message + " is not supported by MakerPlay");
    error.code = "ENOSYS";
    return error;
  }
  defineBuiltin("worker_threads", {
    isMainThread: true,
    parentPort: null,
    workerData: null,
    Worker: function() { throw unsupportedApi("worker_threads"); },
  });

  // child_process is deliberately non-functional: games must not spawn host processes.
  function unsupportedProcess() {
    var error = new Error("child_process is not supported by MakerPlay");
    error.code = "ENOSYS";
    return error;
  }
  function fakeChildProcess(callback) {
    var child = new EventEmitter();
    child.pid = 0;
    child.killed = false;
    child.stdin = { write: function() { return false; }, end: function() {} };
    child.stdout = new EventEmitter();
    child.stderr = new EventEmitter();
    child.kill = function() { child.killed = true; return true; };
    setTimeout(function() {
      if (typeof callback === "function") callback(unsupportedProcess(), "", "");
      child.emit("close", 1, null);
      child.emit("exit", 1, null);
    }, 0);
    return child;
  }
  // The callback API reports ENOSYS; the sync API throws so callers see the failure immediately.
  var childProcessModule = {};
  ["exec", "execFile", "spawn", "fork"].forEach(function(name) {
    childProcessModule[name] = function() {
      var callback = Array.prototype.filter.call(arguments, function(value) { return typeof value === "function"; })[0];
      return fakeChildProcess(callback);
    };
  });
  ["execSync", "execFileSync"].forEach(function(name) {
    childProcessModule[name] = function() { throw unsupportedProcess(); };
  });
  childProcessModule.spawnSync = function() {
    var error = unsupportedProcess();
    return { pid: 0, output: [null, Buffer.alloc(0), Buffer.from(error.message)], stdout: Buffer.alloc(0), stderr: Buffer.from(error.message), status: 1, signal: null, error: error };
  };
  defineBuiltin("child_process", childProcessModule);
  // Outbound network and local servers are stubbed: the runtime serves game assets on its own
  // origin instead, so the sandbox never opens sockets.
  function unsupportedNetwork(message) {
    var error = new Error(message + " are not supported by MakerPlay");
    error.code = "ENOSYS";
    return error;
  }
  function fakeHttpClientRequest() {
    var request = new EventEmitter();
    ["end", "abort", "destroy", "flushHeaders", "setHeader", "removeHeader", "setNoDelay", "setSocketKeepAlive"]
      .forEach(function(name) { request[name] = function() {}; });
    request.write = function() { return false; };
    request.getHeader = function() { return undefined; };
    request.setTimeout = function(timeout, callback) { if (typeof callback === "function") callback(); return request; };
    setTimeout(function() { request.emit("error", unsupportedNetwork("network requests")); }, 0);
    return request;
  }
  function fakeHttpServer() {
    var server = new EventEmitter();
    server.listen = function() {
      var callback = Array.prototype.filter.call(arguments, function(value) { return typeof value === "function"; })[0];
      setTimeout(function() {
        var error = unsupportedNetwork("http servers");
        server.emit("error", error);
        if (typeof callback === "function") callback(error);
      }, 0);
      return server;
    };
    server.close = function(callback) { if (typeof callback === "function") callback(); return server; };
    server.address = function() { return null; };
    return server;
  }
  var httpModule = {
    Agent: function() {},
    globalAgent: { keepAlive: false, keepAliveMsecs: 1000, maxSockets: Infinity, maxFreeSockets: 256, sockets: {}, freeSockets: {}, requests: {} },
    STATUS_CODES: {},
    METHODS: [],
    createServer: function() { return fakeHttpServer(); },
    createClient: function() { return fakeHttpClientRequest(); },
    request: function() { return fakeHttpClientRequest(); },
    get: function() { return fakeHttpClientRequest(); },
  };
  defineBuiltin("http", httpModule);
  defineBuiltin("https", httpModule);
