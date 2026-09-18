package org.jenkinsci.plugins.environmentdashboard;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleProject;
import java.util.List;
import java.util.stream.Collectors;
import jenkins.branch.BranchSource;
import jenkins.scm.impl.mock.MockSCMController;
import jenkins.scm.impl.mock.MockSCMDiscoverBranches;
import jenkins.scm.impl.mock.MockSCMSource;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockFolder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class DeploymentViewTest {

    @Test
    void everyEnvironmentDeployedToInOneBuildIsShown(JenkinsRule j) throws Exception {
        // A deploy pipeline that promotes through environments records one action
        // per environment on the same run. The view used to read them with the
        // singular getAction(), which returns only the first match, so every
        // environment after the first silently vanished.
        WorkflowJob job = j.createProject(WorkflowJob.class, "promote");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  addDeployToDashboard(env: 'staging', buildNumber: '2.0.0')\n"
                        + "  addDeployToDashboard(env: 'production', buildNumber: '2.0.0')\n"
                        + "}",
                true));
        j.buildAndAssertSuccess(job);

        DeploymentView view = new DeploymentView("promote-view");
        j.jenkins.addView(view);
        view.setIncludeRegex("promote");

        List<DeploymentView.Unit> units = view.getUnits(view.getItems());
        assertEquals(1, units.size());
        List<String> envs = units.get(0).getEnvironments().stream()
                .map(DeploymentView.Unit.Environment::getName)
                .collect(Collectors.toList());
        assertEquals(List.of("production", "staging"), envs,
                "both environments must appear, in a deterministic order");
    }

    @Test
    void currentActionIsTheNewestDeploymentWhateverOrderItArrivesIn(JenkinsRule j) throws Exception {
        // For a multibranch project the runs arrive branch by branch, so the
        // newest deployment is not necessarily first. Environment must sort.
        WorkflowJob job = j.createProject(WorkflowJob.class, "ordering");
        job.setDefinition(new CpsFlowDefinition(
                "node { addDeployToDashboard(env: 'production', buildNumber: \"1.0.${BUILD_NUMBER}\") }", true));
        WorkflowRun older = j.buildAndAssertSuccess(job);
        WorkflowRun newer = j.buildAndAssertSuccess(job);

        Deployment.DeploymentAction olderAction = older.getAction(Deployment.DeploymentAction.class);
        Deployment.DeploymentAction newerAction = newer.getAction(Deployment.DeploymentAction.class);
        assertNotNull(olderAction);
        assertNotNull(newerAction);

        // Deliberately oldest-first, which is what the multibranch path produced.
        DeploymentView.Unit.Environment env =
                new DeploymentView.Unit.Environment("production", List.of(olderAction, newerAction));

        assertEquals(newerAction.getBuildNumber(), env.getCurrentAction().getBuildNumber(),
                "the current deployment must be the most recently started one");
        assertEquals(
                List.of(newerAction.getBuildNumber(), olderAction.getBuildNumber()),
                env.getActions().stream()
                        .map(Deployment.DeploymentAction::getBuildNumber)
                        .collect(Collectors.toList()),
                "release history must read newest first");
    }

    @Test
    void aRecordWithNoEnvironmentDoesNotTakeTheWholeDashboardDown(JenkinsRule j) throws Exception {
        // The step will not create one of these any more, but the builds that
        // already have one outlive the fix, and one of them used to 500 the
        // entire view -- every job on it, for every user, until that single
        // build was deleted. Attached directly, which is how it got on disk.
        WorkflowJob job = j.createProject(WorkflowJob.class, "legacy");
        job.setDefinition(new CpsFlowDefinition(
                "node { addDeployToDashboard(env: 'production', buildNumber: '1.2.3') }", true));
        WorkflowRun run = j.buildAndAssertSuccess(job);
        run.addAction(new Deployment.DeploymentAction(null, "9.9.9"));
        run.save();

        DeploymentView view = new DeploymentView("legacy-view");
        j.jenkins.addView(view);
        view.setIncludeRegex("legacy");
        view.save();

        List<DeploymentView.Unit> units = view.getUnits(view.getItems());
        assertEquals(1, units.size());
        assertEquals(List.of("production"),
                units.get(0).getEnvironments().stream()
                        .map(DeploymentView.Unit.Environment::getName)
                        .collect(Collectors.toList()),
                "the unusable record is dropped, the good one still shows");

        try (JenkinsRule.WebClient wc = j.createWebClient()) {
            HtmlPage page = wc.goTo("view/legacy-view/");
            assertEquals(200, page.getWebResponse().getStatusCode());
            assertTrue(page.getWebResponse().getContentAsString().contains("1.2.3"));
        }
    }

    @Test
    void aFreestyleDeploymentIsShown(JenkinsRule j) throws Exception {
        // The step's descriptor is applicable to every project type, so a
        // freestyle job could record a deployment -- and the view, which only
        // knew two Pipeline types, then dropped the job entirely. Selected in
        // the view's configuration, never rendered, and nothing said why.
        FreeStyleProject job = j.createFreeStyleProject("legacy-app");
        job.getBuildersList().add(new Deployment("production", "4.5.6"));
        j.buildAndAssertSuccess(job);

        DeploymentView view = new DeploymentView("freestyle-view");
        j.jenkins.addView(view);
        view.setIncludeRegex("legacy-app");
        view.save();

        List<DeploymentView.Unit> units = view.getUnits(view.getItems());
        assertEquals(1, units.size(), "a freestyle job that deploys belongs on the dashboard");
        assertEquals("production", units.get(0).getEnvironments().get(0).getName());
        assertEquals("4.5.6", units.get(0).getEnvironments().get(0).getCurrentAction().getBuildNumber());
    }

    @Test
    void aDeploymentFromAJobInsideAFolderIsShown(JenkinsRule j) throws Exception {
        // The same generic walk that reaches a multibranch project's branches
        // reaches anything else that holds jobs.
        MockFolder folder = j.createFolder("team");
        FreeStyleProject job = folder.createProject(FreeStyleProject.class, "nested-app");
        job.getBuildersList().add(new Deployment("staging", "7.0.0"));
        j.buildAndAssertSuccess(job);

        DeploymentView view = new DeploymentView("folder-view");
        j.jenkins.addView(view);
        view.setIncludeRegex("team");
        view.save();

        List<DeploymentView.Unit> units = view.getUnits(view.getItems());
        assertEquals(1, units.size(), "a folder holding a job that deploys belongs on the dashboard");
        assertEquals("staging", units.get(0).getEnvironments().get(0).getName());
        assertEquals("7.0.0", units.get(0).getEnvironments().get(0).getCurrentAction().getBuildNumber());
    }

    @Test
    void aMultibranchProjectStillWorksThroughTheGenericWalk(JenkinsRule j) throws Exception {
        // The view no longer names WorkflowMultiBranchProject, and the plugin no
        // longer depends on it, so the case it used to special-case is asserted
        // against a real one built from an SCM rather than assumed.
        try (MockSCMController scm = MockSCMController.create()) {
            scm.createRepository("app");
            scm.addFile("app", "master", "pipeline",
                    "Jenkinsfile",
                    "node { addDeployToDashboard(env: 'production', buildNumber: '1.0.0') }".getBytes(UTF_8));
            scm.cloneBranch("app", "master", "hotfix");
            scm.addFile("app", "hotfix", "pipeline",
                    "Jenkinsfile",
                    "node { addDeployToDashboard(env: 'production', buildNumber: '1.0.1') }".getBytes(UTF_8));

            WorkflowMultiBranchProject mp = j.createProject(WorkflowMultiBranchProject.class, "multi-app");
            mp.getSourcesList().add(new BranchSource(new MockSCMSource(scm, "app", new MockSCMDiscoverBranches())));
            mp.scheduleBuild2(0).getFuture().get();
            j.waitUntilNoActivity();
            assertEquals(2, mp.getItems().size(), "both branches must have been indexed and built");

            DeploymentView view = new DeploymentView("multi-view");
            j.jenkins.addView(view);
            view.setIncludeRegex("multi-app");
            view.save();

            List<DeploymentView.Unit> units = view.getUnits(view.getItems());
            assertEquals(1, units.size());
            DeploymentView.Unit.Environment production = units.get(0).getEnvironments().get(0);
            assertEquals("production", production.getName());
            assertEquals(2, production.getActions().size(),
                    "both branches deployed to production, so both belong in the history");
        }
    }

    @Test
    void viewRendersDeploymentsWithoutInlineJavascript(JenkinsRule j) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, "app");
        job.setDefinition(new CpsFlowDefinition(
                "node { addDeployToDashboard(env: 'production', buildNumber: '1.2.3') }", true));
        WorkflowRun run = j.buildAndAssertSuccess(job);

        Deployment.DeploymentAction action = run.getAction(Deployment.DeploymentAction.class);
        assertNotNull(action);
        assertEquals("production", action.getEnv());
        assertEquals("1.2.3", action.getBuildNumber());

        DeploymentView view = new DeploymentView("deployments");
        j.jenkins.addView(view);
        view.setIncludeRegex(".*");
        view.save();

        List<DeploymentView.Unit> units = view.getUnits(view.getItems());
        assertEquals(1, units.size());
        assertEquals("production", units.get(0).getEnvironments().get(0).getName());
        assertEquals("1.2.3", units.get(0).getEnvironments().get(0).getCurrentAction().getBuildNumber());

        try (JenkinsRule.WebClient wc = j.createWebClient()) {
            HtmlPage page = wc.goTo("view/deployments/");
            String html = page.getWebResponse().getContentAsString();
            assertTrue(html.contains("1.2.3"), "release version must be shown on the dashboard");
            assertTrue(html.contains("edb-popup-toggle"), "environment link must use the CSP-safe toggle");
            assertFalse(html.contains("javascript:toggle"),
                    "inline javascript: URLs must be gone (JENKINS-74429)");
        }
    }
}
