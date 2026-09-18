package org.jenkinsci.plugins.environmentdashboard;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.Descriptor;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Builder;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import org.jenkins.ui.icon.IconSpec;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

public class BuildAddUrl extends Builder implements SimpleBuildStep {

    private final String title;
    private final String url;
    private VisibleTo visibleTo;

    @DataBoundConstructor
    public BuildAddUrl(String title, String url) {
        this.url = url;
        this.title = title;
    }

    public String getTitle() {
        return title;
    }

    public String getUrl() {
        return url;
    }

    public VisibleTo getVisibleTo() {
        return visibleTo;
    }

    @DataBoundSetter
    public void setVisibleTo(VisibleTo visibleTo) {
        this.visibleTo = visibleTo;
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Override
    public void perform(
            @NonNull Run<?, ?> run,
            @NonNull FilePath workspace,
            @NonNull EnvVars env,
            @NonNull Launcher launcher,
            @NonNull TaskListener listener)
            throws InterruptedException, IOException {
        run.addAction(new BuildUrlAction(title, url, visibleTo));
    }

    /**
     * Optional narrowing of who is shown a link, by user id or group name.
     *
     * <p>Deliberately only principal matching on top of what the authorization
     * strategy has already granted. The upstream proposal hand-rolled a
     * membership model that ignored the strategy entirely, which both hid the
     * link from administrators who were not listed and showed it to listed
     * users with no permission on the target.
     */
    public static class VisibleTo extends AbstractDescribableImpl<VisibleTo> {
        private List<String> users;
        private List<String> groups;

        @DataBoundConstructor
        public VisibleTo(List<String> users, List<String> groups) {
            this.users = clean(users);
            this.groups = clean(groups);
        }

        private static List<String> clean(List<String> raw) {
            if (raw == null) {
                return Collections.emptyList();
            }
            List<String> out = new ArrayList<>(raw.size());
            for (String entry : raw) {
                String trimmed = Util.fixEmptyAndTrim(entry);
                if (trimmed != null) {
                    out.add(trimmed);
                }
            }
            return Collections.unmodifiableList(out);
        }

        private static List<String> split(String text) {
            if (Util.fixEmptyAndTrim(text) == null) {
                return Collections.emptyList();
            }
            return clean(java.util.Arrays.asList(text.split("[,\r\n]")));
        }

        public List<String> getUsers() {
            return users;
        }

        public List<String> getGroups() {
            return groups;
        }

        /*
         * The form binds through these rather than through the lists directly.
         * A textarea submits one string, and binding that straight onto a
         * List<String> stores the list's own toString as a single element: a
         * round trip of ["alice"] came back as ["[alice]"]. The form rendered
         * perfectly well while quietly corrupting what was typed into it, which
         * a round-trip test is the only thing that shows.
         */
        public String getUsersText() {
            return String.join("\n", users);
        }

        @DataBoundSetter
        public void setUsersText(String usersText) {
            this.users = split(usersText);
        }

        public String getGroupsText() {
            return String.join("\n", groups);
        }

        @DataBoundSetter
        public void setGroupsText(String groupsText) {
            this.groups = split(groupsText);
        }

        /** No principals listed means no narrowing, not "nobody". */
        public boolean isEmpty() {
            return users.isEmpty() && groups.isEmpty();
        }

        @Extension
        @Symbol("visibleTo")
        public static class DescriptorImpl extends Descriptor<VisibleTo> {
            @Override
            @NonNull
            public String getDisplayName() {
                return "Visible to";
            }
        }
    }

    @Extension
    @Symbol("buildAddUrl")
    public static class DescriptorImpl extends BuildStepDescriptor<Builder> {
        @Override
        @NonNull
        public String getDisplayName() {
            return "Build Add Url";
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> t) {
            return true;
        }
    }

    /**
     * A sidebar/app-bar entry that navigates to the configured URL.
     *
     * The target URL (which typically carries a query string, e.g. a
     * {@code parambuild} link with prefilled parameters) is deliberately NOT
     * exposed as the action's {@code urlName}: query strings in action URLs
     * are not preserved reliably by every Jenkins UI that renders actions.
     * Instead the action is bound under a stable path segment and answers it
     * with an HTTP redirect to the real target, so the query string always
     * reaches the browser intact.
     */
    public static class BuildUrlAction implements Action, IconSpec {
        private final String title;
        private final String url;
        private final VisibleTo visibleTo;

        BuildUrlAction(String title, String url) {
            this(title, url, null);
        }

        BuildUrlAction(String title, String url, VisibleTo visibleTo) {
            this.title = title;
            this.url = url;
            this.visibleTo = visibleTo;
        }

        public VisibleTo getVisibleTo() {
            return visibleTo;
        }

        /**
         * Whether this link exists at all for the given caller.
         *
         * <p>An unset {@code visibleTo} means everyone who can read the build,
         * which is what Jenkins has already decided by the time anything here
         * runs. When it is set, this narrows that further; it never widens it,
         * so it cannot grant anyone access the authorization strategy withheld.
         *
         * <p>It is also not the security boundary for deploying. The target of
         * the link enforces its own permissions -- a {@code parambuild} URL
         * still requires Item.BUILD on the job it points at. This decides who
         * is shown a shortcut.
         */
        public boolean isVisibleTo(Authentication authentication) {
            if (visibleTo == null || visibleTo.isEmpty()) {
                return true;
            }
            if (authentication == null) {
                return false;
            }
            if (visibleTo.getUsers().contains(authentication.getName())) {
                return true;
            }
            for (GrantedAuthority authority : authentication.getAuthorities()) {
                if (authority.getAuthority() != null && visibleTo.getGroups().contains(authority.getAuthority())) {
                    return true;
                }
            }
            return false;
        }

        public boolean isVisible() {
            return isVisibleTo(Jenkins.getAuthentication2());
        }

        @Override
        public String getIconFileName() {
            if (!isVisible()) {
                return null;
            }
            // Hardcoded artifact id: getClass().getPackage().getImplementationTitle()
            // is unreliable under modern plugin classloaders (may return null).
            return "/plugin/deploy-dashboard/deploy.png";
        }

        @Override
        public String getIconClassName() {
            return isVisible() ? "symbol-rocket-outline plugin-ionicons-api" : null;
        }

        @Override
        public String getDisplayName() {
            return title;
        }

        @Override
        public String getUrlName() {
            return "deploy-link-" + Util.getDigestOf(title + "|" + url).substring(0, 12);
        }

        public String getUrl() {
            return url;
        }

        /**
         * Only root-relative paths and http(s) URLs may be redirected to;
         * anything else (javascript:, data:, protocol-relative) is refused.
         */
        boolean isSafeUrl() {
            String target = Util.fixEmptyAndTrim(url);
            if (target == null) {
                return false;
            }
            if (target.startsWith("/")) {
                return !target.startsWith("//");
            }
            try {
                String scheme = new URI(target).getScheme();
                if (scheme == null) {
                    return false;
                }
                scheme = scheme.toLowerCase(Locale.ROOT);
                return scheme.equals("http") || scheme.equals("https");
            } catch (URISyntaxException e) {
                return false;
            }
        }

        /**
         * A GET that only redirects. It must stay GET (users click it from the
         * sidebar and the details bar), it has no side effects, and it is only
         * reachable through the run's URL, which Jenkins already gates on
         * Item.READ while resolving the job and the build.
         *
         * <p>It does check {@code visibleTo}. Hiding the icon is not access
         * control -- the URL is in the page source, and this endpoint has a
         * derivable path -- so a link someone is not meant to see answers 404
         * here rather than merely being absent from their sidebar.
         */
        // lgtm[jenkins/csrf]
        public void doIndex(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
            if (!isSafeUrl() || !isVisible()) {
                rsp.sendError(StaplerResponse2.SC_NOT_FOUND);
                return;
            }
            rsp.sendRedirect2(url);
        }
    }
}
