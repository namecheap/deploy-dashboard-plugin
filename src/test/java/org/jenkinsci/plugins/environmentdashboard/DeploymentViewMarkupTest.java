package org.jenkinsci.plugins.environmentdashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleProject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * An id has to be unique in a document. The dashboard emitted the same one on
 * every row and every modal, which is invalid HTML and makes getElementById
 * pick whichever came first.
 */
@WithJenkins
class DeploymentViewMarkupTest {

    private static HtmlPage dashboardWithSeveralRows(JenkinsRule j) throws Exception {
        // Two jobs, two environments each: four rows and four modals, which is
        // what turned one id into eight.
        for (String name : List.of("alpha", "beta")) {
            FreeStyleProject job = j.createFreeStyleProject(name);
            job.getBuildersList().add(new Deployment("production", "1.0.0"));
            job.getBuildersList().add(new Deployment("staging", "1.0.0"));
            j.buildAndAssertSuccess(job);
        }
        DeploymentView view = new DeploymentView("deployments");
        j.jenkins.addView(view);
        view.setIncludeRegex(".*");
        view.save();
        return j.createWebClient().goTo("view/deployments/");
    }

    @Test
    void everyIdOnTheDashboardIsUnique(JenkinsRule j) throws Exception {
        HtmlPage page = dashboardWithSeveralRows(j);

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Object node : page.getByXPath("//*[@id]")) {
            String id = ((DomElement) node).getId();
            if (!id.isEmpty()) {
                counts.merge(id, 1, Integer::sum);
            }
        }
        List<String> duplicated = new ArrayList<>();
        counts.forEach((id, n) -> {
            if (n > 1) {
                duplicated.add(id + " x" + n);
            }
        });

        assertEquals(List.of(), duplicated, "ids must be unique in the document");
    }

    @Test
    void theModalsAreStillReachableByTheirOwnIds(JenkinsRule j) throws Exception {
        // The ids that carry meaning are the per-modal ones the toggle points
        // at; removing the decorative duplicates must not touch them.
        HtmlPage page = dashboardWithSeveralRows(j);
        String html = page.getWebResponse().getContentAsString();

        for (Object node : page.getByXPath("//a[@class='edb-popup-toggle']")) {
            String target = ((DomElement) node).getAttribute("data-popup-id");
            assertTrue(
                    html.contains("id=\"" + target + "\""),
                    "the toggle points at " + target + ", which must exist exactly once");
            assertEquals(1, page.getByXPath("//*[@id='" + target + "']").size());
        }
    }
}
