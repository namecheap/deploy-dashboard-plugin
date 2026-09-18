package org.jenkinsci.plugins.environmentdashboard;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.Job;
import hudson.model.ListView;
import hudson.model.TopLevelItem;
import hudson.model.ViewDescriptor;
import hudson.util.FormValidation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.environmentdashboard.Deployment.DeploymentAction;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;

public class DeploymentView extends ListView {
    @DataBoundConstructor
    public DeploymentView(String name) {
        super(name);
    }

    private List<Unit.Environment> getEnvs(TopLevelItem item) {
        List<WorkflowRun> runs = Collections.emptyList();
        if (item instanceof WorkflowMultiBranchProject) {
            runs = ((WorkflowMultiBranchProject) item)
                    .getItems()
                    .stream()
                    .map(Job::getBuilds)
                    .flatMap(Collection::stream)
                    .collect(Collectors.toList());
        } else if (item instanceof WorkflowJob) {
            runs = ((WorkflowJob) item).getBuilds();
        }

        return runs
                .stream()
                // getActions, not getAction: a build that deploys to several
                // environments records one action per deployment, and the
                // singular form returns only the first -- so every environment
                // after the first silently vanished from the dashboard.
                .flatMap(run -> run.getActions(DeploymentAction.class).stream())
                // A RunAction2 always has its run set through onAttached/onLoad,
                // but the whole view dereferences it, so a broken record is
                // dropped here rather than breaking the page for every job.
                .filter(action -> action.getRun() != null)
                // groupingBy rejects a null key, so a single action recorded
                // before the step required an env took the whole page down with
                // a 500 -- every job on the view, for every user, until that one
                // build was deleted. New ones cannot be created, but the records
                // already on disk outlive the fix.
                .filter(action -> action.getEnv() != null)
                // TreeMap, not the default HashMap: the environment rows are
                // rendered in map order, and hash order is neither stable across
                // restarts nor meaningful to a reader.
                .collect(Collectors.groupingBy(
                        DeploymentAction::getEnv, TreeMap::new, Collectors.toList()))
                .entrySet()
                .stream()
                .map(e -> new Unit.Environment(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
    }

    public List<Unit> getUnits(List<? extends TopLevelItem> items) {
        return items
                .stream()
                .map(item -> new Unit(item, getEnvs(item)))
                .filter(unit -> !unit.getEnvironments().isEmpty())
                .collect(Collectors.toList());
    }

    public static class Unit {
        private final TopLevelItem job;
        private final List<Environment> environments;

        public Unit(TopLevelItem job, List<Environment> environments) {
            this.job = job;
            this.environments = environments;
        }

        public TopLevelItem getJob() {
            return job;
        }

        public List<Environment> getEnvironments() {
            return environments;
        }

        public static class Environment {
            /** Most recently started deployment first, so `get(0)` is the current one. */
            private static final Comparator<DeploymentAction> NEWEST_FIRST = Comparator.comparingLong(
                            (DeploymentAction a) -> a.getRun().getStartTimeInMillis())
                    .thenComparingInt(a -> a.getRun().getNumber())
                    .reversed();

            private final String name;
            private final List<DeploymentAction> actions;

            /**
             * Sorts on the way in rather than trusting the caller's order.
             *
             * {@link #getCurrentAction()} promises the deployment that is live
             * now, and the order it used to rely on did not carry that meaning:
             * for a multibranch project the runs arrive branch by branch, so
             * whichever branch happened to sort first supplied the "current"
             * release for every environment -- a dashboard confidently showing
             * a stale version whenever the newest deployment came from a
             * later-sorting branch. Ordering here makes the promise the class's
             * own rather than a caller's responsibility.
             */
            public Environment(String name, List<DeploymentAction> actions) {
                this.name = name;
                List<DeploymentAction> sorted = new ArrayList<>(actions);
                sorted.sort(NEWEST_FIRST);
                this.actions = Collections.unmodifiableList(sorted);
            }

            public String getName() {
                return name;
            }

            public List<DeploymentAction> getActions() {
                return actions;
            }

            public DeploymentAction getCurrentAction() {
                return actions.get(0);
            }
        }
    }

    @Extension
    public static class DeploymentViewDescriptor extends ViewDescriptor {
        public DeploymentViewDescriptor() {
            super(DeploymentView.class);
            load();
        }

        @Override
        @NonNull
        public String getDisplayName() {
            return "Deployment View";
        }

        // Copy-n-paste from ListView$Descriptor as sadly we cannot inherit from that class
        public FormValidation doCheckIncludeRegex(@QueryParameter String value) {
            String v = Util.fixEmpty(value);
            if (v != null) {
                try {
                    Pattern.compile(v);
                } catch (PatternSyntaxException pse) {
                    return FormValidation.error(pse.getMessage());
                }
            }
            return FormValidation.ok();
        }

        @Override
        public boolean configure(StaplerRequest2 req, JSONObject json) throws FormException {
            save();

            return true;
        }
    }
}
