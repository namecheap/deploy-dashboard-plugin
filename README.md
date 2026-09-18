# Jenkins Deploy Dashboard Plugin

#### Overview

This Jenkins plugin creates a custom view which can be used as a dashboard to display what code release versions have been deployed to what test and production environments (or devices).

![Demo](docs/images/demo.gif)


## Deployment View 
#### Add new view

On the Jenkins main page or folder, click the + tab to start the new view wizard (If you
do not see a +, it is likely you do not have permission to create a new view). 
On the "create new view" page, give your view a name and select the Deployment View
type and click ok.

![Create view](docs/images/create-view.png)


Select the list of jobs to include in the view. This is exactly the
same process as the standard list view that comes with Jenkins.

![Configure view 1](docs/images/configure-view-01.png)

Also a regular expression can be used to specify the jobs to include in
the view. (e.g.: `.*` will select all the jobs in the folder)

![Create new view 2](docs/images/configure-view-02.png)

#### How it looks like

![Dashboard](docs/images/dashboard-view.png)

You can click on the specific environment and get the release history

![Dashboard History](docs/images/release-history-view.png)


#### Pipeline | Add a new release to the environment
```groovy
properties([parameters([
    string(name: 'version', description: 'App version to deploy'),
    choice(name: 'env', choices: ['dev', 'prod'], description: 'Environment where the app should be deployed')
])])

node {
    stage("Deploy") {
        // Deploy app version ${params.version} to ${params.env} environment
        
        //add release information to dashboard
        addDeployToDashboard(env: params.env, buildNumber: params.version)
    }
}
```



## Add action button feature
There is one more useful feature which this plugin can do. You can add additional links to a build, pointing wherever
you like -- most usefully at the job that deploys what was just built.
This feature doesn't have any binding with Deploy Dashboard feature, it's just comfortable to use them together.

Where those links appear depends on the Jenkins version; see
[Notes on modern Jenkins](#notes-on-modern-jenkins-plugin-020) below.

#### Pipeline | Add button
E.g.: We are building an app and add the the button which will link to deployment job
```groovy
node {
    stage("Build") {
        String builtVersion = "v2.7.5"
        // Build app with ${builtVersion} version

        // Add links to the build
        buildAddUrl(title: 'Deploy to DEV', url: "/job/app-deploy/parambuild/?env=dev&version=${builtVersion}")
        buildAddUrl(title: 'Deploy to PROD', url: "/job/app-deploy/parambuild/?env=prod&version=${builtVersion}")
    }
}
```
#### How it looks like

![Sidebar](docs/images/deploy-action.png)

#### Restricting who is shown a link

A link can be narrowed to particular people with the optional `visibleTo`:

```groovy
buildAddUrl(title: 'Deploy to PROD', url: '/job/deploy-prod/parambuild/?version=1.2.3',
            visibleTo: [users: ['alice'], groups: ['release-managers']])
```

Listing neither users nor groups means no narrowing, and the link behaves as it
always has. When something is listed, the link is hidden from everyone else in
the sidebar and the details bar, **and its URL answers 404** — hiding an icon
is not access control, since the target is in the page source and the link's
path is derivable.

This only narrows; it cannot widen. Jenkins has already decided who may read
the build before any of this runs.

**It is not the security boundary for deploying.** The job the link points at
enforces its own permissions -- a `parambuild` URL still requires `Item.BUILD`
on that job. `visibleTo` decides who is shown a shortcut, not who may use what
it points at.

#### Notes on modern Jenkins (plugin 0.2.0+)

Since 0.2.0 the button no longer exposes the raw target URL as the action URL.
Each button is served under a stable path on the build
(`.../<build>/deploy-link-<hash>`) that answers with an HTTP redirect to the
configured URL, so query strings (e.g. `parambuild` links with prefilled
parameters) always reach the browser intact regardless of how the UI renders
the link. Only root-relative paths and `http(s)` URLs are allowed as targets.

On the redesigned build pages (Jenkins 2.5xx new build page, Pipeline Graph
View pages) — which no longer render the classic left sidebar — the buttons
additionally appear in the build details bar next to the build title.


## License

This plugin is licensed under the Apache license 2.0, see [LICENSE](LICENSE).
