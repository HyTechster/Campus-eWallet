// Disable the submit button after the first click. The idempotency key is the real
// protection against double charges; this only stops the obvious double tap.
(function () {
  document.addEventListener("submit", function (event) {
    var form = event.target;
    if (!form.matches("form[data-once]")) return;
    if (form.dataset.sent) {
      event.preventDefault();
      return;
    }
    form.dataset.sent = "1";
    form.querySelectorAll("button[type=submit]").forEach(function (button) {
      button.setAttribute("aria-busy", "true");
      if (button.dataset.busy) {
        button.dataset.label = button.textContent;
        button.textContent = button.dataset.busy;
      }
      // Disable on the next tick so the browser still submits this click.
      setTimeout(function () { button.disabled = true; }, 0);
    });
  });

  // Coming back with the Back button restores the page from cache; let it be used again.
  window.addEventListener("pageshow", function (event) {
    if (!event.persisted) return;
    document.querySelectorAll("form[data-once]").forEach(function (form) {
      delete form.dataset.sent;
      form.querySelectorAll("button[type=submit]").forEach(function (button) {
        button.disabled = false;
        button.removeAttribute("aria-busy");
        if (button.dataset.label) button.textContent = button.dataset.label;
      });
    });
  });
})();
