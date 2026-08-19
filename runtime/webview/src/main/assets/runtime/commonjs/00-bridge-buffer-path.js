(function(root) {
  "use strict";

  if (typeof root.require === "function" && root.require.__makerplayCommonJs) return;
  var bridge = root.makerplayNodeNative;
  if (!bridge || typeof bridge.transact !== "function") return;
  var asyncBridge = root.makerplayNodeAsyncNative;
  var token = __MAKERPLAY_NODE_TOKEN__;
  var nextRequestId = 1;
  var pendingAsync = new Map();
  var queuedAsync = [];
  var MAX_PENDING_ASYNC = 64;
  var MAX_QUEUED_ASYNC = 1024;

  function nodeError(op, path, reason) {
    var error = new Error("Node compatibility operation failed: " + op + " " + path + " (" + reason + ")");
    error.code = reason === "unsupported" ? "ENOSYS" : reason === "forbidden" ? "EACCES" : reason === "busy" ? "EBUSY" : reason === "invalid" ? "EINVAL" : reason === "exists" ? "EEXIST" : reason === "closed" ? "EBADF" : "ENOENT";
    error.path = path;
    error.syscall = op;
    return error;
  }

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

  function asyncTransact(op, path, data, target, extra) {
    if (!asyncBridge || typeof asyncBridge.postMessage !== "function") {
      return Promise.reject(nodeError(op, path, "unsupported"));
    }
    if (queuedAsync.length >= MAX_QUEUED_ASYNC) return Promise.reject(nodeError(op, path, "busy"));
    var id = "node-async-" + nextRequestId++;
    var request = Object.assign({ v: 1, id: id, op: op, path: path }, extra || {});
    if (data !== undefined) request.data = data;
    if (target !== undefined) request.target = target;
    return new Promise(function(resolve, reject) {
      queuedAsync.push({ id: id, request: request, resolve: resolve, reject: reject, op: op, path: path });
      pumpAsync();
    });
  }

  function pumpAsync() {
    while (pendingAsync.size < MAX_PENDING_ASYNC && queuedAsync.length > 0) {
      var call = queuedAsync.shift();
      pendingAsync.set(call.id, call);
      try { asyncBridge.postMessage(JSON.stringify(call.request)); }
      catch (error) {
        pendingAsync.delete(call.id);
        call.reject(error);
      }
    }
  }

  if (asyncBridge) asyncBridge.onmessage = function(event) {
    var response;
    try { response = JSON.parse(event.data); } catch (_) { return; }
    var call = pendingAsync.get(response && response.id);
    if (!call) return;
    pendingAsync.delete(response.id);
    if (response.ok === true) call.resolve(response.data);
    else call.reject(nodeError(call.op, call.path, response.error || "invalid-response"));
    pumpAsync();
  };

  function bytesFromBase64(value) {
    var binary = atob(value || "");
    var bytes = new Uint8Array(binary.length);
    for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return bytes;
  }

  function base64FromBytes(value) {
    var bytes = value instanceof Uint8Array ? value : Buffer.from(value);
    var parts = [];
    for (var i = 0; i < bytes.length; i += 0x8000) {
      parts.push(String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000)));
    }
    return btoa(parts.join(""));
  }

  var MAX_BUFFER_BYTES = 64 * 1024 * 1024;
  function Buffer(value, encoding) { return typeof value === "number" ? Buffer.alloc(value) : Buffer.from(value, encoding); }
  Buffer.prototype = Object.create(Uint8Array.prototype);
  Buffer.prototype.constructor = Buffer;
  Buffer.prototype._isBuffer = true;
  Buffer.from = function(value, encoding) {
    var bytes;
    if (typeof value === "string") {
      encoding = (encoding || "utf8").toLowerCase();
      if (encoding === "base64") bytes = bytesFromBase64(value);
      else if (encoding === "hex") {
        bytes = new Uint8Array(Math.floor(value.length / 2));
        for (var h = 0; h < bytes.length; h++) bytes[h] = parseInt(value.substr(h * 2, 2), 16);
      } else bytes = new TextEncoder().encode(value);
    } else if (value instanceof ArrayBuffer) bytes = new Uint8Array(value.slice(0));
    else if (ArrayBuffer.isView(value)) bytes = new Uint8Array(value.buffer.slice(value.byteOffset, value.byteOffset + value.byteLength));
    else if (Array.isArray(value)) bytes = new Uint8Array(value);
    else if (value && value.type === "Buffer" && Array.isArray(value.data)) bytes = new Uint8Array(value.data);
    else if (typeof value === "number") throw new TypeError("Buffer.from does not accept a number");
    else bytes = new Uint8Array(0);
    if (bytes.length > MAX_BUFFER_BYTES) throw new RangeError("Buffer exceeds MakerPlay memory limit");
    Object.setPrototypeOf(bytes, Buffer.prototype);
    return bytes;
  };
  Buffer.alloc = function(size, fill) {
    size = Number(size);
    if (!Number.isSafeInteger(size) || size < 0 || size > MAX_BUFFER_BYTES) throw new RangeError("Invalid Buffer size");
    var value = Buffer.from(new Uint8Array(size));
    if (fill !== undefined) value.fill(typeof fill === "number" ? fill : Buffer.from(fill)[0]);
    return value;
  };
  Buffer.allocUnsafe = Buffer.alloc;
  Buffer.isBuffer = function(value) { return !!(value && value._isBuffer); };
  Buffer.byteLength = function(value, encoding) { return Buffer.from(value, encoding).length; };
  Buffer.concat = function(values, length) {
    length = length === undefined ? values.reduce(function(sum, value) { return sum + value.length; }, 0) : length;
    var output = Buffer.alloc(length);
    var offset = 0;
    values.forEach(function(value) {
      value = Buffer.from(value);
      output.set(value.subarray(0, Math.max(0, length - offset)), offset);
      offset += value.length;
    });
    return output;
  };
  Buffer.prototype.toString = function(encoding, start, end) {
    encoding = (encoding || "utf8").toLowerCase();
    var value = this.subarray(start || 0, end === undefined ? this.length : end);
    if (encoding === "base64") return base64FromBytes(value);
    if (encoding === "hex") return Array.from(value).map(function(byte) { return byte.toString(16).padStart(2, "0"); }).join("");
    return new TextDecoder(encoding === "ascii" || encoding === "binary" ? "windows-1252" : "utf-8").decode(value);
  };
  Buffer.prototype.toJSON = function() { return { type: "Buffer", data: Array.from(this) }; };

  function normalize(path) {
    path = String(path || ".").replace(/\\/g, "/");
    var absolute = path.charAt(0) === "/";
    var trailingSeparator = path.length > 0 && path.charAt(path.length - 1) === "/";
    var parts = [];
    path.split("/").forEach(function(part) {
      if (!part || part === ".") return;
      if (part === "..") {
        if (parts.length && parts[parts.length - 1] !== "..") parts.pop();
        else if (!absolute) parts.push(part);
      } else parts.push(part);
    });
    var result = (absolute ? "/" : "") + parts.join("/");
    result = result || (absolute ? "/" : ".");
    return trailingSeparator && result !== "/" ? result + "/" : result;
  }

  function trimTrailingSeparator(path) {
    return path.length > 1 && path.charAt(path.length - 1) === "/" ? path.slice(0, -1) : path;
  }

  var pathModule = {
    resolve: function() {
      var resolved = "";
      for (var i = arguments.length - 1; i >= -1; i--) {
        var part = i >= 0 ? arguments[i] : "/game";
        if (!part) continue;
        resolved = String(part) + "/" + resolved;
        if (String(part).charAt(0) === "/") break;
      }
      return trimTrailingSeparator(normalize(resolved));
    },
    normalize: normalize,
    join: function() {
      var parts = Array.prototype.slice.call(arguments).filter(function(part) { return part !== ""; });
      return normalize(parts.length ? parts.join("/") : ".");
    },
    dirname: function(path) {
      path = trimTrailingSeparator(normalize(path));
      if (path === "/") return "/";
      var index = path.lastIndexOf("/");
      return index < 0 ? "." : index === 0 ? "/" : path.slice(0, index);
    },
    basename: function(path, suffix) {
      var value = trimTrailingSeparator(normalize(path)).split("/").pop();
      return suffix && value.endsWith(suffix) ? value.slice(0, -suffix.length) : value;
    },
    extname: function(path) {
      var base = pathModule.basename(path);
      var index = base.lastIndexOf(".");
      return index <= 0 ? "" : base.slice(index);
    },
    relative: function(from, to) {
      var left = pathModule.resolve(from).split("/").filter(Boolean);
      var right = pathModule.resolve(to).split("/").filter(Boolean);
      while (left.length && right.length && left[0] === right[0]) { left.shift(); right.shift(); }
      return left.map(function() { return ".."; }).concat(right).join("/");
    },
    isAbsolute: function(path) { return String(path).charAt(0) === "/"; },
    parse: function(path) {
      var dir = pathModule.dirname(path), base = pathModule.basename(path), ext = pathModule.extname(path);
      return { root: String(path).charAt(0) === "/" ? "/" : "", dir: dir, base: base, ext: ext, name: ext ? base.slice(0, -ext.length) : base };
    },
    format: function(value) { return pathModule.join(value.dir || value.root || "", value.base || ((value.name || "") + (value.ext || ""))); },
    sep: "/",
    delimiter: ":"
  };
  pathModule.posix = pathModule;
  pathModule.win32 = pathModule;