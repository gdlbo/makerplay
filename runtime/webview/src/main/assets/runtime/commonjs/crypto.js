  // --- crypto -----------------------------------------------------------------------------
  // Random helpers run in JS; hashes/HMACs run on the platform provider through the hash/hmac
  // bridge ops, because SubtleCrypto is async-only while games call digest() synchronously.
  // Unbiased random integer using rejection sampling over random values.
  function randomIntInRange(min, max) {
    min = Math.ceil(Number(min) || 0);
    max = Math.floor(Number(max));
    if (!Number.isFinite(max) || max <= min) throw new RangeError("The value of \"max\" is out of range");
    var range = max - min;
    if (range <= 0xffffffff) {
      var seed = new Uint32Array(1);
      var limit = Math.floor(0x100000000 / range) * range;
      var value;
      do { root.crypto.getRandomValues(seed); value = seed[0]; } while (value >= limit);
      return min + (value % range);
    }
    var bytes = new Uint8Array(7);
    root.crypto.getRandomValues(bytes);
    var wide = 0;
    for (var index = 0; index < bytes.length; index++) wide = wide * 256 + bytes[index];
    return min + (wide % range);
  }

  // Algorithms the platform provider exposes through the hash/hmac bridge ops.
  var HASH_ALGORITHMS = { md5: 1, sha1: 1, sha224: 1, sha256: 1, sha384: 1, sha512: 1 };
  var MAX_HASH_BYTES = 16 * 1024 * 1024;
  function canonicalAlgorithm(algorithm) {
    var name = String(algorithm == null ? "" : algorithm).toLowerCase().replace(/-/g, "");
    if (!HASH_ALGORITHMS[name]) throw new TypeError("Digest method not supported: " + algorithm);
    return name;
  }
  // Streaming hash; chunks accumulate and are digested in a single bridge call.
  function Hash(algorithm) {
    this._algorithm = canonicalAlgorithm(algorithm);
    this._chunks = [];
    this._size = 0;
    this._finalized = false;
  }
  // Shared update() body for Hash and Hmac, with an input ceiling.
  function appendChunk(target, data, encoding) {
    if (target._finalized) throw new Error("Digest already called");
    var chunk = Buffer.from(data, encoding);
    target._size += chunk.length;
    if (target._size > MAX_HASH_BYTES) throw new RangeError("Hash input is too large");
    target._chunks.push(chunk);
    return target;
  }
  function hashUpdate(data, encoding) {
    var effective = typeof data === "string" ? (typeof encoding === "string" ? encoding : "utf8") : undefined;
    return appendChunk(this, data, effective);
  }
  Hash.prototype._append = function(data, encoding) { return appendChunk(this, data, encoding); };
  Hash.prototype.update = hashUpdate;
  Hash.prototype.digest = function(encoding) {
    if (this._finalized) throw new Error("Digest already called");
    this._finalized = true;
    var payload = Buffer.concat(this._chunks);
    this._chunks = [];
    var result = Buffer.from(transact("hash", "", payload.toString("base64"), undefined, { algo: this._algorithm }), "base64");
    return typeof encoding === "string" ? result.toString(encoding) : result;
  };
  Hash.prototype.copy = function() {
    var clone = new Hash(this._algorithm);
    clone._chunks = this._chunks.slice();
    clone._size = this._size;
    return clone;
  };
  // HMAC variant of Hash; the key travels as base64 inside the request.
  function Hmac(algorithm, key) {
    this._algorithm = canonicalAlgorithm(algorithm);
    this._key = Buffer.from(key, typeof key === "string" ? "utf8" : undefined);
    this._chunks = [];
    this._size = 0;
    this._finalized = false;
  }
  Hmac.prototype.update = hashUpdate;
  Hmac.prototype.digest = function(encoding) {
    if (this._finalized) throw new Error("Digest already called");
    this._finalized = true;
    var payload = Buffer.concat(this._chunks);
    this._chunks = [];
    var result = Buffer.from(
      transact("hmac", "", payload.toString("base64"), undefined, {
        algo: this._algorithm,
        key: this._key.toString("base64"),
      }),
      "base64",
    );
    return typeof encoding === "string" ? result.toString(encoding) : result;
  };

  // Public module: platform random plus createHash/createHmac/timingSafeEqual.
  defineBuiltin("crypto", {
    randomBytes: function(size) { var value = Buffer.alloc(size); root.crypto.getRandomValues(value); return value; },
    randomFillSync: function(buffer, offset, size) {
      var view = buffer instanceof Uint8Array ? buffer : Buffer.from(buffer);
      offset = Number(offset) || 0;
      size = size === undefined ? view.length - offset : Number(size);
      root.crypto.getRandomValues(view.subarray(offset, offset + size));
      return buffer;
    },
    randomUUID: function() { return root.crypto.randomUUID(); },
    randomInt: function(min, max, callback) {
      if (typeof min === "function") { callback = min; min = 0; max = 281474976710655; }
      else if (typeof max === "function") { callback = max; max = min; min = 0; }
      var value = randomIntInRange(min, max);
      if (typeof callback === "function") { queueMicrotask(function() { callback(null, value); }); return; }
      return value;
    },
    getRandomValues: function(buffer) { return root.crypto.getRandomValues(buffer); },
    createHash: function(algorithm) { return new Hash(algorithm); },
    createHmac: function(algorithm, key) { return new Hmac(algorithm, key); },
    timingSafeEqual: function(left, right) {
      left = Buffer.from(left);
      right = Buffer.from(right);
      if (left.length !== right.length) throw new RangeError("Input buffers must have the same byte length");
      var difference = 0;
      for (var index = 0; index < left.length; index++) difference |= left[index] ^ right[index];
      return difference === 0;
    },
    webcrypto: root.crypto,
  });
