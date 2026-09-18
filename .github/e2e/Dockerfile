# A stock Jenkins with the plugin installed the way a user installs it.
#
# Built rather than bind-mounted on purpose: `jenkins-plugin-cli` runs as the
# `jenkins` user and cannot write into a host-owned mount, and the ref directory
# has to be readable by that user for Jenkins to seed JENKINS_HOME from it.
# The tag is written out in full, and deliberately not behind an ARG.
# Dependabot's Dockerfile parser matches a literal image:tag on the FROM line
# and has no handling for ${VARIABLE}, so an ARG-indirected base image is
# invisible to it and would never be bumped. The compatibility workflow tests
# the weekly line by rewriting this line, and checks that the rewrite took.
FROM jenkins/jenkins:2.568.3-jdk21

# The plugin's own dependencies, resolved from the update centre exactly as they
# would be for a real installation. Nothing is pinned: the point of this job is
# that the plugin still works against current Jenkins.
RUN jenkins-plugin-cli --plugins workflow-aggregator workflow-multibranch ionicons-api

COPY --chown=jenkins:jenkins deploy-dashboard.hpi /usr/share/jenkins/ref/plugins/deploy-dashboard.jpi
COPY --chown=jenkins:jenkins seed.groovy /usr/share/jenkins/ref/init.groovy.d/seed.groovy

ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false -Dhudson.model.DownloadService.noSignatureCheck=true"
