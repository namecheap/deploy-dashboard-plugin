# Contribution

Thank you for your interest in making [Deploy Dashboard Plugin](https://github.com/jenkinsci/deploy-dashboard-plugin) even better and more awesome. Your contributions are highly welcome.

Plugin source code is hosted on [GitHub](https://github.com/jenkinsci/deploy-dashboard-plugin). New feature proposals and bug fix proposals should be submitted as [GitHub pull requests](https://help.github.com/en/github/collaborating-with-issues-and-pull-requests/creating-a-pull-request). Your pull request will be evaluated by [GitHub Actions](../../actions): the test suite on Linux and Windows, an end-to-end run against a real Jenkins, and the Jenkins Security Scan.

## Development

There is an official [Jenkins plugin development guide](https://www.jenkins.io/doc/developer/). All the details you will find there.

In short, you have to be familiar with java (JDK 21 or 25 is required) and maven build tool.

```bash
./mvnw clean install
```

This command will build the plugin. The `hpi` file you can find in the `target` folder.

The build enforces the standard jenkinsci formatting, so if it fails with
`The following files had format violations`, run:

```
./mvnw spotless:apply
```

It also fails on imports the Jenkins project has retired: `StaplerRequest`/`StaplerResponse`
(use the `2` variants), JUnit 4, and `org.apache.commons.lang` (use `lang3`).

## Release (Only for Plugin's maintainers)

See [docs/RELEASE.md](docs/RELEASE.md). The instructions used to be written out
in both places and had already begun to disagree.
