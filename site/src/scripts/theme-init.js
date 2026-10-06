(function () {
  try {
    var theme = localStorage.getItem("vitrin-theme");
    if (theme === "light" || theme === "dark") {
      document.documentElement.dataset.theme = theme;
    }
  } catch (error) {}
})();
