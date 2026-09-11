(function(root) {
  "use strict";
  // --- runtime bootstrap ------------------------------------------------------------------
  // The runtime is shipped as the files in this directory concatenated into ONE function body, in
  // the order declared by RuntimeScriptAssets.COMMON_JS_PARTS (this file opens it, loader.js
  // closes it), so every file shares a single scope. This one owns the native bridge plumbing
  // and the module registry that require() reads from.

  if (typeof root.require === "function" && root.require.__makerplayCommonJs) return;
  var bridge = root.makerplayNodeNative;
  if (!bridge || typeof bridge.transact !== "function") return;
  var asyncBridge = root.makerplayNodeAsyncNative;
  var token = __MAKERPLAY_NODE_TOKEN__;
  var nextRequestId = 1;
  var pendingAsync = new Map();
  var queuedAsync = [];
  var pendingAsyncChars = 0;
  var MAX_PENDING_ASYNC = 64;
  var MAX_QUEUED_ASYNC = 1024;
  // Bounds the payload bytes a game can keep in flight, mirroring the native queue budget.
  var MAX_ASYNC_CHARS = 32 * 1024 * 1024;

  // Map a native failure reason onto an errno-style Error (missing -> ENOENT, zdata -> Z_DATA_ERROR, ...).
  function nodeError(op, path, reason) {
    var error = new Error("Node compatibility operation failed: " + op + " " + path + " (" + reason + ")");
    error.code = reason === "unsupported" ? "ENOSYS" : reason === "forbidden" ? "EACCES" : reason === "busy" ? "EBUSY" : reason === "invalid" ? "EINVAL" : reason === "exists" ? "EEXIST" : reason === "closed" ? "EBADF" : reason === "notdir" ? "ENOTDIR" : reason === "isdir" ? "EISDIR" : reason === "notempty" ? "ENOTEMPTY" : reason === "zdata" ? "Z_DATA_ERROR" : reason === "zlimit" ? "ERR_BUFFER_TOO_LARGE" : "ENOENT";
    error.path = path;
    error.syscall = op;
    return error;
  }

  // Synchronous bridge call: one JSON request/response through the Java interface.
  function transact(op, path, data, target, extra) {
    var request = Object.assign({ v: 1, id: "node-" + nextRequestId++, op: op, path: path }, extra || {});
    if (data !== undefined) request.data = data;
    if (target !== undefined) request.target = target;
    var response = JSON.parse(bridge.transact(token, JSON.stringify(request)));
    if (!response || response.ok !== true) {
      var reason = response && response.error ? response.error : "invalid-response";
      throw nodeError(op, path, reason);
    }
    return response.data;
  }

  // Asynchronous bridge call, queued and paced below so a game cannot flood the native side.
  function asyncTransact(op, path, data, target, extra) {
    if (!asyncBridge || typeof asyncBridge.postMessage !== "function") {
      return Promise.reject(nodeError(op, path, "unsupported"));
    }
    var size = (data === undefined ? 0 : String(data).length) + String(path).length;
    if (queuedAsync.length >= MAX_QUEUED_ASYNC || pendingAsyncChars + size > MAX_ASYNC_CHARS) {
      return Promise.reject(nodeError(op, path, "busy"));
    }
    var id = "node-async-" + nextRequestId++;
    var request = Object.assign({ v: 1, id: id, op: op, path: path }, extra || {});
    if (data !== undefined) request.data = data;
    if (target !== undefined) request.target = target;
    return new Promise(function(resolve, reject) {
      pendingAsyncChars += size;
      queuedAsync.push({ id: id, request: request, resolve: resolve, reject: reject, op: op, path: path, size: size });
      pumpAsync();
    });
  }

  // Resolve or reject one async call and release its queued byte budget.
  function settleAsync(call, error, value) {
    pendingAsyncChars = Math.max(0, pendingAsyncChars - (call.size || 0));
    if (error) call.reject(error);
    else call.resolve(value);
  }

  // Post queued calls while staying inside the pending and in-flight byte limits.
  function pumpAsync() {
    while (pendingAsync.size < MAX_PENDING_ASYNC && queuedAsync.length > 0) {
      var call = queuedAsync.shift();
      pendingAsync.set(call.id, call);
      try { asyncBridge.postMessage(JSON.stringify(call.request)); }
      catch (error) {
        pendingAsync.delete(call.id);
        settleAsync(call, error);
      }
    }
  }

  if (asyncBridge) asyncBridge.onmessage = function(event) {
    var response;
    try { response = JSON.parse(event.data); } catch (_) { return; }
    var call = pendingAsync.get(response && response.id);
    if (!call) return;
    pendingAsync.delete(response.id);
    if (response.ok === true) settleAsync(call, null, response.data);
    else settleAsync(call, nodeError(call.op, call.path, response.error || "invalid-response"));
    pumpAsync();
  };

  // Module registry used by require() (no prototype, so ids such as "constructor" stay safe).
  var builtins = Object.create(null);
  // Register a module under its require() id.
  function defineBuiltin(name, value) { builtins[name] = value; }
