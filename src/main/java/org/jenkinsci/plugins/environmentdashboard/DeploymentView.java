package org.jenkinsci.plugins.environmentdashboard;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Job;
import hudson.model.ListView;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.TopLevelItem;
import hudson.model.ViewDescriptor;
import hudson.model.listeners.ItemListener;
import hudson.model.listeners.RunListener;
import hudson.util.FormValidation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.environmentdashboard.Deployment.DeploymentAction;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.verb.POST;

public class DeploymentView extends ListView {
    @DataBoundConstructor
    public DeploymentView(String name) {
        super(name);
    }

    /**
     * Every build underneath an item, whatever kind of item it is.
     *
     * <p>This used to name two Pipeline types outright, and anything else --
     * freestyle, matrix, a job inside a folder -- produced no runs at all, so
     * the job was quietly dropped from the view. The build step is a
     * {@link hudson.tasks.Builder} whose descriptor is applicable to every
     * project type, so those deployments were recorded and then never shown,
     * with nothing to say why.
     *
     * <p>A multibranch project is just an {@link ItemGroup} of jobs, so the
     * generic walk covers it without the view having to know the type. An item
     * that is both -- a matrix project, whose configurations run their own
     * builds -- contributes from both sides.
     */
    private static Stream<Run<?, ?>> runsOf(Item item) {
        Stream<Run<?, ?>> own = item instanceof Job
                ? ((Job<?, ?>) item).getBuilds().stream().map(run -> (Run<?, ?>) run)
                : Stream.empty();
        Stream<Run<?, ?>> nested = item instanceof ItemGroup
                ? ((ItemGroup<?>) item).getItems().stream().flatMap(DeploymentView::runsOf)
                : Stream.empty();
        return Stream.concat(own, nested);
    }

    /**
     * How many deployments per environment the release-history modal carries.
     *
     * <p>It used to render every deployment ever made, for every environment of
     * every job, into hidden divs on the dashboard -- so the page grew without
     * bound and a controller with a long history served megabytes of DOM to
     * every viewer. The modal is a recent-history panel; when it is cut short
     * it says so rather than quietly showing less than it used to.
     */
    static final int HISTORY_LIMIT = 50;

    /**
     * Keyed by item full name, shared by every view showing that item.
     *
     * <p>Entries are validated against {@link #fingerprint}, not trusted for a
     * period of time: a stale release on a deployment dashboard is worse than a
     * slow one.
     */
    private static final Map<String, Snapshot> CACHE = new ConcurrentHashMap<>();

    private static final class Snapshot {
        private final long fingerprint;
        private final List<Unit.Environment> environments;

        Snapshot(long fingerprint, List<Unit.Environment> environments) {
            this.fingerprint = fingerprint;
            this.environments = environments;
        }
    }

    /**
     * Cheap summary of everything that could change what the dashboard shows.
     *
     * <p>Reads only each job's next-build number and its last completed build,
     * so it costs one run load per job at most -- against the full scan it
     * guards, which loads every build.xml of every job on every render.
     *
     * <p>The next-build number moves when a build starts, the last completed
     * number when one finishes, and the job count when jobs come and go. A
     * deployment is recorded during a build, so finishing is the event that
     * matters and the second term is what catches it.
     *
     * <p>The job's object identity is in there because a reload rebuilds every
     * item from disk with the same numbers: the cached entry would validate,
     * while holding runs that are no longer the ones Jenkins is serving. A
     * reload does not fire {@link ItemListener#onLoaded()}, which is how that
     * was found, so this does not depend on being told.
     */
    private static long fingerprint(Item item) {
        long acc = 17;
        for (Job<?, ?> job : jobsOf(item).collect(Collectors.toList())) {
            Run<?, ?> lastCompleted = job.getLastCompletedBuild();
            acc = acc * 31 + job.getFullName().hashCode();
            acc = acc * 31 + System.identityHashCode(job);
            acc = acc * 31 + job.getNextBuildNumber();
            acc = acc * 31 + (lastCompleted == null ? -1 : lastCompleted.getNumber());
        }
        return acc;
    }

    private static Stream<Job<?, ?>> jobsOf(Item item) {
        Stream<Job<?, ?>> own = item instanceof Job ? Stream.of((Job<?, ?>) item) : Stream.empty();
        Stream<Job<?, ?>> nested = item instanceof ItemGroup
                ? ((ItemGroup<?>) item).getItems().stream().flatMap(DeploymentView::jobsOf)
                : Stream.empty();
        return Stream.concat(own, nested);
    }

    private List<Unit.Environment> getEnvs(TopLevelItem item) {
        long fingerprint = fingerprint(item);
        Snapshot cached = CACHE.get(item.getFullName());
        if (cached != null && cached.fingerprint == fingerprint) {
            return cached.environments;
        }
        List<Unit.Environment> environments = computeEnvs(item);
        CACHE.put(item.getFullName(), new Snapshot(fingerprint, environments));
        return environments;
    }

    /**
     * Deleting a build cannot move any of the numbers {@link #fingerprint}
     * reads, so it is the one change that has to announce itself.
     */
    @Extension
    public static class CacheInvalidator extends RunListener<Run<?, ?>> {
        @Override
        public void onDeleted(Run<?, ?> run) {
            CACHE.clear();
        }

        @Override
        public void onFinalized(Run<?, ?> run) {
            // Belt and braces: onFinalized runs after the actions are attached
            // and saved, so even if a job's numbers somehow read the same, the
            // next render recomputes.
            CACHE.remove(run.getParent().getFullName());
            for (ItemGroup<?> parent = run.getParent().getParent();
                    parent instanceof Item;
                    parent = ((Item) parent).getParent()) {
                CACHE.remove(((Item) parent).getFullName());
            }
        }

        @Override
        public void onStarted(Run<?, ?> run, TaskListener listener) {
            // No-op: a started build has recorded nothing yet, and the
            // fingerprint already moved with the next-build number.
        }
    }

    /**
     * Renaming or deleting an item strands the name the cache is keyed by.
     * Rare, so it just drops everything.
     *
     * <p>{@code onLoaded} does not fire on {@code Jenkins.reload()}; that case
     * is handled by the job identity in {@link #fingerprint} rather than here.
     */
    @Extension
    public static class CacheReset extends ItemListener {
        @Override
        public void onLoaded() {
            CACHE.clear();
        }

        @Override
        public void onDeleted(Item item) {
            CACHE.clear();
        }

        @Override
        public void onLocationChanged(Item item, String oldFullName, String newFullName) {
            CACHE.clear();
        }
    }

    private List<Unit.Environment> computeEnvs(TopLevelItem item) {
        return runsOf(item)
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
                .collect(Collectors.groupingBy(DeploymentAction::getEnv, TreeMap::new, Collectors.toList()))
                .entrySet()
                .stream()
                .map(e -> new Unit.Environment(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
    }

    public List<Unit> getUnits(List<? extends TopLevelItem> items) {
        return items.stream()
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
            private final int totalCount;

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
                this.totalCount = actions.size();
                List<DeploymentAction> sorted = new ArrayList<>(actions);
                sorted.sort(NEWEST_FIRST);
                this.actions = Collections.unmodifiableList(
                        new ArrayList<>(sorted.subList(0, Math.min(sorted.size(), HISTORY_LIMIT))));
            }

            public String getName() {
                return name;
            }

            public List<DeploymentAction> getActions() {
                return actions;
            }

            /** Deployments to this environment in total, before the history limit. */
            public int getTotalCount() {
                return totalCount;
            }

            public boolean isTruncated() {
                return totalCount > actions.size();
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
        // Compiles a regex and nothing else; see Deployment.DescriptorImpl for why no permission check.
        @POST
        // lgtm[jenkins/no-permission-check]
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
