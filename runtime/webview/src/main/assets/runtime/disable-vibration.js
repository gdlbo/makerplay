(() => {
  const block = () => false;
  try {
    Object.defineProperty(Navigator.prototype, 'vibrate', {
      value: block,
      configurable: false,
      writable: false,
    });
  } catch (_) {
    try { Object.defineProperty(navigator, 'vibrate', { value: block }); } catch (_) {}
  }
})();