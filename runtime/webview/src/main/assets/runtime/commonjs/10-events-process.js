  function EventEmitter() { this._events = Object.create(null); this._maxListeners = undefined; this._warnedEvents = Object.create(null); }
  EventEmitter.prototype.on = EventEmitter.prototype.addListener = function(name, listener) {
    var listeners = this._events[name] || (this._events[name] = []);
    listeners.push(listener);
    var limit = this.getMaxListeners();
    if (limit > 0 && listeners.length > limit && !this._warnedEvents[name]) {
      this._warnedEvents[name] = true;
      console.warn("Possible EventEmitter memory leak detected. " + listeners.length + " " + String(name) + " listeners added.");
    }
    return this;
  };
  EventEmitter.prototype.once = function(name, listener) {
    var self = this;
    function once() { self.removeListener(name, once); return listener.apply(this, arguments); }
    once.listener = listener; return this.on(name, once);
  };
  EventEmitter.prototype.emit = function(name) {
    var args = Array.prototype.slice.call(arguments, 1);
    var listeners = (this._events[name] || []).slice();
    if (!listeners.length && name === "error" && args[0]) throw args[0];
    listeners.forEach(function(listener) { listener.apply(this, args); }, this);
    return listeners.length > 0;
  };
  EventEmitter.prototype.removeListener = function(name, listener) {
    this._events[name] = (this._events[name] || []).filter(function(item) { return item !== listener && item.listener !== listener; });
    if (this._events[name].length <= this.getMaxListeners()) delete this._warnedEvents[name];
    return this;
  };
  EventEmitter.prototype.removeAllListeners = function(name) { if (name) { delete this._events[name]; delete this._warnedEvents[name]; } else { this._events = Object.create(null); this._warnedEvents = Object.create(null); } return this; };
  EventEmitter.prototype.listeners = function(name) { return (this._events[name] || []).slice(); };
  EventEmitter.prototype.listenerCount = function(name) { return (this._events[name] || []).length; };
  EventEmitter.prototype.eventNames = function() { return Reflect.ownKeys(this._events).filter(function(name) { return this._events[name].length; }, this); };
  EventEmitter.prototype.getMaxListeners = function() { return this._maxListeners === undefined ? EventEmitter.defaultMaxListeners : this._maxListeners; };
  EventEmitter.prototype.setMaxListeners = function(value) { value = Number(value); if (!Number.isFinite(value) || value < 0) throw new RangeError("Invalid max listeners"); this._maxListeners = value; return this; };
  EventEmitter.prototype.off = EventEmitter.prototype.removeListener;
  EventEmitter.defaultMaxListeners = 10;
  EventEmitter.EventEmitter = EventEmitter;
  EventEmitter.listenerCount = function(emitter, name) { return emitter.listenerCount(name); };

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
    var seconds = Math.floor(performance.now() / 1000), nanos = Math.floor((performance.now() % 1000) * 1000000);
    if (!previous) return [seconds, nanos];
    var delta = seconds - previous[0], nanoDelta = nanos - previous[1];
    if (nanoDelta < 0) { delta--; nanoDelta += 1000000000; }
    return [delta, nanoDelta];
  };
  processModule.versions = { node: "18.0.0", nw: "0.0.0", chromium: navigator.userAgent };

  var utilModule = {
    format: function(format) {
      var args = Array.prototype.slice.call(arguments, 1);
      if (typeof format !== "string") return [format].concat(args).map(utilModule.inspect).join(" ");
      var index = 0;
      var value = format.replace(/%[sdijoO%]/g, function(token) {
        if (token === "%%") return "%";
        if (index >= args.length) return token;
        var arg = args[index++];
        if (token === "%s") return String(arg);
        if (token === "%d" || token === "%i") return Number(arg);
        if (token === "%j") { try { return JSON.stringify(arg); } catch (_) { return "[Circular]"; } }
        return utilModule.inspect(arg);
      });
      return [value].concat(args.slice(index).map(utilModule.inspect)).join(" ");
    },
    inspect: function(value) { if (typeof value === "string") return value; try { return JSON.stringify(value); } catch (_) { return String(value); } },
    inherits: function(ctor, superCtor) { ctor.super_ = superCtor; ctor.prototype = Object.create(superCtor.prototype, { constructor: { value: ctor, writable: true, configurable: true } }); },
    deprecate: function(fn) { return fn; },
    promisify: function(fn) { return function() { var args = Array.from(arguments); return new Promise(function(resolve, reject) { fn.apply(this, args.concat(function(error, value) { if (error) reject(error); else resolve(value); })); }); }; },
    types: { isUint8Array: function(value) { return value instanceof Uint8Array; } }
  };

  function Stats(value) { this.size = value.size || 0; this._file = !!value.file; this._directory = !!value.directory; }
  Stats.prototype.isFile = function() { return this._file; };
  Stats.prototype.isDirectory = function() { return this._directory; };
  Stats.prototype.isSymbolicLink = function() { return false; };

  function encodingOf(options) { return typeof options === "string" ? options : options && options.encoding; }
  function asyncCall(action, callback) {
    callback = typeof callback === "function" ? callback : function() {};
    setTimeout(function() { try { callback(null, action()); } catch (error) { callback(error); } }, 0);
  }