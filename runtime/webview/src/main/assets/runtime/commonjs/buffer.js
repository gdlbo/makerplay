  // --- Buffer ------------------------------------------------------------------------------
  // Byte container used by every other module: Node-compatible encodings, zero-copy views and
  // binary accessors. String payloads cross the native bridge as base64.
  var MAX_BUFFER_BYTES = 64 * 1024 * 1024;
  // Divisible by 3 so every chunk encodes to an independently valid base64 group.
  var BASE64_CHUNK_BYTES = 0x7fe0;
  var CODEC_CHUNK_BYTES = 0x8000;
  var STRING_CHUNK_BYTES = 0x4000;
  var HEX_DIGITS = "0123456789abcdef";
  var HEX_VALUES = new Int16Array(128).fill(-1);
  var UTF8_ENCODER = new TextEncoder();
  var UTF8_DECODER = new TextDecoder("utf-8");
  for (var hexDigit = 0; hexDigit < 10; hexDigit++) HEX_VALUES[48 + hexDigit] = hexDigit;
  for (var hexLetter = 0; hexLetter < 6; hexLetter++) {
    HEX_VALUES[97 + hexLetter] = 10 + hexLetter;
    HEX_VALUES[65 + hexLetter] = 10 + hexLetter;
  }

  // Canonicalise an encoding name; unknown names throw like Node (an empty name means utf8).
  function normalizeEncoding(encoding) {
    var name = String(encoding == null ? "utf8" : encoding).toLowerCase();
    if (name === "" || name === "utf8" || name === "utf-8") return "utf8";
    if (name === "latin1" || name === "binary") return "latin1";
    if (name === "ascii") return "ascii";
    if (name === "hex") return "hex";
    if (name === "base64") return "base64";
    if (name === "base64url") return "base64url";
    if (name === "utf16le" || name === "utf-16le" || name === "ucs2" || name === "ucs-2") return "utf16le";
    throw new TypeError("Unknown encoding: " + encoding);
  }

  // Hex encode in chunks so no single huge intermediate string is built.
  function hexFromBytes(bytes) {
    var parts = [];
    for (var i = 0; i < bytes.length; i += CODEC_CHUNK_BYTES) {
      var chunk = bytes.subarray(i, Math.min(i + CODEC_CHUNK_BYTES, bytes.length));
      var text = "";
      for (var j = 0; j < chunk.length; j++) text += HEX_DIGITS[chunk[j] >> 4] + HEX_DIGITS[chunk[j] & 15];
      parts.push(text);
    }
    return parts.join("");
  }

  // latin1 decode, with an optional 7-bit mask for ascii.
  function singleByteFromBytes(bytes, mask) {
    var parts = [];
    for (var i = 0; i < bytes.length; i += CODEC_CHUNK_BYTES) {
      var chunk = bytes.subarray(i, Math.min(i + CODEC_CHUNK_BYTES, bytes.length));
      var codes = new Array(chunk.length);
      for (var j = 0; j < chunk.length; j++) codes[j] = mask === undefined ? chunk[j] : chunk[j] & mask;
      parts.push(String.fromCharCode.apply(null, codes));
    }
    return parts.join("");
  }

  // utf16le decode; a trailing odd byte is dropped, as in Node.
  function utf16leFromBytes(bytes) {
    var length = bytes.length >> 1;
    var parts = [];
    for (var start = 0; start < length; start += STRING_CHUNK_BYTES) {
      var end = Math.min(start + STRING_CHUNK_BYTES, length);
      var codes = new Array(end - start);
      for (var index = start; index < end; index++) {
        codes[index - start] = bytes[index * 2] | (bytes[index * 2 + 1] << 8);
      }
      parts.push(String.fromCharCode.apply(null, codes));
    }
    return parts.join("");
  }

  // base64 encode per 3-byte-aligned chunk so every piece is independently valid.
  function base64FromBytes(value) {
    var bytes = value instanceof Uint8Array ? value : Buffer.from(value);
    var parts = [];
    for (var i = 0; i < bytes.length; i += BASE64_CHUNK_BYTES) {
      parts.push(btoa(String.fromCharCode.apply(
        null,
        bytes.subarray(i, Math.min(i + BASE64_CHUNK_BYTES, bytes.length)),
      )));
    }
    return parts.join("");
  }

  /** Node ignores characters that are not part of the base64 alphabet. */
  // Node-lenient base64 decode: stray characters are ignored and padding is repaired.
  function bytesFromBase64(value) {
    var cleaned = String(value == null ? "" : value).replace(/[^A-Za-z0-9+/]/g, "");
    var remainder = cleaned.length % 4;
    if (remainder === 1) cleaned = cleaned.slice(0, -1);
    else if (remainder > 0) cleaned += "==".slice(0, 4 - remainder);
    if (!cleaned) return new Uint8Array(0);
    var binary = atob(cleaned);
    var bytes = new Uint8Array(binary.length);
    for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return bytes;
  }

  // Hex decode that stops at the first invalid character instead of yielding zeros.
  function bytesFromHex(value) {
    var text = String(value);
    var length = text.length >> 1;
    var bytes = new Uint8Array(length);
    for (var i = 0; i < length; i++) {
      var highCode = text.charCodeAt(i * 2);
      var lowCode = text.charCodeAt(i * 2 + 1);
      var high = highCode < 128 ? HEX_VALUES[highCode] : -1;
      var low = lowCode < 128 ? HEX_VALUES[lowCode] : -1;
      if (high < 0 || low < 0) return Uint8Array.prototype.subarray.call(bytes, 0, i);
      bytes[i] = (high << 4) | low;
    }
    return bytes;
  }

  // latin1/ascii encode (the high bit is masked for ascii).
  function bytesFromSingleByte(value, mask) {
    var text = String(value);
    var bytes = new Uint8Array(text.length);
    for (var i = 0; i < bytes.length; i++) bytes[i] = text.charCodeAt(i) & mask;
    return bytes;
  }

  // utf16le encode.
  function bytesFromUtf16le(value) {
    var text = String(value);
    var bytes = new Uint8Array(text.length * 2);
    for (var i = 0; i < text.length; i++) {
      var code = text.charCodeAt(i);
      bytes[i * 2] = code & 0xff;
      bytes[i * 2 + 1] = code >> 8;
    }
    return bytes;
  }

  // Lexicographic comparison shared by Buffer.compare and buffer.compare().
  function compareBytes(left, right) {
    var shared = Math.min(left.length, right.length);
    for (var i = 0; i < shared; i++) if (left[i] !== right[i]) return left[i] < right[i] ? -1 : 1;
    if (left.length === right.length) return 0;
    return left.length < right.length ? -1 : 1;
  }

  // Callable Buffer, matching the legacy Buffer(n) / Buffer(string) forms.
  function Buffer(value, encoding) { return typeof value === "number" ? Buffer.alloc(value) : Buffer.from(value, encoding); }
  Buffer.prototype = Object.create(Uint8Array.prototype);
  Buffer.prototype.constructor = Buffer;
  Buffer.prototype._isBuffer = true;
  // Re-tag a Uint8Array as a Buffer without copying, enforcing the memory ceiling.
  // Byte view of any input without copying an existing view (used by fs, zlib and builtins).
  function asBytes(value) { return value instanceof Uint8Array ? value : Buffer.from(value); }
  function asBuffer(bytes) {
    if (bytes.length > MAX_BUFFER_BYTES) throw new RangeError("Buffer exceeds MakerPlay memory limit");
    Object.setPrototypeOf(bytes, Buffer.prototype);
    return bytes;
  }
  // Coerce a string, typed array, ArrayBuffer or Buffer JSON value into a Buffer.
  Buffer.from = function(value, encoding) {
    var bytes;
    if (typeof value === "string") {
      switch (normalizeEncoding(encoding)) {
        case "base64": bytes = bytesFromBase64(value); break;
        case "base64url": bytes = bytesFromBase64(value.replace(/-/g, "+").replace(/_/g, "/")); break;
        case "hex": bytes = bytesFromHex(value); break;
        case "latin1": bytes = bytesFromSingleByte(value, 0xff); break;
        case "ascii": bytes = bytesFromSingleByte(value, 0x7f); break;
        case "utf16le": bytes = bytesFromUtf16le(value); break;
        default: bytes = UTF8_ENCODER.encode(value); break;
      }
    } else if (value instanceof ArrayBuffer) bytes = new Uint8Array(value.slice(0));
    else if (ArrayBuffer.isView(value)) bytes = new Uint8Array(value.buffer.slice(value.byteOffset, value.byteOffset + value.byteLength));
    else if (Array.isArray(value)) bytes = new Uint8Array(value);
    else if (value && value.type === "Buffer" && Array.isArray(value.data)) bytes = new Uint8Array(value.data);
    else if (typeof value === "number") throw new TypeError("Buffer.from does not accept a number");
    else bytes = new Uint8Array(0);
    return asBuffer(bytes);
  };
  // Zero-filled allocation with a validated size.
  Buffer.alloc = function(size, fill) {
    size = Number(size);
    if (!Number.isSafeInteger(size) || size < 0 || size > MAX_BUFFER_BYTES) throw new RangeError("Invalid Buffer size");
    var value = Buffer.from(new Uint8Array(size));
    if (fill !== undefined) value.fill(fill);
    return value;
  };
  Buffer.allocUnsafe = Buffer.alloc;
  Buffer.isBuffer = function(value) { return !!(value && value._isBuffer); };
  // True when Buffer.from/toString accept this encoding name.
  Buffer.isEncoding = function(encoding) {
    if (encoding === "" || encoding == null) return false;
    try { normalizeEncoding(encoding); return true; } catch (_) { return false; }
  };
  // Encoded byte length of a string, or the element count of an existing view.
  Buffer.byteLength = function(value, encoding) {
    if (typeof value === "string") return Buffer.from(value, encoding).length;
    return Number(value && value.length) || 0;
  };
  // Concatenate with Node semantics: truncate when short, zero-fill when long.
  Buffer.concat = function(values, length) {
    // Node returns an empty buffer for an empty list, ignoring the requested length.
    if (values.length === 0) return Buffer.alloc(0);
    var total = 0;
    values.forEach(function(value) { total += value.length; });
    // Node zero-fills when the requested length exceeds the total and truncates when it is smaller.
    length = length === undefined ? total : Math.max(0, Math.floor(Number(length) || 0));
    var output = Buffer.alloc(length);
    var offset = 0;
    values.forEach(function(value) {
      if (offset >= length) return;
      value = Buffer.from(value);
      output.set(value.subarray(0, length - offset), offset);
      offset += value.length;
    });
    return output;
  };
  // Views must be plain Uint8Arrays: the default species is Buffer, whose constructor
  // allocates and copies on every subarray/slice call.
  Object.defineProperty(Buffer, Symbol.species, { value: Uint8Array, configurable: true });
  // Zero-copy view; Symbol.species above stops the default species from copying.
  Buffer.prototype.subarray = function(start, end) {
    return asBuffer(Uint8Array.prototype.subarray.call(this, start, end));
  };
  Buffer.prototype.slice = Buffer.prototype.subarray;
  // Decode a slice with the requested encoding.
  Buffer.prototype.toString = function(encoding, start, end) {
    var name = normalizeEncoding(encoding);
    var offset = Number(start) || 0;
    if (offset < 0) offset = 0;
    var stop = end === undefined ? this.length : Number(end) || 0;
    if (stop < 0) stop = 0;
    if (stop > this.length) stop = this.length;
    var value = this.subarray(offset, stop);
    if (name === "base64") return base64FromBytes(value);
    if (name === "base64url") return base64FromBytes(value).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
    if (name === "hex") return hexFromBytes(value);
    if (name === "latin1") return singleByteFromBytes(value);
    if (name === "ascii") return singleByteFromBytes(value, 0x7f);
    if (name === "utf16le") return utf16leFromBytes(value);
    return UTF8_DECODER.decode(value);
  };
  // Encode a string into the buffer and return the number of bytes written.
  Buffer.prototype.write = function(string, offset, length, encoding) {
    if (typeof offset === "string") {
      encoding = offset;
      offset = 0;
      length = this.length;
    } else if (typeof length === "string") {
      encoding = length;
      length = this.length - (Number(offset) || 0);
    }
    offset = Number(offset) || 0;
    if (offset < 0 || offset > this.length) throw new RangeError("offset is out of bounds");
    length = length === undefined ? this.length - offset : Number(length);
    var source = Buffer.from(String(string), encoding).subarray(0, Math.max(0, length));
    this.set(source, offset);
    return source.length;
  };
  // Fill with a byte value or a repeated string.
  Buffer.prototype.fill = function(value, offset, end, encoding) {
    if (typeof value === "number") return Uint8Array.prototype.fill.call(this, value, offset, end);
    var fill = Buffer.from(value, encoding);
    if (fill.length === 0) return this;
    var start = Number(offset) || 0;
    var stop = end === undefined ? this.length : Number(end) || 0;
    for (var index = start; index < stop; index++) this[index] = fill[(index - start) % fill.length];
    return this;
  };
  // Copy a slice into another buffer; returns the copied length.
  Buffer.prototype.copy = function(target, targetStart, sourceStart, sourceEnd) {
    targetStart = Number(targetStart) || 0;
    sourceStart = Number(sourceStart) || 0;
    sourceEnd = sourceEnd === undefined ? this.length : Number(sourceEnd);
    var source = this.subarray(sourceStart, sourceEnd);
    target.set(source, targetStart);
    return source.length;
  };
  // Length check plus byte equality.
  Buffer.prototype.equals = function(other) {
    other = Buffer.from(other);
    if (other.length !== this.length) return false;
    for (var i = 0; i < this.length; i++) if (this[i] !== other[i]) return false;
    return true;
  };
  Buffer.compare = function(left, right) {
    return compareBytes(Buffer.from(left), Buffer.from(right));
  };
  Buffer.prototype.compare = function(target, targetStart, targetEnd, sourceStart, sourceEnd) {
    var right = Buffer.from(target);
    var leftValue = this;
    if (targetStart !== undefined || targetEnd !== undefined || sourceStart !== undefined || sourceEnd !== undefined) {
      leftValue = this.subarray(sourceStart, sourceEnd);
      right = right.subarray(targetStart, targetEnd);
    }
    return compareBytes(leftValue, right);
  };
  // Search for a byte value or a substring.
  Buffer.prototype.indexOf = function(value, byteOffset, encoding) {
    var needle = typeof value === "number"
      ? Uint8Array.of(value & 0xff)
      : Buffer.from(value, encoding);
    if (this.length === 0 && needle.length === 0) return 0;
    var start = Number(byteOffset) || 0;
    if (start < 0) start = Math.max(0, this.length + start);
    for (var i = start; i + needle.length <= this.length; i++) {
      var matched = true;
      for (var j = 0; j < needle.length; j++) {
        if (this[i + j] !== needle[j]) { matched = false; break; }
      }
      if (matched) return i;
    }
    return -1;
  };
  Buffer.prototype.includes = function(value, byteOffset, encoding) {
    return this.indexOf(value, byteOffset, encoding) !== -1;
  };
  // DataView over a slice with Node-style bounds checking.
  function integerView(buffer, offset, size) {
    offset = Number(offset) || 0;
    if (offset < 0 || offset + size > buffer.length) throw new RangeError("offset is out of bounds");
    return new DataView(buffer.buffer, buffer.byteOffset + offset, size);
  }
  [
    ["UInt8", 1, false, false, false], ["Int8", 1, false, true, false],
    ["UInt16LE", 2, true, false, false], ["UInt16BE", 2, false, false, false],
    ["Int16LE", 2, true, true, false], ["Int16BE", 2, false, true, false],
    ["UInt32LE", 4, true, false, false], ["UInt32BE", 4, false, false, false],
    ["Int32LE", 4, true, true, false], ["Int32BE", 4, false, true, false],
    ["FloatLE", 4, true, false, true], ["FloatBE", 4, false, false, true],
    ["DoubleLE", 8, true, false, true], ["DoubleBE", 8, false, false, true],
  // Generate readUInt8..writeDoubleBE from one table of width, endianness and signedness.
  ].forEach(function(spec) {
    var suffix = spec[0], size = spec[1], littleEndian = spec[2], signed = spec[3], floating = spec[4];
    Buffer.prototype["read" + suffix] = function(offset) {
      var view = integerView(this, offset, size);
      if (floating) return size === 4 ? view.getFloat32(0, littleEndian) : view.getFloat64(0, littleEndian);
      if (size === 1) return signed ? view.getInt8(0) : view.getUint8(0);
      if (size === 2) return signed ? view.getInt16(0, littleEndian) : view.getUint16(0, littleEndian);
      return signed ? view.getInt32(0, littleEndian) : view.getUint32(0, littleEndian);
    };
    Buffer.prototype["write" + suffix] = function(value, offset) {
      var view = integerView(this, offset, size);
      if (floating && size === 4) view.setFloat32(0, value, littleEndian);
      else if (floating) view.setFloat64(0, value, littleEndian);
      else if (size === 1) signed ? view.setInt8(0, value) : view.setUint8(0, value);
      else if (size === 2) signed ? view.setInt16(0, value, littleEndian) : view.setUint16(0, value, littleEndian);
      else signed ? view.setInt32(0, value, littleEndian) : view.setUint32(0, value, littleEndian);
      return Number(offset) + size;
    };
  });
  Buffer.prototype.toJSON = function() { return { type: "Buffer", data: Array.from(this) }; };

  defineBuiltin("buffer", {
    Buffer: Buffer,
    SlowBuffer: Buffer.alloc,
    INSPECT_MAX_BYTES: 50,
    kMaxLength: MAX_BUFFER_BYTES,
    constants: { MAX_LENGTH: MAX_BUFFER_BYTES, MAX_STRING_LENGTH: MAX_BUFFER_BYTES }
  });
