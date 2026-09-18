package org.jenkinsci.plugins.environmentdashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.util.FormValidation;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * The step used to record whatever it was given, including nothing at all. An
 * env of null then reached the view's {@code groupingBy}, which rejects a null
 * key, so a single such build returned HTTP 500 for the whole dashboard.
 */
@WithJenkins
class DeploymentTest {

    @Test
    void aBlankEnvFailsTheBuild(JenkinsRule j) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, "blank-env");
        job.setDefinition(new CpsFlowDefinition(
                "node { addDeployToDashboard(env: '   ', buildNumber: '1.2.3') }", true));

        WorkflowRun run = j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        j.assertLogContains(Deployment.REQUIRED_ENV, run);
        assertNull(run.getAction(Deployment.DeploymentAction.class),
                "nothing may be recorded when the step rejects its arguments");
    }

    @Test
    void anOmittedEnvFailsTheBuild(JenkinsRule j) throws Exception {
        // The call from the bug report: the step succeeded, and the dashboard
        // it fed then 500'd for everyone.
        WorkflowJob job = j.createProject(WorkflowJob.class, "no-env");
        job.setDefinition(new CpsFlowDefinition("node { addDeployToDashboard(buildNumber: '1.2.3') }", true));

        WorkflowRun run = j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        assertNull(run.getAction(Deployment.DeploymentAction.class));
    }

    @Test
    void aBlankBuildNumberFailsTheBuild(JenkinsRule j) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, "blank-release");
        job.setDefinition(new CpsFlowDefinition(
                "node { addDeployToDashboard(env: 'production', buildNumber: '') }", true));

        WorkflowRun run = j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        j.assertLogContains(Deployment.REQUIRED_BUILD_NUMBER, run);
    }

    @Test
    void surroundingWhitespaceIsNotPartOfTheEnvironmentName(JenkinsRule j) throws Exception {
        // Otherwise "production" and "production " are two rows on the dashboard
        // and no one can see why.
        WorkflowJob job = j.createProject(WorkflowJob.class, "padded");
        job.setDefinition(new CpsFlowDefinition(
                "node { addDeployToDashboard(env: ' production ', buildNumber: ' 1.2.3 ') }", true));

        WorkflowRun run = j.buildAndAssertSuccess(job);
        Deployment.DeploymentAction action = run.getAction(Deployment.DeploymentAction.class);
        assertNotNull(action);
        assertEquals("production", action.getEnv());
        assertEquals("1.2.3", action.getBuildNumber());
    }

    @Test
    void aFreestyleBuildIsRejectedTheSameWay(JenkinsRule j) throws Exception {
        // The step is a Builder applicable to every project type, so the
        // guard has to hold outside Pipeline too.
        FreeStyleProject job = j.createFreeStyleProject("freestyle-no-env");
        job.getBuildersList().add(new Deployment(null, "1.2.3"));

        j.assertLogContains(Deployment.REQUIRED_ENV, j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0)));
    }

    @Test
    void theFormRejectsWhatTheStepRejects(JenkinsRule j) {
        // Same rule, enforced before a build is run rather than during one.
        Deployment.DescriptorImpl d = j.jenkins.getDescriptorByType(Deployment.DescriptorImpl.class);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckEnv("").kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckEnv("  ").kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckEnv("production").kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckBuildNumber(null).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckBuildNumber("1.2.3").kind);
    }
}
