  function Stream() { EventEmitter.call(this); }
  utilModule.inherits(Stream, EventEmitter);
  function Readable() { Stream.call(this); }
  utilModule.inherits(Readable, Stream);
  function Writable() { Stream.call(this); }
  utilModule.inherits(Writable, Stream);
  Writable.prototype.write = function(chunk, encoding, callback) { if (typeof encoding === "function") callback = encoding; if (callback) callback(); return true; };
  Writable.prototype.end = function(chunk, encoding, callback) { if (typeof chunk === "function") callback = chunk; else if (typeof encoding === "function") callback = encoding; if (callback) callback(); this.emit("finish"); };
  function Duplex() { Readable.call(this); Writable.call(this); }
  utilModule.inherits(Duplex, Readable);
  function Transform() { Duplex.call(this); }
  utilModule.inherits(Transform, Duplex);
  var streamModule = Stream;
  streamModule.Stream = Stream; streamModule.Readable = Readable; streamModule.Writable = Writable; streamModule.Duplex = Duplex; streamModule.Transform = Transform; streamModule.PassThrough = Transform;

  var builtins = Object.create(null);
  function builtin(name, value) { builtins[name] = value; }
  builtin("path", pathModule);
  builtin("process", processModule);
  builtin("buffer", {
    Buffer: Buffer,
    SlowBuffer: Buffer.alloc,
    INSPECT_MAX_BYTES: 50,
    kMaxLength: MAX_BUFFER_BYTES,
    constants: { MAX_LENGTH: MAX_BUFFER_BYTES, MAX_STRING_LENGTH: MAX_BUFFER_BYTES }
  });
  builtin("events", EventEmitter);
  builtin("util", utilModule);
  builtin("fs", fsModule);
  builtin("fs/promises", fsModule.promises);
  builtin("stream", streamModule);
  builtin("string_decoder", { StringDecoder: function(encoding) { this.encoding = encoding || "utf8"; this.write = function(value) { return Buffer.from(value).toString(this.encoding); }; this.end = this.write; } });
  builtin("os", { platform: function() { return "android"; }, arch: function() { return processModule.arch; }, hostname: function() { return "makerplay"; }, homedir: function() { return "/data"; }, tmpdir: function() { return "/data/tmp"; }, EOL: "\n", endianness: function() { return "LE"; } });
  builtin("tty", { isatty: function() { return false; }, ReadStream: function() {}, WriteStream: function() {} });
  builtin("assert", function(value, message) { if (!value) throw new Error(message || "Assertion failed"); });
  builtin("constants", {});
  builtin("querystring", { stringify: function(value) { return new URLSearchParams(value).toString(); }, parse: function(value) { var output = {}; new URLSearchParams(value).forEach(function(item, key) { output[key] = item; }); return output; } });
  builtin("url", { URL: root.URL, URLSearchParams: root.URLSearchParams, pathToFileURL: function(path) { return new URL("file://" + pathModule.resolve(path)); }, fileURLToPath: function(url) { return new URL(url).pathname; } });
  builtin("worker_threads", { isMainThread: true, parentPort: null, workerData: null, Worker: function() { throw new Error("worker_threads is not supported"); } });
  builtin("crypto", { randomBytes: function(size) { var value = Buffer.alloc(size); root.crypto.getRandomValues(value); return value; } });
  function fakeChildProcess(callback) {
    var child = new EventEmitter();
    child.pid = 0;
    child.killed = false;
    child.stdin = { write: function() { return false; }, end: function() {} };
    child.stdout = new EventEmitter();
    child.stderr = new EventEmitter();
    child.kill = function() { child.killed = true; return true; };
    setTimeout(function() {
      var error = new Error("child_process is not supported by MakerPlay");
      error.code = "ENOSYS";
      if (typeof callback === "function") callback(error, "", "");
      child.emit("close", 1, null);
      child.emit("exit", 1, null);
    }, 0);
    return child;
  }
  builtin("child_process", {
    exec: function(command, options, callback) { if (typeof options === "function") callback = options; return fakeChildProcess(callback); },
    execFile: function(file, args, options, callback) { if (typeof args === "function") callback = args; else if (typeof options === "function") callback = options; return fakeChildProcess(callback); },
    spawn: function() { return fakeChildProcess(); },
    execSync: function() { var error = new Error("child_process is not supported by MakerPlay"); error.code = "ENOSYS"; throw error; },
    execFileSync: function() { var error = new Error("child_process is not supported by MakerPlay"); error.code = "ENOSYS"; throw error; },
    spawnSync: function() { var error = new Error("child_process is not supported by MakerPlay"); error.code = "ENOSYS"; return { pid: 0, output: [null, Buffer.alloc(0), Buffer.from(error.message)], stdout: Buffer.alloc(0), stderr: Buffer.from(error.message), status: 1, signal: null, error: error }; },
    fork: function() { return fakeChildProcess(); }
  });
  var nwWindow = new EventEmitter();
  Object.assign(nwWindow, {
    menu: null,
    window: root,
    title: document.title,
    x: 0,
    y: 0,
    width: root.innerWidth,
    height: root.innerHeight,
    zoomLevel: 0,
    focus: function() { root.focus(); },
    show: function() {},
    hide: function() {},
    close: function() { this.emit("close"); },
    maximize: function() {},
    unmaximize: function() {},
    minimize: function() {},
    restore: function() {},
    setAlwaysOnTop: function() {},
    setResizable: function() {},
    setMinimumSize: function() {},
    resizeTo: function() {},
    moveTo: function() {},
    setPosition: function() {},
    setMaximumSize: function() {},
    requestAttention: function() {},
    setProgressBar: function() {},
    showDevTools: function() { return nwWindow; },
    closeDevTools: function() {},
    isDevToolsOpen: function() { return false; },
    reload: function() { root.location.reload(); },
    enterFullscreen: function() { if (document.documentElement.requestFullscreen) document.documentElement.requestFullscreen(); },
    leaveFullscreen: function() { if (document.exitFullscreen) document.exitFullscreen(); },
    toggleFullscreen: function() { return document.fullscreenElement ? this.leaveFullscreen() : this.enterFullscreen(); },
    isFullscreen: false,
  });
  function NwMenu() { this.items = []; }
  NwMenu.prototype.append = function(item) { this.items.push(item); };
  NwMenu.prototype.createMacBuiltin = function() {};
  function NwMenuItem(options) { Object.assign(this, options || {}); }
  var nwApp = new EventEmitter();
  Object.assign(nwApp, {
    argv: [],
    fullArgv: [],
    filteredArgv: [],
    dataPath: "/data",
    startPath: "/game",
    manifest: {},
    quit: function() { this.emit("quit"); },
    closeAllWindows: function() { nwWindow.close(); },
    clearCache: function() {}
  });
  var clipboardText = "";
  var nwClipboard = {
    get: function() { return clipboardText; },
    set: function(value, type) {
      if (type && type !== "text") {
        var error = new Error("Clipboard type '" + type + "' is not supported by MakerPlay");
        error.code = "ENOSYS";
        throw error;
      }
      clipboardText = String(value);
      if (navigator.clipboard && typeof navigator.clipboard.writeText === "function") {
        navigator.clipboard.writeText(clipboardText).catch(function(error) {
          console.warn("Unable to write system clipboard", error);
        });
      }
      return true;
    },
    clear: function() {
      clipboardText = "";
      if (navigator.clipboard && typeof navigator.clipboard.writeText === "function") {
        navigator.clipboard.writeText("").catch(function() {});
      }
    }
  };
  var nwGui = {
    Window: { get: function() { return nwWindow; }, open: function(url, options, callback) { if (callback) callback(nwWindow); return nwWindow; } },
    Menu: NwMenu,
    MenuItem: NwMenuItem,
    App: nwApp,
    Clipboard: { get: function() { return nwClipboard; } },
    Shell: { openExternal: function() {}, openItem: function() {}, showItemInFolder: function() {} }
  };
  builtin("nw.gui", nwGui);
  builtin("nw", nwGui);