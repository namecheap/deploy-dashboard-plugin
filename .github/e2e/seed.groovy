// Seeds the e2e Jenkins: no security (this container is ephemeral and only ever
// reachable from the job that started it), one pipeline that exercises both of
// the plugin's build steps, and the Deployment View the plugin exists to render.
import hudson.model.FreeStyleProject
import hudson.security.AuthorizationStrategy
import hudson.security.SecurityRealm
import jenkins.model.Jenkins
import org.jenkinsci.plugins.environmentdashboard.Deployment
import org.jenkinsci.plugins.environmentdashboard.DeploymentView
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.job.WorkflowJob

def jenkins = Jenkins.get()

jenkins.setSecurityRealm(SecurityRealm.NO_AUTHENTICATION)
jenkins.setAuthorizationStrategy(AuthorizationStrategy.UNSECURED)
jenkins.setCrumbIssuer(null)

// Two environments from ONE build, which is the case the singular getAction()
// used to drop, and a buildAddUrl whose target carries a query string -- the
// thing the redirecting action exists to keep intact.
def pipeline = '''
node {
  addDeployToDashboard(env: 'staging', buildNumber: '3.1.4')
  addDeployToDashboard(env: 'production', buildNumber: '3.1.4')
  buildAddUrl(title: 'Redeploy', url: '/job/deploy-app/parambuild/?version=3.1.4&region=eu-west-1')
}
'''

def job = jenkins.getItem('deploy-app') as WorkflowJob
if (job == null) {
    job = jenkins.createProject(WorkflowJob, 'deploy-app')
}
job.setDefinition(new CpsFlowDefinition(pipeline, true))
job.save()

// A freestyle job, which the view used to drop on the floor: the build step is
// offered for every project type and records the deployment, but the view only
// knew how to find builds of two Pipeline types, so the job was selected in the
// view's configuration and then never rendered.
def legacy = jenkins.getItem('legacy-app') as FreeStyleProject
if (legacy == null) {
    legacy = jenkins.createProject(FreeStyleProject, 'legacy-app')
}
legacy.buildersList.clear()
legacy.buildersList.add(new Deployment('qa', '2.7.0'))
legacy.save()

if (jenkins.getView('deployments') == null) {
    def view = new DeploymentView('deployments')
    jenkins.addView(view)
    view.setIncludeRegex('.*')
    view.save()
}

jenkins.save()
println '[e2e] seeded deploy-app, legacy-app and the deployments view'
