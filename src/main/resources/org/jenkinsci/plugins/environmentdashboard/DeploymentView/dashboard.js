// Opens the release history in core's dialog.
//
// This used to toggle the display of a div pre-rendered into the page for every
// environment, styled by the plugin's own CSS -- which hardcoded a white panel
// and a black header, so the history was unreadable in dark theme. Core's
// dialog is themed by core, and the history is now cloned out of a <template>
// on demand rather than sitting in the DOM of every page load.
(function () {
    "use strict";

    // Collapsing is done here rather than server-side on purpose: the rows are
    // always in the HTML, so anything reading the page without running scripts
    // still sees every deployment.
    var STORAGE_PREFIX = "deploy-dashboard.expanded.";
    var DEFAULT_EXPANDED_UP_TO = 5;

    function storageKey(id) {
        return STORAGE_PREFIX + window.location.pathname + "#" + id;
    }

    function remembered(id) {
        try {
            return window.localStorage.getItem(storageKey(id));
        } catch (e) {
            // Private windows and blocked site data throw rather than returning
            // null; a dashboard that cannot remember is still a dashboard.
            return null;
        }
    }

    function remember(id, expanded) {
        try {
            window.localStorage.setItem(storageKey(id), expanded ? "1" : "0");
        } catch (e) {
            /* ignore */
        }
    }

    function apply(button, expanded) {
        var rows = document.getElementById(button.getAttribute("data-edb-toggle"));
        if (!rows) {
            return;
        }
        rows.hidden = !expanded;
        button.setAttribute("aria-expanded", expanded ? "true" : "false");
        var icon = button.querySelector("svg, .jenkins-menu-dropdown-chevron, span");
        if (icon && icon.classList) {
            icon.classList.toggle("edb-collapsed-chevron", !expanded);
        }
    }

    function initialise() {
        var buttons = document.querySelectorAll(".edb-toggle");
        var expandedByDefault = buttons.length <= DEFAULT_EXPANDED_UP_TO;
        buttons.forEach(function (button) {
            var saved = remembered(button.getAttribute("data-edb-toggle"));
            apply(button, saved === null ? expandedByDefault : saved === "1");
        });
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", initialise);
    } else {
        initialise();
    }

    document.addEventListener("click", function (event) {
        var toggle = event.target.closest(".edb-toggle");
        if (toggle) {
            event.preventDefault();
            var expanded = toggle.getAttribute("aria-expanded") !== "true";
            apply(toggle, expanded);
            remember(toggle.getAttribute("data-edb-toggle"), expanded);
            return;
        }

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
