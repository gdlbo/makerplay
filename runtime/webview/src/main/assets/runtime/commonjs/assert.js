  // --- assert -----------------------------------------------------------------------------
  // Small but real assert module: libraries feature-detect assert.strictEqual, deepEqual, ...
  // Error type Node throws, carrying actual/expected/operator fields.
  function AssertionError(options) {
    options = options || {};
    this.name = "AssertionError";
    this.code = "ERR_ASSERTION";
    this.actual = options.actual;
    this.expected = options.expected;
    this.operator = options.operator || "==";
    this.generatedMessage = options.message === undefined;
    this.message = options.message || "Assertion failed";
    if (Error.captureStackTrace) Error.captureStackTrace(this, AssertionError);
  }
  AssertionError.prototype = Object.create(Error.prototype);
  AssertionError.prototype.constructor = AssertionError;
  // assert(value) and assert.ok(); all other helpers route through it.
  function assert(value, message) {
    if (!value) throw new AssertionError({ message: message, actual: value, expected: true, operator: "==" });
  }
  // Shared success/failure path for the comparison helpers.
  function assertComparison(actual, expected, message, matches, operator) {
    if (matches) return;
    throw new AssertionError({ message: message, actual: actual, expected: expected, operator: operator });
  }
  assert.ok = assert;
  assert.fail = function(message) {
    throw new AssertionError({ message: typeof message === "string" ? message : "Failed" });
  };
  assert.equal = function(actual, expected, message) { assertComparison(actual, expected, message, actual == expected, "=="); };
  assert.notEqual = function(actual, expected, message) { assertComparison(actual, expected, message, actual != expected, "!="); };
  assert.strictEqual = function(actual, expected, message) { assertComparison(actual, expected, message, actual === expected, "strictEqual"); };
  assert.notStrictEqual = function(actual, expected, message) { assertComparison(actual, expected, message, actual !== expected, "notStrictEqual"); };
  assert.deepEqual = function(actual, expected, message) { assertComparison(actual, expected, message, deepEqual(actual, expected, false), "deepEqual"); };
  assert.notDeepEqual = function(actual, expected, message) { assertComparison(actual, expected, message, !deepEqual(actual, expected, false), "notDeepEqual"); };
  assert.deepStrictEqual = function(actual, expected, message) { assertComparison(actual, expected, message, deepEqual(actual, expected, true), "deepStrictEqual"); };
  assert.notDeepStrictEqual = function(actual, expected, message) { assertComparison(actual, expected, message, !deepEqual(actual, expected, true), "notDeepStrictEqual"); };
  function expectsThrown(error, expected, message) {
    if (expected === undefined) return;
    if (expected instanceof RegExp) {
      assertComparison(String(error && error.message), expected, message, expected.test(String(error && error.message)), "throws");
    } else if (typeof expected === "function") {
      assertComparison(error, expected, message, error instanceof expected, "throws");
    } else if (expected && typeof expected === "object" && "message" in expected) {
      assertComparison(String(error && error.message), expected.message, message, String(error && error.message) === String(expected.message), "throws");
    }
  }
  // Run block and verify it threw the expected error type, message or regex.
  assert.throws = function(block, expected, message) {
    var thrown = null;
    try { block(); } catch (error) { thrown = error; }
    if (!thrown) throw new AssertionError({ message: message || "Missing expected exception" });
    expectsThrown(thrown, expected, message);
    return thrown;
  };
  assert.doesNotThrow = function(block, expected, message) {
    var thrown = null;
    try { block(); } catch (error) { thrown = error; }
    if (thrown) throw new AssertionError({ message: message || "Got unwanted exception", actual: thrown, expected: undefined, operator: "doesNotThrow" });
  };
  assert.ifError = function(error) { if (error) throw error; };
  assert.AssertionError = AssertionError;
  assert.strict = assert;

  defineBuiltin("assert", assert);
