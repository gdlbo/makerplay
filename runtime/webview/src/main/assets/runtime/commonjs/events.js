  // --- events -----------------------------------------------------------------------------
  // EventEmitter with the listener bookkeeping node exposes: on/once/prepend/rawListeners and
  // the leak warning.

  // EventEmitter plus the deep-equality helper shared by util.isDeepStrictEqual and assert.
  // Minimal listener registry backed by null-prototype maps.
  function EventEmitter() { this._events = Object.create(null); this._maxListeners = undefined; this._warnedEvents = Object.create(null); }
  EventEmitter.prototype.on = EventEmitter.prototype.addListener = function(name, listener) {
    var listeners = this._events[name] || (this._events[name] = []);
    listeners.push(listener);
    this._warnIfLeaking(name, listeners);
    return this;
  };
  EventEmitter.prototype.prependListener = function(name, listener) {
    var listeners = this._events[name] || (this._events[name] = []);
    listeners.unshift(listener);
    this._warnIfLeaking(name, listeners);
    return this;
  };
  // Emit Node-style leak warning once per event name.
  EventEmitter.prototype._warnIfLeaking = function(name, listeners) {
    var limit = this.getMaxListeners();
    if (limit > 0 && listeners.length > limit && !this._warnedEvents[name]) {
      this._warnedEvents[name] = true;
      console.warn("Possible EventEmitter memory leak detected. " + listeners.length + " " + String(name) + " listeners added.");
    }
  };
  // Wrap a listener so it unregisters itself before its first call.
  function onceWrapper(emitter, name, listener) {
    function wrapped() { emitter.removeListener(name, wrapped); return listener.apply(this, arguments); }
    wrapped.listener = listener;
    return wrapped;
  }
  EventEmitter.prototype.once = function(name, listener) {
    return this.on(name, onceWrapper(this, name, listener));
  };
  EventEmitter.prototype.prependOnceListener = function(name, listener) {
    return this.prependListener(name, onceWrapper(this, name, listener));
  };
  EventEmitter.prototype.rawListeners = function(name) { return this.listeners(name); };
  // Call a snapshot of the listeners; "error" without listeners throws.
  EventEmitter.prototype.emit = function(name) {
    var args = Array.prototype.slice.call(arguments, 1);
    var listeners = (this._events[name] || []).slice();
    if (!listeners.length && name === "error" && args[0]) throw args[0];
    listeners.forEach(function(listener) { listener.apply(this, args); }, this);
    return listeners.length > 0;
  };
  // Remove by identity, unwrapping once() listeners through their .listener link.
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


  defineBuiltin("events", EventEmitter);
