package org.jenkinsci.plugins.environmentdashboard;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.util.FormValidation;
import java.nio.file.Files;
import java.nio.file.Path;
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
        job.setDefinition(
                new CpsFlowDefinition("node { addDeployToDashboard(env: '   ', buildNumber: '1.2.3') }", true));

        WorkflowRun run = j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        j.assertLogContains(Deployment.REQUIRED_ENV, run);
        assertNull(
                run.getAction(Deployment.DeploymentAction.class),
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
        job.setDefinition(
                new CpsFlowDefinition("node { addDeployToDashboard(env: 'production', buildNumber: '') }", true));

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
    void theOwningRunIsNotWrittenIntoBuildXml(JenkinsRule j) throws Exception {
        // RunAction2 injects the owner on load, so it is not state this action
        // owns. Persisting it wrote a positional back-reference into the run's
        // own build.xml.
        FreeStyleProject job = j.createFreeStyleProject("persistence");
        job.getBuildersList().add(new Deployment("production", "1.0.0"));
        FreeStyleBuild build = j.buildAndAssertSuccess(job);

        String buildXml = Files.readString(Path.of(build.getRootDir().toString(), "build.xml"), UTF_8);
        String action = buildXml.substring(
                buildXml.indexOf("<org.jenkinsci.plugins.environmentdashboard.Deployment_-DeploymentAction>"),
                buildXml.indexOf("</org.jenkinsci.plugins.environmentdashboard.Deployment_-DeploymentAction>"));
        assertFalse(action.contains("<run"), "the owning run must not be persisted, but build.xml has: " + action);
        assertTrue(action.contains("<env>production</env>"), action);
        assertTrue(action.contains("<buildNumber>1.0.0</buildNumber>"), action);

        j.jenkins.reload();
        FreeStyleBuild reloaded = j.jenkins
                .getItemByFullName("persistence", FreeStyleProject.class)
                .getBuildByNumber(1);
        Deployment.DeploymentAction loaded = reloaded.getAction(Deployment.DeploymentAction.class);
        assertNotNull(loaded, "the action must survive a reload");
        assertEquals("production", loaded.getEnv());
        assertEquals("1.0.0", loaded.getBuildNumber());
        assertSame(reloaded, loaded.getRun(), "onLoad must inject the reloaded run");
    }

    @Test
    void aBuildXmlFromBeforeThisFixStillLoads(JenkinsRule j) throws Exception {
        // Records already on disk carry the back-reference. Reading them must
        // keep working, with the field simply ignored.
        FreeStyleProject job = j.createFreeStyleProject("legacy-persistence");
        job.getBuildersList().add(new Deployment("staging", "2.0.0"));
        FreeStyleBuild build = j.buildAndAssertSuccess(job);

        Path xml = Path.of(build.getRootDir().toString(), "build.xml");
        String tag = "<org.jenkinsci.plugins.environmentdashboard.Deployment_-DeploymentAction>";
        Files.writeString(
                xml,
                Files.readString(xml, UTF_8)
                        .replace(tag, tag + "\n      <run class=\"build\" reference=\"../../..\"/>"),
                UTF_8);

        j.jenkins.reload();
        FreeStyleBuild reloaded = j.jenkins
                .getItemByFullName("legacy-persistence", FreeStyleProject.class)
                .getBuildByNumber(1);
        Deployment.DeploymentAction loaded = reloaded.getAction(Deployment.DeploymentAction.class);
        assertNotNull(loaded, "an action written by the older version must still load");
        assertEquals("staging", loaded.getEnv());
        assertEquals("2.0.0", loaded.getBuildNumber());
        assertSame(reloaded, loaded.getRun());
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
