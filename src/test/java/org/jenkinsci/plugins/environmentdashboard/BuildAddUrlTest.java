package org.jenkinsci.plugins.environmentdashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleProject;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import java.net.URL;
import java.util.List;
import jenkins.model.Jenkins;
import jenkins.model.details.Detail;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class BuildAddUrlTest {

    private static final String TARGET =
            "/job/app-deploy/parambuild/?imageRegistry=reg.example.com/app&version=1.2.3&revision=abc123&build_url=https://ci.example.com/job/app/1/";

    /** A run whose link is only meant for the listed principals. */
    private static WorkflowRun runWithRestrictedLink(JenkinsRule j, String name, String visibleTo) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, name);
        job.setDefinition(new CpsFlowDefinition(
                "node { buildAddUrl(title: 'Deploy to PROD', url: '/job/deploy/parambuild/?v=1', visibleTo: ["
                        + visibleTo + "]) }",
                true));
        return j.buildAndAssertSuccess(job);
    }

    private static int statusFor(JenkinsRule j, WorkflowRun run, String as) throws Exception {
        BuildAddUrl.BuildUrlAction action = run.getAction(BuildAddUrl.BuildUrlAction.class);
        try (JenkinsRule.WebClient wc = j.createWebClient()) {
            wc.login(as);
            wc.getOptions().setRedirectEnabled(false);
            wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
            return wc.loadWebResponse(new WebRequest(new URL(j.getURL(), run.getUrl() + action.getUrlName() + "/")))
                    .getStatusCode();
        }
    }

    private static WorkflowRun runWithLink(JenkinsRule j, String url) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, "app-" + Math.abs(url.hashCode()));
        job.setDefinition(
                new CpsFlowDefinition("node { buildAddUrl(title: 'Deploy to DEV', url: '" + url + "') }", true));
        return j.buildAndAssertSuccess(job);
    }

    @Test
    void actionRedirectsToTargetWithQueryStringIntact(JenkinsRule j) throws Exception {
        WorkflowRun run = runWithLink(j, TARGET);

        BuildAddUrl.BuildUrlAction action = run.getAction(BuildAddUrl.BuildUrlAction.class);
        assertNotNull(action, "buildAddUrl should attach a BuildUrlAction to the run");
        assertEquals("Deploy to DEV", action.getDisplayName());
        assertEquals(TARGET, action.getUrl());

        // The routed path must be a clean token: query strings in action URLs
        // get mangled or dropped by some UIs, which is the bug this guards against.
        assertFalse(action.getUrlName().contains("?"), "urlName must not carry a query string");
        assertFalse(action.getUrlName().contains("&"), "urlName must not carry a query string");

        try (JenkinsRule.WebClient wc = j.createWebClient()) {
            wc.getOptions().setRedirectEnabled(false);
            wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
            URL url = new URL(j.getURL(), run.getUrl() + action.getUrlName() + "/");
            WebResponse response = wc.loadWebResponse(new WebRequest(url));
            assertEquals(302, response.getStatusCode(), "the action must redirect to the configured URL");
            String location = response.getResponseHeaderValue("Location");
            assertNotNull(location);
            assertTrue(location.endsWith(TARGET), "redirect must preserve the full query string, got: " + location);
        }
    }

    @Test
    void unsafeUrlIsNotRedirected(JenkinsRule j) throws Exception {
        WorkflowRun run = runWithLink(j, "javascript:alert(1)");

        BuildAddUrl.BuildUrlAction action = run.getAction(BuildAddUrl.BuildUrlAction.class);
        assertNotNull(action);
        assertFalse(action.isSafeUrl());

        try (JenkinsRule.WebClient wc = j.createWebClient()) {
            wc.getOptions().setRedirectEnabled(false);
            wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
            URL url = new URL(j.getURL(), run.getUrl() + action.getUrlName() + "/");
            WebResponse response = wc.loadWebResponse(new WebRequest(url));
            assertEquals(404, response.getStatusCode(), "unsafe URL schemes must not redirect");
        }
    }

    @Test
    void protocolRelativeUrlIsNotRedirected(JenkinsRule j) throws Exception {
        WorkflowRun run = runWithLink(j, "//evil.example.com/phish");

        BuildAddUrl.BuildUrlAction action = run.getAction(BuildAddUrl.BuildUrlAction.class);
        assertNotNull(action);
        assertFalse(action.isSafeUrl());
    }

    @Test
    void aRestrictedLinkIsHiddenAndRefusedForEveryoneElse(JenkinsRule j) throws Exception {
        // Upstream's version returned null from getIconFileName and stopped
        // there. The URL is still in the page source and the endpoint's path is
        // derivable, so hiding an icon protects nothing. All three surfaces get
        // asserted here, and the redirect is the one that matters.
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        WorkflowRun run = runWithRestrictedLink(j, "restricted", "users: ['alice']");
        BuildAddUrl.BuildUrlAction action = run.getAction(BuildAddUrl.BuildUrlAction.class);
        assertNotNull(action);

        try (ACLContext ignored = ACL.as2(User.getById("alice", true).impersonate2())) {
            assertTrue(action.isVisible(), "the listed user must see the link");
            assertNotNull(action.getIconFileName());
            assertNotNull(action.getIconClassName());
            assertEquals(1, new BuildUrlDetailFactory().createFor(run).size());
        }
        try (ACLContext ignored = ACL.as2(User.getById("bob", true).impersonate2())) {
            assertFalse(action.isVisible(), "an unlisted user must not see the link");
            assertNull(action.getIconFileName(), "no sidebar icon");
            assertNull(action.getIconClassName(), "no details-bar icon");
            assertEquals(0, new BuildUrlDetailFactory().createFor(run).size(), "no details-bar entry");
        }

        assertEquals(302, statusFor(j, run, "alice"), "the listed user must still be redirected");
        assertEquals(404, statusFor(j, run, "bob"), "the redirect itself must refuse an unlisted user");
    }

    @Test
    void aGroupGrantsVisibility(JenkinsRule j) throws Exception {
        org.jvnet.hudson.test.JenkinsRule.DummySecurityRealm realm = j.createDummySecurityRealm();
        realm.addGroups("dave", "release-managers");
        j.jenkins.setSecurityRealm(realm);

        WorkflowRun run = runWithRestrictedLink(j, "grouped", "groups: ['release-managers']");
        BuildAddUrl.BuildUrlAction action = run.getAction(BuildAddUrl.BuildUrlAction.class);

        try (ACLContext ignored = ACL.as2(User.getById("dave", true).impersonate2())) {
            assertTrue(action.isVisible(), "a member of the listed group must see the link");
        }
        try (ACLContext ignored = ACL.as2(User.getById("carol", true).impersonate2())) {
            assertFalse(action.isVisible(), "membership grants it, not merely being a user");
        }
        assertEquals(302, statusFor(j, run, "dave"));
        assertEquals(404, statusFor(j, run, "carol"));
    }

    @Test
    void withoutVisibleToTheLinkIsShownToAnyoneWhoCanReadTheBuild(JenkinsRule j) throws Exception {
        // The narrowing is opt-in; absent it, nothing changes.
        WorkflowRun run = runWithLink(j, TARGET);
        BuildAddUrl.BuildUrlAction action = run.getAction(BuildAddUrl.BuildUrlAction.class);

        try (ACLContext ignored = ACL.as2(Jenkins.ANONYMOUS2)) {
            assertTrue(action.isVisible(), "an unrestricted link stays visible");
            assertNotNull(action.getIconFileName());
            assertEquals(1, new BuildUrlDetailFactory().createFor(run).size());
        }
    }

    @Test
    void theFreestyleFormRoundTripsVisibleTo(JenkinsRule j) throws Exception {
        // The step's form is new, and a form that renders but does not bind is
        // worse than none: it silently discards what was typed into it.
        FreeStyleProject job = j.createFreeStyleProject("form-roundtrip");
        BuildAddUrl step = new BuildAddUrl("Deploy to PROD", "/job/deploy/parambuild/?v=1");
        step.setVisibleTo(new BuildAddUrl.VisibleTo(List.of("alice"), List.of("release-managers")));
        job.getBuildersList().add(step);

        j.configRoundtrip(job);

        BuildAddUrl saved = job.getBuildersList().get(BuildAddUrl.class);
        assertNotNull(saved, "the build step must survive a configuration round trip");
        assertEquals("Deploy to PROD", saved.getTitle());
        assertNotNull(saved.getVisibleTo(), "visibleTo must survive the round trip");
        assertEquals(List.of("alice"), saved.getVisibleTo().getUsers());
        assertEquals(List.of("release-managers"), saved.getVisibleTo().getGroups());
    }

    @Test
    void detailFactoryExposesLinkOnDetailsBar(JenkinsRule j) throws Exception {
        WorkflowRun run = runWithLink(j, TARGET);
        BuildAddUrl.BuildUrlAction action = run.getAction(BuildAddUrl.BuildUrlAction.class);

        List<? extends Detail> details = new BuildUrlDetailFactory().createFor(run);
        assertEquals(1, details.size(), "each buildAddUrl call should surface one detail");
        Detail detail = details.get(0);
        assertEquals("Deploy to DEV", detail.getDisplayName());
        assertNotNull(detail.getIconClassName(), "details without an icon are not rendered by the details bar");
        assertTrue(
                detail.getLink().endsWith("/" + run.getUrl() + action.getUrlName()),
                "detail must link to the redirecting action, got: " + detail.getLink());
    }
}
