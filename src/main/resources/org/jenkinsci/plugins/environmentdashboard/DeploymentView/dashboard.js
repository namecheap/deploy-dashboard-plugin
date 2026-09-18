// Opens the release history in core's dialog.
//
// This used to toggle the display of a div pre-rendered into the page for every
// environment, styled by the plugin's own CSS -- which hardcoded a white panel
// and a black header, so the history was unreadable in dark theme. Core's
// dialog is themed by core, and the history is now cloned out of a <template>
// on demand rather than sitting in the DOM of every page load.
(function () {
    "use strict";

    document.addEventListener("click", function (event) {
        var toggle = event.target.closest(".edb-popup-toggle");
        if (!toggle) {
            return;
        }
        event.preventDefault();

        var template = document.getElementById(toggle.getAttribute("data-popup-id"));
        if (!template || !template.content) {
            return;
        }
        var content = document.createElement("div");
        content.appendChild(template.content.cloneNode(true));

        if (window.dialog && typeof window.dialog.modal === "function") {
            window.dialog.modal(content, { title: toggle.getAttribute("data-popup-title") });
        } else {
            // Core has provided `dialog` since well before this plugin's
            // baseline. Say so rather than appearing to do nothing.
            console.error("deploy-dashboard: core's dialog API is unavailable; cannot open the release history");
        }
    });
})();
