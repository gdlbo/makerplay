  // --- host builtins ----------------------------------------------------------------------
  // Leaf modules that map onto the host platform: os, tty, url, querystring, constants and the
  // streaming UTF-8 decoder.

  defineBuiltin("string_decoder", {
    StringDecoder: function StringDecoder(encoding) {
      var name = String(encoding || "utf8").toLowerCase();
      var label = name === "utf16le" || name === "ucs2" || name === "ucs-2" ? "utf-16le"
        : name === "latin1" || name === "binary" ? "windows-1252" : name;
      this.encoding = name;
      try { this._decoder = new TextDecoder(label); } catch (_) { this._decoder = new TextDecoder("utf-8"); }
      this.write = function(value) { return this._decoder.decode(asBytes(value), { stream: true }); };
      this.end = function(value) { return value === undefined ? this._decoder.decode() : this._decoder.decode(asBytes(value)); };
    }
  });
  // Host facts games read; values the sandbox cannot know are reported as zeros.
  defineBuiltin("os", {
    platform: function() { return "android"; },
    arch: function() { return processModule.arch; },
    hostname: function() { return "makerplay"; },
    homedir: function() { return "/data"; },
    tmpdir: function() { return "/data/tmp"; },
    EOL: "\n",
    endianness: function() { return "LE"; },
    availableParallelism: function() { return navigator.hardwareConcurrency || 2; },
    cpus: function() {
      var count = navigator.hardwareConcurrency || 2;
      var times = { user: 0, nice: 0, sys: 0, idle: 0, irq: 0 };
      var list = [];
      for (var index = 0; index < count; index++) list.push({ model: "MakerPlay CPU", speed: 0, times: times });
      return list;
    },
    totalmem: function() { return 0; },
    freemem: function() { return 0; },
    loadavg: function() { return [0, 0, 0]; },
    uptime: function() { return performance.now() / 1000; },
    type: function() { return "Android"; },
    release: function() { return navigator.userAgent; },
    networkInterfaces: function() { return {}; },
    userInfo: function() { return { username: "joiplay", uid: -1, gid: -1, shell: null, homedir: "/data" }; }
  });
  defineBuiltin("tty", { isatty: function() { return false; }, ReadStream: function() {}, WriteStream: function() {} });
  defineBuiltin("constants", {});
  defineBuiltin("querystring", { stringify: function(value) { return new URLSearchParams(value).toString(); }, parse: function(value) { var output = {}; new URLSearchParams(value).forEach(function(item, key) { output[key] = item; }); return output; } });
  defineBuiltin("url", { URL: root.URL, URLSearchParams: root.URLSearchParams, pathToFileURL: function(path) { return new URL("file://" + pathModule.resolve(path)); }, fileURLToPath: function(url) { return new URL(url).pathname; } });
  defineBuiltin("worker_threads", { isMainThread: true, parentPort: null, workerData: null, Worker: function() { throw new Error("worker_threads is not supported"); } });
