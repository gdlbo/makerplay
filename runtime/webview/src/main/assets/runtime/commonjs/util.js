  // --- util -------------------------------------------------------------------------------
  // util helpers libraries expect (format, inspect, promisify, types, TextEncoder) plus the
  // structural equality used by util.isDeepStrictEqual and assert.

  // Structural equality for Date/RegExp/typed arrays/objects, shared by util and assert.
  function deepEqual(left, right, strict) {
    if (left === right) return true;
    if (left instanceof Date || right instanceof Date) {
      return left instanceof Date && right instanceof Date && Number(left) === Number(right);
    }
    if (left instanceof RegExp || right instanceof RegExp) {
      return left instanceof RegExp && right instanceof RegExp && String(left) === String(right);
    }
    if (ArrayBuffer.isView(left) || ArrayBuffer.isView(right)) {
      if (!ArrayBuffer.isView(left) || !ArrayBuffer.isView(right) || left.length !== right.length) return false;
      for (var i = 0; i < left.length; i++) if (left[i] !== right[i]) return false;
      return true;
    }
    if (left === null || right === null || typeof left !== "object" || typeof right !== "object") {
      return strict ? left === right : left == right;
    }
    var leftKeys = Object.keys(left), rightKeys = Object.keys(right);
    if (leftKeys.length !== rightKeys.length) return false;
    for (var index = 0; index < leftKeys.length; index++) {
      var key = leftKeys[index];
      if (!Object.prototype.hasOwnProperty.call(right, key)) return false;
      if (!deepEqual(left[key], right[key], strict)) return false;
    }
    return true;
  }

  // util helpers libraries require: format, inspect, promisify, types, TextEncoder, ...
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
    callbackify: function(fn) { return function() { var args = Array.from(arguments); var callback = args.pop(); Promise.resolve(fn.apply(this, args)).then(function(value) { callback(null, value); }, callback); }; },
    stripVTControlCharacters: function(value) { return String(value).replace(/\u001b\[[0-9;]*[A-Za-z]/g, ""); },
    isDeepStrictEqual: function(left, right) { return deepEqual(left, right, true); },
    TextEncoder: root.TextEncoder,
    TextDecoder: root.TextDecoder,
    types: {
      isUint8Array: function(value) { return value instanceof Uint8Array; },
      isTypedArray: function(value) { return ArrayBuffer.isView(value) && !(value instanceof DataView); },
      isArrayBuffer: function(value) { return value instanceof ArrayBuffer; },
      isDate: function(value) { return value instanceof Date; },
      isRegExp: function(value) { return value instanceof RegExp; },
      isPromise: function(value) { return !!value && typeof value.then === "function"; },
      isFunction: function(value) { return typeof value === "function"; },
      isBuffer: function(value) { return Buffer.isBuffer(value); }
    }
  };

  defineBuiltin("util", utilModule);
