(function () {
  try {
    var theme = localStorage.getItem('ucl.theme');
    if (theme === 'light' || theme === 'dark' || theme === 'system') {
      var actual = theme === 'system'
        ? (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light')
        : theme;
      document.documentElement.dataset.theme = actual;
    }
    var accent = localStorage.getItem('ucl.accent');
    if (['blue', 'mint', 'violet', 'sunset', 'gradient'].indexOf(accent) >= 0) {
      document.documentElement.dataset.accent = accent;
    }
  } catch (ignored) { }
})();
