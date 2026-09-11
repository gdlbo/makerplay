  // --- stream -----------------------------------------------------------------------------
  // Readable/Writable/Duplex/Transform/PassThrough with flowing mode, backpressure and pipe.
  // fs.create*Stream and zlib.create* build on these.
  // Minimal but functional stream implementation: Node's stream classes are used by plugins for
  // log piping, file transforms and save post-processing, so silent no-op stubs are not enough.
  // Common base holding the readable/writable flags and destroy().
  function Stream(options) {
    EventEmitter.call(this);
    options = options || {};
    this.readable = false;
    this.writable = false;
    this.destroyed = false;
    this._encoding = options.encoding || null;
  }
  utilModule.inherits(Stream, EventEmitter);
  Stream.prototype.destroy = function(error) {
    if (this.destroyed) return this;
    this.destroyed = true;
    this.readable = false;
    this.writable = false;
    if (error) this.emit("error", error);
    this.emit("close");
    return this;
  };

  // Push/read source with flowing mode and a high-water mark.
  function Readable(options) {
    Stream.call(this, options);
    options = options || {};
    this.readable = true;
    this._read = typeof options.read === "function" ? options.read : this._read;
    this.highWaterMark = Number(options.highWaterMark) || 16 * 1024;
    this._buffer = [];
    this._bufferLength = 0;
    this._ended = false;
    this._flowing = false;
    this._reading = false;
    this._draining = false;
    this._endedEmitted = false;
  }
  utilModule.inherits(Readable, Stream);
  // Buffer a chunk (or end with null) and drain it when a consumer is attached.
  Readable.prototype.push = function(chunk, encoding) {
    if (chunk === null) {
      this._ended = true;
      this._drain();
      return false;
    }
    if (typeof chunk === "string") chunk = Buffer.from(chunk, encoding || this._encoding || "utf8");
    else chunk = Buffer.from(chunk);
    this._buffer.push(chunk);
    this._bufferLength += chunk.length;
    this._drain();
    return this._bufferLength < this.highWaterMark;
  };
  Readable.prototype.unshift = function(chunk, encoding) {
    if (typeof chunk === "string") chunk = Buffer.from(chunk, encoding || this._encoding || "utf8");
    this._buffer.unshift(Buffer.from(chunk));
    this._bufferLength += this._buffer[0].length;
    this._drain();
  };
  Readable.prototype.read = function() {
    if (this._buffer.length === 0) this._maybeRead();
    var chunk = this._buffer.shift();
    if (chunk === undefined) return null;
    this._bufferLength -= chunk.length;
    return this._encoding ? chunk.toString(this._encoding) : chunk;
  };
  Readable.prototype.pause = function() { this._flowing = false; return this; };
  Readable.prototype.resume = function() {
    this._flowing = true;
    this._drain();
    return this;
  };
  Readable.prototype.isPaused = function() { return !this._flowing; };
  Readable.prototype.setEncoding = function(encoding) { this._encoding = encoding; return this; };
  Readable.prototype.on = function(name, listener) {
    var result = EventEmitter.prototype.on.call(this, name, listener);
    // Attaching a data consumer switches the stream to flowing mode, like Node.
    if (name === "data" || name === "end") this.resume();
    return result;
  };
  // Forward data, pause on backpressure and end the destination on end.
  Readable.prototype.pipe = function(destination, options) {
    var self = this;
    function onData(chunk) {
      if (destination.write(chunk) === false && typeof self.pause === "function") {
        self.pause();
        destination.once("drain", function() { self.resume(); });
      }
    }
    function onEnd() {
      if (options && options.end === false) return;
      if (typeof destination.end === "function") destination.end();
    }
    function onError(error) { if (typeof destination.destroy === "function") destination.destroy(error); }
    this.on("data", onData);
    this.on("end", onEnd);
    this.on("error", onError);
    this.on("close", function() {
      self.removeListener("data", onData);
      self.removeListener("end", onEnd);
      if (destination.removeListener) destination.removeListener("drain", self.resume);
    });
    if (typeof destination.emit === "function") destination.emit("pipe", this);
    return destination;
  };
  // Let the owner fill the buffer, guarding against re-entrant _read calls.
  Readable.prototype._maybeRead = function() {
    if (this._reading || this._ended || this.destroyed) return;
    if (typeof this._read !== "function") return;
    this._reading = true;
    try { this._read(this.highWaterMark); } finally { this._reading = false; }
  };
  // Emit buffered data in one loop; the flag stops push() from recursing into it.
  Readable.prototype._drain = function() {
    if (this._draining) return;
    this._draining = true;
    try {
      while (this._flowing && this._buffer.length > 0) {
        var chunk = this._buffer.shift();
        this._bufferLength -= chunk.length;
        this.emit("data", this._encoding ? chunk.toString(this._encoding) : chunk);
      }
      if (this._ended && this._buffer.length === 0) {
        if (!this._endedEmitted) {
          this._endedEmitted = true;
          this.readable = false;
          this.emit("end");
          this.emit("close");
        }
        return;
      }
      if (this._flowing && this._buffer.length === 0) this._maybeRead();
    } finally {
      this._draining = false;
    }
  };

  // Write sink with an optional callback-style _write implementation.
  function Writable(options) {
    Stream.call(this, options);
    options = options || {};
    this.writable = true;
    this._writeImpl = typeof options.write === "function" ? options.write : this._write;
    this._finalImpl = typeof options.final === "function" ? options.final : null;
    this.highWaterMark = Number(options.highWaterMark) || 16 * 1024;
    this._ended = false;
    this._pending = [];
    this._writing = false;
  }
  utilModule.inherits(Writable, Stream);
  // Run or queue a write; returns false once the queue is deep.
  Writable.prototype.write = function(chunk, encoding, callback) {
    if (typeof encoding === "function") { callback = encoding; encoding = undefined; }
    if (this._ended) {
      var ended = nodeError("write", "", "closed");
      if (callback) callback(ended);
      this.emit("error", ended);
      return false;
    }
    if (typeof chunk === "string") chunk = Buffer.from(chunk, encoding || this._encoding || "utf8");
    if (this._writing && typeof this._writeImpl === "function") {
      this._pending.push({ chunk: chunk, callback: callback });
      return this._pending.length < 64;
    }
    this._writeNow(chunk, callback);
    return this._pending.length < 64;
  };
  // Run one write (sync or callback), drain the queue, then finish when ended.
  Writable.prototype._writeNow = function(chunk, callback) {
    var self = this;
    var settled = false;
    var done = function(error) {
      if (settled) return;
      settled = true;
      self._writing = false;
      if (callback) callback(error);
      if (error) self.emit("error", error);
      if (self._pending.length > 0) {
        var next = self._pending.shift();
        self._writeNow(next.chunk, next.callback);
      } else if (self._ended) {
        self._finish();
      }
    };
    if (typeof this._writeImpl !== "function") { done(null); return; }
    this._writing = true;
    try {
      if (this._writeImpl.length >= 2) this._writeImpl(chunk, done);
      else { this._writeImpl(chunk); done(null); }
    } catch (error) {
      done(error);
    }
  };
  Writable.prototype.end = function(chunk, encoding, callback) {
    if (typeof chunk === "function") { callback = chunk; chunk = undefined; }
    else if (typeof encoding === "function") { callback = encoding; encoding = undefined; }
    if (chunk !== undefined && chunk !== null) this.write(chunk, encoding);
    this._ended = true;
    if (callback) this.once("finish", callback);
    if (!this._writing && this._pending.length === 0) this._finish();
    return this;
  };
  Writable.prototype._finish = function() {
    if (!this.writable) return;
    if (typeof this._finalImpl === "function") {
      try { this._finalImpl(); } catch (error) { this.emit("error", error); }
    }
    this.writable = false;
    this.emit("finish");
    this.emit("close");
  };
  Writable.prototype.cork = function() {};
  Writable.prototype.uncork = function() {};
  Writable.prototype.setDefaultEncoding = function(encoding) { this._encoding = encoding; return this; };
  Writable.prototype.destroy = function(error) {
    this._pending.length = 0;
    return Stream.prototype.destroy.call(this, error);
  };

  // Both directions; Transform builds on it.
  function Duplex(options) {
    Readable.call(this, options);
    Writable.call(this, options);
    this.readable = true;
    this.writable = true;
    this._writeImpl = typeof (options && options.write) === "function" ? options.write : this._write;
  }
  utilModule.inherits(Duplex, Readable);
  // Duplex is readable *and* writable: borrow the write side verbatim.
  ["write", "end", "_writeNow", "_finish", "cork", "uncork", "setDefaultEncoding"]
    .forEach(function(name) { Duplex.prototype[name] = Writable.prototype[name]; });

  // Buffer input, then emit the transformed output on flush (one-shot codecs).
  function Transform(options) {
    Duplex.call(this, options);
    this._transformImpl = typeof (options && options.transform) === "function" ? options.transform : this._transform;
    this._flushImpl = typeof (options && options.flush) === "function" ? options.flush : null;
    var self = this;
    this._writeImpl = function(chunk, callback) {
      var settled = false;
      var done = function(error, output) {
        if (settled) return;
        settled = true;
        if (error) { callback(error); return; }
        if (output !== undefined && output !== null) self.push(output);
        callback(null);
      };
      try {
        if (typeof self._transformImpl === "function") self._transformImpl(chunk, "buffer", done);
        else done(null, chunk);
      } catch (error) {
        done(error);
      }
    };
    this._finalImpl = function() {
      if (typeof self._flushImpl !== "function") { self.push(null); return; }
      var settled = false;
      var done = function(error, output) {
        if (settled) return;
        settled = true;
        if (error) { self.emit("error", error); return; }
        if (output !== undefined && output !== null) self.push(output);
        self.push(null);
      };
      try { self._flushImpl(done); } catch (error) { done(error); }
    };
  }
  utilModule.inherits(Transform, Duplex);
  Transform.prototype._transform = function(chunk, encoding, callback) { callback(null, chunk); };

  function PassThrough(options) { Transform.call(this, options); }
  utilModule.inherits(PassThrough, Transform);

  // Chain streams with pipe() and settle once the last finishes or any stream errors.
  function pipeline() {
    var streams = Array.prototype.slice.call(arguments);
    var callback = typeof streams[streams.length - 1] === "function" ? streams.pop() : null;
    var settled = false;
    var finishPromise = null;
    var settle = function(error) {
      if (settled) return;
      settled = true;
      if (callback) callback(error || null);
      else if (finishPromise) finishPromise(error || null);
    };
    var promise = callback ? null : new Promise(function(resolve, reject) {
      finishPromise = function(error) { if (error) reject(error); else resolve(); };
    });
    streams.forEach(function(stream, index) {
      if (index > 0) streams[index - 1].pipe(stream);
      stream.on("error", settle);
    });
    var last = streams[streams.length - 1];
    if (last) last.on("finish", function() { settle(null); });
    else settle(null);
    return promise;
  }

  // Resolve when a stream ends, finishes, closes or errors.
  function finished(stream, callback) {
    var settled = false;
    var finishPromise = null;
    var settle = function(error) {
      if (settled) return;
      settled = true;
      if (callback) callback(error || null);
      else if (finishPromise) finishPromise(error || null);
    };
    var promise = callback ? null : new Promise(function(resolve, reject) {
      finishPromise = function(error) { if (error) reject(error); else resolve(); };
    });
    stream.on("end", function() { settle(null); });
    stream.on("finish", function() { settle(null); });
    stream.on("error", settle);
    stream.on("close", function() { settle(null); });
    return promise;
  }

  var streamModule = Stream;
  streamModule.Stream = Stream;
  streamModule.Readable = Readable;
  streamModule.Writable = Writable;
  streamModule.Duplex = Duplex;
  streamModule.Transform = Transform;
  streamModule.PassThrough = PassThrough;
  streamModule.pipeline = pipeline;
  streamModule.finished = finished;
  streamModule.promises = { pipeline: pipeline, finished: finished };

  defineBuiltin("stream", streamModule);
