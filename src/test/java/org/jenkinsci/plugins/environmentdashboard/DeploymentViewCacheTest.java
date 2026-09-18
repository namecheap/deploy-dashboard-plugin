package org.jenkinsci.plugins.environmentdashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * The dashboard used to scan every build of every selected job on every page
 * render, and render every deployment ever made into hidden modals. Both costs
 * were paid again by each viewer, on each load.
 *
 * <p>What matters at least as much as the saving: a deployment dashboard that
 * answers from a cache must never answer with a stale release.
 */
@WithJenkins
class DeploymentViewCacheTest {

    private static DeploymentView viewOver(JenkinsRule j, String name, String regex) throws Exception {
        DeploymentView view = new DeploymentView(name);
        j.jenkins.addView(view);
        view.setIncludeRegex(regex);
        view.save();
        return view;
    }

    private static DeploymentView.Unit.Environment onlyEnvironment(DeploymentView view) {
        List<DeploymentView.Unit> units = view.getUnits(view.getItems());
        assertEquals(1, units.size());
        return units.get(0).getEnvironments().get(0);
    }

    @Test
    void anUnchangedJobIsNotScannedTwice(JenkinsRule j) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("cached");
        job.getBuildersList().add(new Deployment("production", "1.0.0"));
        j.buildAndAssertSuccess(job);

        DeploymentView view = viewOver(j, "cache-view", "cached");

        List<DeploymentView.Unit.Environment> first =
                view.getUnits(view.getItems()).get(0).getEnvironments();
        List<DeploymentView.Unit.Environment> second =
                view.getUnits(view.getItems()).get(0).getEnvironments();
        assertSame(first, second, "nothing changed, so the second render must reuse the first result");
    }

    @Test
    void afinishedDeploymentIsVisibleOnTheVeryNextRender(JenkinsRule j) throws Exception {
        // The failure mode a cache introduces, and the one that matters here:
        // the dashboard exists to say what is live now.
        FreeStyleProject job = j.createFreeStyleProject("moving");
        job.getBuildersList().add(new Deployment("production", "1.0.0"));
        j.buildAndAssertSuccess(job);

        DeploymentView view = viewOver(j, "moving-view", "moving");
        assertEquals("1.0.0", onlyEnvironment(view).getCurrentAction().getBuildNumber());

        job.getBuildersList().clear();
        job.getBuildersList().add(new Deployment("production", "2.0.0"));
        j.buildAndAssertSuccess(job);

        DeploymentView.Unit.Environment env = onlyEnvironment(view);
        assertEquals(
                "2.0.0",
                env.getCurrentAction().getBuildNumber(),
                "the release that just deployed must be the one shown");
        assertEquals(2, env.getActions().size());
    }

    @Test
    void aNewEnvironmentAppearsOnTheVeryNextRender(JenkinsRule j) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("growing");
        job.getBuildersList().add(new Deployment("staging", "1.0.0"));
        j.buildAndAssertSuccess(job);

        DeploymentView view = viewOver(j, "growing-view", "growing");
        assertEquals(1, view.getUnits(view.getItems()).get(0).getEnvironments().size());

        job.getBuildersList().clear();
        job.getBuildersList().add(new Deployment("production", "1.0.0"));
        j.buildAndAssertSuccess(job);

        assertEquals(
                2,
                view.getUnits(view.getItems()).get(0).getEnvironments().size(),
                "an environment deployed to for the first time must show up at once");
    }

    @Test
    void deletingABuildIsNoticed(JenkinsRule j) throws Exception {
        // Deleting a build moves none of the numbers the cache validates
        // against, so it is the one change that has to announce itself.
        FreeStyleProject job = j.createFreeStyleProject("shrinking");
        job.getBuildersList().add(new Deployment("production", "1.0.0"));
        FreeStyleBuild first = j.buildAndAssertSuccess(job);
        j.buildAndAssertSuccess(job);

        DeploymentView view = viewOver(j, "shrinking-view", "shrinking");
        assertEquals(2, onlyEnvironment(view).getActions().size());

        first.delete();

        assertEquals(1, onlyEnvironment(view).getActions().size(), "a deleted build must leave the dashboard");
    }

    @Test
    void aReloadDropsEverythingThatWasCached(JenkinsRule j) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("reloaded");
        job.getBuildersList().add(new Deployment("production", "1.0.0"));
        j.buildAndAssertSuccess(job);

        DeploymentView view = viewOver(j, "reload-view", "reloaded");
        List<DeploymentView.Unit.Environment> before =
                view.getUnits(view.getItems()).get(0).getEnvironments();

        j.jenkins.reload();

        DeploymentView reloadedView = (DeploymentView) j.jenkins.getView("reload-view");
        assertNotNull(reloadedView);
        List<DeploymentView.Unit.Environment> after =
                reloadedView.getUnits(reloadedView.getItems()).get(0).getEnvironments();
        assertNotSame(before, after, "a reload can change anything on disk, so nothing may survive it");
        assertEquals("1.0.0", after.get(0).getCurrentAction().getBuildNumber());
    }

    @Test
    void theHistoryIsCappedAndSaysSoWhenItIs(JenkinsRule j) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("long-history");
        job.getBuildersList().add(new Deployment("production", "1.0.0"));
        FreeStyleBuild build = j.buildAndAssertSuccess(job);
        Deployment.DeploymentAction action = build.getAction(Deployment.DeploymentAction.class);
        assertNotNull(action);

        List<Deployment.DeploymentAction> overLimit = new ArrayList<>();
        for (int i = 0; i < DeploymentView.HISTORY_LIMIT + 5; i++) {
            overLimit.add(action);
        }
        DeploymentView.Unit.Environment capped = new DeploymentView.Unit.Environment("production", overLimit);
        assertEquals(DeploymentView.HISTORY_LIMIT, capped.getActions().size(), "the modal must not grow without bound");
        assertEquals(DeploymentView.HISTORY_LIMIT + 5, capped.getTotalCount());
        assertTrue(capped.isTruncated(), "a shortened history has to admit it");

        DeploymentView.Unit.Environment whole =
                new DeploymentView.Unit.Environment("production", overLimit.subList(0, 3));
        assertEquals(3, whole.getActions().size());
        assertEquals(3, whole.getTotalCount());
        assertFalse(whole.isTruncated());
    }
}
