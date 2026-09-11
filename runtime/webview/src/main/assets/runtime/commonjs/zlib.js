  // --- zlib -------------------------------------------------------------------------------
  // deflate/inflate/gzip over the platform zlib through the zlib bridge op, with byte caps so a
  // decompression bomb cannot exhaust the WebView.
  // Constants plugins pass as level or strategy arguments.
  var ZLIB_CONSTANTS = {
    Z_NO_COMPRESSION: 0, Z_BEST_SPEED: 1, Z_BEST_COMPRESSION: 9, Z_DEFAULT_COMPRESSION: -1,
    Z_FILTERED: 1, Z_HUFFMAN_ONLY: 2, Z_RLE: 3, Z_FIXED: 4, Z_DEFAULT_STRATEGY: 0,
    Z_NO_FLUSH: 0, Z_PARTIAL_FLUSH: 1, Z_SYNC_FLUSH: 2, Z_FULL_FLUSH: 3, Z_FINISH: 4, Z_BLOCK: 5,
    Z_OK: 0, Z_STREAM_END: 1, Z_NEED_DICT: 2, Z_ERRNO: -1, Z_STREAM_ERROR: -2, Z_DATA_ERROR: -3,
    Z_MEM_ERROR: -4, Z_BUF_ERROR: -5, Z_VERSION_ERROR: -6,
    Z_MIN_CHUNK: 64, Z_MAX_CHUNK: 64 * 1024 * 1024,
  };
  // Normalise the input argument: string, Buffer or typed array.
  function zlibInput(chunk, encoding) {
    if (chunk instanceof Uint8Array) return chunk;
    if (typeof chunk === "string") return Buffer.from(chunk, encoding || "utf8");
    return Buffer.from(chunk);
  }
  // Read level 0-9 from options; -1 lets the platform pick the default.
  function zlibLevel(options) {
    if (options && typeof options === "object" && options.level !== undefined) {
      var level = Number(options.level);
      if (level >= 0 && level <= 9) return level;
    }
    return -1;
  }
  // One-shot transform through the zlib bridge op (base64 in and out).
  function zlibSync(format, chunk, options) {
    var bytes = zlibInput(chunk, typeof options === "string" ? options : undefined);
    if (bytes.length > MAX_HASH_BYTES) throw new RangeError("Input is too large");
    var encoded = transact("zlib", "", bytes.toString("base64"), undefined, {
      format: format,
      level: zlibLevel(options),
    });
    return Buffer.from(encoded, "base64");
  }
  // Callback/promise form that runs the transform off the JS thread.
  function zlibAsync(format, chunk, options, callback) {
    if (typeof options === "function") { callback = options; options = undefined; }
    var bytes = zlibInput(chunk, typeof options === "string" ? options : undefined);
    return asyncTransact("zlib", "", bytes.toString("base64"), undefined, {
      format: format,
      level: zlibLevel(options),
    }).then(function(encoded) {
      var result = Buffer.from(encoded, "base64");
      if (callback) callback(null, result);
      return result;
    }, function(error) {
      if (callback) callback(error);
      throw error;
    });
  }
  // Streaming wrapper: buffer every chunk, transform once on flush.
  function zlibTransformStream(format) {
    var chunks = [];
    return new Transform({
      transform: function(chunk, encoding, callback) { chunks.push(zlibInput(chunk, encoding)); callback(null); },
      flush: function(callback) { callback(null, zlibSync(format, Buffer.concat(chunks))); },
    });
  }
  // Public module: sync, promise and stream forms of deflate, inflate and gzip.
  defineBuiltin("zlib", {
    constants: ZLIB_CONSTANTS,
    deflateSync: function(chunk, options) { return zlibSync("deflate", chunk, options); },
    inflateSync: function(chunk, options) { return zlibSync("inflate", chunk, options); },
    deflateRawSync: function(chunk, options) { return zlibSync("deflateRaw", chunk, options); },
    inflateRawSync: function(chunk, options) { return zlibSync("inflateRaw", chunk, options); },
    gzipSync: function(chunk, options) { return zlibSync("gzip", chunk, options); },
    gunzipSync: function(chunk, options) { return zlibSync("gunzip", chunk, options); },
    deflate: function(chunk, options, callback) { return zlibAsync("deflate", chunk, options, callback); },
    inflate: function(chunk, options, callback) { return zlibAsync("inflate", chunk, options, callback); },
    deflateRaw: function(chunk, options, callback) { return zlibAsync("deflateRaw", chunk, options, callback); },
    inflateRaw: function(chunk, options, callback) { return zlibAsync("inflateRaw", chunk, options, callback); },
    gzip: function(chunk, options, callback) { return zlibAsync("gzip", chunk, options, callback); },
    gunzip: function(chunk, options, callback) { return zlibAsync("gunzip", chunk, options, callback); },
    createDeflate: function() { return zlibTransformStream("deflate"); },
    createInflate: function() { return zlibTransformStream("inflate"); },
    createDeflateRaw: function() { return zlibTransformStream("deflateRaw"); },
    createInflateRaw: function() { return zlibTransformStream("inflateRaw"); },
    createGzip: function() { return zlibTransformStream("gzip"); },
    createGunzip: function() { return zlibTransformStream("gunzip"); },
  });
