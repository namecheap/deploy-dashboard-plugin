# Changelog

## 0.3.0

First release since 0.2.1, covering 21 merged pull requests.

### Upgrade notes

- **`addDeployToDashboard` now fails the build when `env` or `buildNumber` is
  missing.** It previously succeeded and recorded an unusable entry, which then
  returned HTTP 500 for the whole Deployment View. Both values are also trimmed,
  so `" production "` and `"production"` are no longer two separate rows.
- **The plugin no longer depends on `workflow-multibranch`.** The view walks
  item groups generically, so multibranch keeps working, but an installation
  that only uses freestyle jobs no longer has to carry the Pipeline plugins.
- **Minimum Jenkins is now 2.568.1** (was 2.568.2), which widens the range of
  controllers that can install it.

### Fixed

- The dashboard showed only one environment when a build deployed to several
  (#16).
- The "current" release could be a stale one on multibranch projects, and the
  release history was ordered by branch rather than by time (#16).
- Environment rows appeared in a different order between restarts (#16).
- A deployment recorded with no environment returned HTTP 500 for the entire
  view, for every job and every user, until that build was deleted (#18).
- Deployments from freestyle, matrix and folder-nested jobs were recorded but
  never displayed (#19).
- The owning run was written into the build's own `build.xml` as a positional
  back-reference, which would drop the action if anything reordered the file
  (#20).
- Ticking jobs in the view's configuration did nothing: the form posted bare
  job names while core reads `item_<name>` (#32).
- The page emitted duplicate `id` attributes on every row and every modal (#33).
- Stapler web methods flagged by the Jenkins Security Scan (#38, #46).

### Added

- `buildAddUrl` takes an optional `visibleTo`, restricting a link to named users
  or groups. Enforced on the sidebar, the details bar and the redirect itself,
  which answers 404 rather than merely hiding the icon (#44).
- Collapsible per-job sections on the dashboard, remembered per viewer (#45).
- A configuration form for both build steps; they previously rendered an empty
  panel in freestyle and in the Snippet Generator (#18, #44).

### Changed

- The dashboard uses core's current table styling and core's dialog for the
  release history. The old modal hardcoded a white panel and a black header, so
  it was unreadable in dark theme (#42).
- The view no longer rescans every build of every job on each page render;
  results are cached and validated against a cheap fingerprint, and the history
  a modal carries is capped at 50 per environment, which it says when it applies
  (#22).
- Plugin parent and BOM updated to current releases, with a JDK 25 build leg
  (#37).
- Release credentials are no longer passed on the Maven command line, and the
  JDK the release build downloads is pinned and checksum-verified (#21).

### Build and CI

The repository had no CI at all; its `Jenkinsfile` targets ci.jenkins.io, which
builds the upstream repository. It now runs tests on Linux and Windows across
JDK 21 and 25, an end-to-end job against a real Jenkins, a weekly compatibility
run against the current LTS and weekly lines, the Jenkins Security Scan, and six
of the archetype's quality gates (#16, #17, #23, #29, #30, #34, #36, #39, #43).
