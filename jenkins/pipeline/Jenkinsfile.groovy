#!/usr/bin/env groovy
import hudson.model.*

// Variables

BuildVersion = "1.0.0.${env.BUILD_NUMBER}"
GitURL = "http://gitlab/<account>/<project>"
GitCredential = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
JiraKey = 'KEY'
JiraJQL = "PROJECT = '${JiraKey}' and (status = Finished and (issuetype = Bug OR issuetype = Improvement OR issuetype = Story) or (issuetype = Story and status = Done)) and Sprint in openSprints() and created > startOfYear() and 'Integrated Version' is EMPTY ORDER BY Rank ASC"
TeamsWebHook = "https://outlook.office.com/webhook/f3eda2fa-83d1-46b7-86b9-665522e7b0dd@f4d16f32-3894-45d2-aa43-4998abfff44c/JenkinsCI/589ed84bc32c425a8367b34c58babdac/b0b191c5-edb3-4798-acd9-e5f67348855f"
projectPath = "Project"

pipeline {

    agent { label 'pipeline' }

    options {
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '20', artifactDaysToKeepStr: ''))
    }   

    stages {
        stage('Init') {
            steps {
                // Block merge request while pipeline is broken
                //updateGitlabCommitStatus name: 'build', state: 'pending'
                printConfigurations()
                checkout scm: [
                    $class: 'GitSCM',
                    userRemoteConfigs: [[
                        url:"${GitURL}",
                        credentialsId: "${GitCredential}"
                    ]],
                    branches: [[
                        name: gitBranchClone()
                    ]]
                ], poll: false
            }
        } // Close Stage 'Init'

        stage('Install') {
            steps {
                dir(projectPath) {
                    // Build Commands...
                }
            }
        } // Close Stage 'Install'

        stage('Unitary tests') {
            steps {
                echo 'Performing Unitary Tests'
                dir(projectPath) {
                    // Test Commands...
                }
            }
        } // Close Stage 'Unitary tests'

        stage('Static analysis') {
            steps {
                dir(projectPath) {
                    // Sonar Commands...
                }
            }
        } // Close Stage 'Static analysis'

        stage('Package') {
            steps {
                dir(projectPath) {
                    // Package Commands...
                }
            }
        } // Close Stage 'Package'

        stage('Release to QA') {
            when { expression { return isRelease() } }
            steps {
                dir(projectPath) {
                    // Delivery to QA Commands...
                    //archiveArtifacts artifacts: 'build/**/*distribution*.zip', allowEmptyArchive: true
                    //jiraIntegration()
                    notifyMessage(
                        "A new release has been generated on ${env.JOB_NAME} [${env.BUILD_NUMBER}]",
                        "The new release can be found [here](${env.BUILD_URL}) and manual tests can proceed"
                    )
                }
            }
        } // Close Stage 'Release to QA'

        stage('Delivery to Prod') {
            when { expression { return isDelivery() } }
            steps {
                dir(projectPath) {
                    // Delivery to Production Commands...
                    //archiveArtifacts artifacts: 'build/**/*distribution*.zip', allowEmptyArchive: true
                    notifyMessage(
                        "A new delivery has been generated on ${env.JOB_NAME} [${env.BUILD_NUMBER}]",
                        "The new delivery can be found [here](${env.BUILD_URL})"
                    )
                }
            }
        } // Close Stage 'Delivery to Prod'

    } // Close Stages Section

    post {
        failure {
            notifyError()
            updateGitlabCommitStatus name: 'build', state: 'failed'
        }
        success {
            updateGitlabCommitStatus name: 'build', state: 'success'
        }
    }

} // Close Pipeline Section

def gitBranchClone() {
    def branch = _gitlabBranch()
    return branch
}

def _gitlabBranch() {
    if (_gitlabSourceBranch() == null) {
        try {
            return gitlabBranch
        } catch (ex) {
            return 'master'
        }
    } else {
        return _gitlabSourceBranch()
    }
}

def _gitlabSourceBranch() {
    try {
        return gitlabSourceBranch
    } catch (ex) {
        return null
    }
}

def isMaster() {
    return _gitlabBranch().endsWith('master')
}

def isRelease() {
    return _gitlabBranch().startsWith('refs/tags/r-')
}

def isDelivery() {
    return _gitlabBranch().startsWith('refs/tags/d-')
}

def notifyMessage(title, message) {
     def notifySuccessWebhook = "${TeamsWebHook}"

     sh("curl -d \"{'@context': 'http://schema.org/extensions','@type': 'MessageCard','themeColor': '35B535','title': '$title','text': '$message'}\" -H \"Content-Type: application/json\" -X POST $notifySuccessWebhook")
}

def notifyError() {
    def title="Build error on ${env.JOB_NAME} [${env.BUILD_NUMBER}]"
    def message="Jenkins has been slain by $gitlabUserEmail on build [${env.BUILD_NUMBER}](${env.BUILD_URL}/console)"

    def notifyErrorWebhook = "${TeamsWebHook}"
    sh("curl -d \"{'@context': 'http://schema.org/extensions','@type': 'MessageCard','themeColor': 'b63939','title': '$title','text': '$message'}\" -H \"Content-Type: application/json\" -X POST $notifyErrorWebhook")

    try {
        mail subject: "[JENKINS] ${env.JOB_NAME} (#${env.BUILD_NUMBER}) ~ Failure",
            body: "A new build for [${env.JOB_NAME}] has been performed (build #${env.BUILD_NUMBER}). Status is: Failure. You can check the build results and the current status at ${env.BUILD_URL}. Please fix it and try again.",
            to: gitlabUserEmail,
            replyTo: gitlabUserEmail,
            from: 'noreply@venturus.org.br'
    } catch (ex) {
        echo "Unable to send email ${ex}"
    }
}

def jiraIntegration() {

    def currDate = new Date()

    def newVersion = [
        name: "${buildProject}.${BuildVersion}",
        archived: false,
        released: true,
        description: 'Release generated',
        project: JiraKey
        //releaseDate: currDate.format('dd-MM-yyyy')
    ]

    def newVersionResp = jiraNewVersion version: newVersion, site: 'Jira'
    def versionId = newVersionResp.data.id

    newVersion.id = versionId

    echo "version: ${versionId}"
    echo "getting issues"

    // only Integrated issues
    def jiraSearch = jiraJqlSearch jql: JiraJQL, site: 'Jira'

    def issues = jiraSearch.data.issues

    /*
    * You can find the correct transition id, inspecting the issues found on the
    * jqlQuery above and calling the getIssuesTransitions from an issue in the
    * desired state.
    *
    * See more in:
    * https://jenkinsci.github.io/jira-steps-plugin/steps/issue/jira_get_issue_transitions/
    */

    // "Integrate" transition (state)
    def transtionInput = [ transition: [ id: 31 ] ]

    def sout = new StringBuilder()
    sout.append("A new version is created ($newVersion) \n\n")
    sout.append("All issues contemplated in this version are: \n")

    for (int i = 0; i < issues.size(); i++) {
        def issue = issues[i]
        def id = issue.id

        def updateIssue = [
            fields: [
                // custom field integrated version is: customfield_10401
                customfield_10401: [newVersion]
            ]
        ]

        // custom field for improvements
        jiraAddComment  idOrKey: id, comment: "A new version with this implementation was released. The version is: ${newVersion.name}", site: 'Jira'
        jiraEditIssue idOrKey: id, issue: updateIssue, site: 'Jira'

        try {
            jiraTransitionIssue idOrKey: id, input: transtionInput, site: 'Jira'
        } catch (ex) {
            echo "Error transitioning issue, please check your jql filter (${ex})"

            echo "Available transitions are: "
            def transitions = jiraGetIssueTransitions idOrKey: id, site: 'Jira'
            echo transitions.data.toString()
        }

        sout.append("      - ${issue.key} ${issue.fields.summary} - (http://jira/issues/${issue.key})\n")

        echo "Issue details: "
        echo "${issue}"
    }

    sout.append("\n\n")
    sout.append("Jenkins build: http://jenkins/job/sara_pipeline/${env.BUILD_NUMBER} \n")
    sout.append("GIT Tag: ${_gitlabBranch()}\n")
    sout.append("\n\n")
    sout.append("Best regards, \n")
    sout.append("Tools Team")

    try {
        mail subject: "[SARA] New Version: $newVersion",
            body: sout.toString(),
            to: 'devops@venturus.org.br',
            replyTo: 'noreply@venturus.org.br',
            from: 'noreply@venturus.org.br'
    } catch (ex) {
        echo "Unable to send email ${ex}"
    }
}

def printConfigurations() {
    echo '======================================'
    echo '== gitlabBranch:' + gitlabBranch
    echo '== gitlabSourceBranch:' + _gitlabSourceBranch()
    echo '== _gitlabBranch():' + _gitlabBranch()
    echo '== isRelease():' + isRelease()
    echo '== isMaster():' + isMaster()
    echo '== isDelivery():' + isDelivery()
    echo '== clonning:' + gitBranchClone()
    echo '== jiraProject:' + jiraProject
    echo '== buildVersion:' + buildVersion
    echo '== buildProject:' + buildProject
    echo '== jqlRelease:' + jqlRelease
    echo '======================================'
}
