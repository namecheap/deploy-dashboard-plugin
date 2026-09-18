package org.jenkinsci.plugins.environmentdashboard;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import java.io.IOException;
import jenkins.model.RunAction2;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

public class Deployment extends Builder implements SimpleBuildStep {

    static final String REQUIRED_ENV = "addDeployToDashboard: env is required";
    static final String REQUIRED_BUILD_NUMBER = "addDeployToDashboard: buildNumber is required";

    private final String env;
    private final String buildNumber;

    @DataBoundConstructor
    public Deployment(String env, String buildNumber) {
        this.env = env;
        this.buildNumber = buildNumber;
    }

    public String getEnv() {
        return env;
    }

    public String getBuildNumber() {
        return buildNumber;
    }

    /**
     * Both arguments are required, and a missing one fails the build.
     *
     * <p>They used to be recorded exactly as given. A null env then reached
     * {@code DeploymentView.getEnvs()}, whose {@code groupingBy} rejects a null
     * key -- so one build calling {@code addDeployToDashboard(buildNumber:
     * '1.2.3')} without an env returned HTTP 500 for the entire dashboard, for
     * every job on it and every user, until that build was deleted. The step
     * itself had succeeded, so nothing pointed the author at the cause.
     *
     * <p>Failing here instead puts the error in the build that caused it, at
     * the moment it is made, which is the only place it can be acted on.
     */
    @Override
    public void perform(
            @NonNull Run<?, ?> run,
            @NonNull FilePath workspace,
            @NonNull EnvVars environment,
            @NonNull Launcher launcher,
            @NonNull TaskListener listener
    ) throws InterruptedException, IOException {
        String cleanEnv = Util.fixEmptyAndTrim(env);
        String cleanBuildNumber = Util.fixEmptyAndTrim(buildNumber);
        if (cleanEnv == null) {
            throw new AbortException(REQUIRED_ENV);
        }
        if (cleanBuildNumber == null) {
            throw new AbortException(REQUIRED_BUILD_NUMBER);
        }
        run.addAction(new DeploymentAction(
                cleanEnv,
                cleanBuildNumber
        ));
    }

    @Extension
    @Symbol("addDeployToDashboard")
    public static class DescriptorImpl extends BuildStepDescriptor<Builder> {
        @Override
        @NonNull
        public String getDisplayName() {
            return "Deployment";
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> t) {
            return true;
        }

        // Pure string checks: no state change, no I/O, so no permission is required
        // beyond the READ the form itself needs. POST because form validation is
        // submitted that way and it keeps the endpoint out of link-based CSRF.
        @POST
        // lgtm[jenkins/no-permission-check]
        public FormValidation doCheckEnv(@QueryParameter String value) {
            return Util.fixEmptyAndTrim(value) == null ? FormValidation.error(REQUIRED_ENV) : FormValidation.ok();
        }

        @POST
        // lgtm[jenkins/no-permission-check]
        public FormValidation doCheckBuildNumber(@QueryParameter String value) {
            return Util.fixEmptyAndTrim(value) == null
                    ? FormValidation.error(REQUIRED_BUILD_NUMBER)
                    : FormValidation.ok();
        }
    }

    public static final class DeploymentAction implements RunAction2 {

        /**
         * Injected by {@link #onAttached}/{@link #onLoad}, never persisted.
         *
         * <p>Without {@code transient} XStream wrote the owner into the run's
         * own build.xml as a positional back-reference:
         *
         * <pre>{@code <run class="flow-build" reference="../../.."/>}</pre>
         *
         * <p>which is not state this action owns, and which anything that moves
         * the action inside {@code <actions>} turns into a dangling pointer --
         * XStream then reports a conversion error and drops the action, so the
         * deployment disappears from the dashboard.
         */
        private transient Run<?, ?> run;

        private String env;
        private String buildNumber;

        public DeploymentAction(String env, String buildNumber) {
            this.env = env;
            this.buildNumber = buildNumber;
        }

        @Override
        public String getIconFileName() {
            return null;
        }

        @Override
        public String getDisplayName() {
            return String.format(
                    "Deployment %s to %s",
                    buildNumber,
                    env
            );
        }

        @Override
        public String getUrlName() {
            return null;
        }

        public String getBuildNumber() {
            return buildNumber;
        }

        public String getEnv() {
            return env;
        }

        public Run<?, ?> getRun() {
            return run;
        }

        @Override
        public void onLoad(Run<?, ?> r) {
            this.run = r;
        }

        @Override
        public void onAttached(Run<?, ?> r) {
            this.run = r;
        }
    }
}
