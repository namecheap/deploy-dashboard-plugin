package org.jenkinsci.plugins.environmentdashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
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
