package org.jenkinsci.plugins.environmentdashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * The view inherits {@link hudson.model.ListView}'s job selection, and core
 * reads the ticked jobs out of the submitted form as {@code item_<relative
 * name>} (see {@code ListView.submitImpl}). A configuration form that names the
 * checkboxes any other way leaves ticking a job doing nothing at all, silently.
 */
@WithJenkins
class DeploymentViewConfigTest {

    @Test
    void aJobTickedInTheConfigurationFormIsSelected(JenkinsRule j) throws Exception {
        j.createFreeStyleProject("alpha");
        j.createFreeStyleProject("beta");
        DeploymentView view = new DeploymentView("deployments");
        j.jenkins.addView(view);

        try (JenkinsRule.WebClient wc = j.createWebClient()) {
            HtmlPage page = wc.goTo("view/deployments/configure");
            HtmlForm form = page.getFormByName("viewConfig");
            form.getInputByName("item_alpha").setChecked(true);
            j.submit(form);
        }

        assertEquals(
                List.of(j.jenkins.getItem("alpha")),
                view.getItems(),
                "the job ticked in the form must be the job the view shows");
    }

    @Test
    void theRegexPathStillWorks(JenkinsRule j) throws Exception {
        // This is the only selection mechanism that worked, so it is the one
        // every existing installation is relying on. It must survive the fix.
        j.createFreeStyleProject("gamma-one");
        j.createFreeStyleProject("delta");
        DeploymentView view = new DeploymentView("regex-view");
        j.jenkins.addView(view);

        try (JenkinsRule.WebClient wc = j.createWebClient()) {
            HtmlPage page = wc.goTo("view/regex-view/configure");
            HtmlForm form = page.getFormByName("viewConfig");
            form.getInputByName("useincluderegex").setChecked(true);
            form.getInputByName("includeRegex").setValue("gamma-.*");
            j.submit(form);
        }

        assertEquals("gamma-.*", view.getIncludeRegex());
        assertEquals(List.of(j.jenkins.getItem("gamma-one")), view.getItems());
    }

    @Test
    void recursingIntoFoldersIsStillOffered(JenkinsRule j) throws Exception {
        // The copied form carried its own "Recurse in subfolders" entry, and
        // the dashboard walks folders since #9, so the option has to survive.
        j.createFolder("team").createProject(hudson.model.FreeStyleProject.class, "nested");
        DeploymentView view = new DeploymentView("recurse-view");
        j.jenkins.addView(view);

        try (JenkinsRule.WebClient wc = j.createWebClient()) {
            HtmlPage page = wc.goTo("view/recurse-view/configure");
            HtmlForm form = page.getFormByName("viewConfig");
            // Core renders it through field="recurse", hence the databinding
            // prefix; the plugin's copy used a bare name.
            form.getInputByName("_.recurse").setChecked(true);
            j.submit(form);
        }

        assertTrue(view.isRecurse(), "the recurse option must still be wired up");
    }
}
