#!/usr/bin/env groovy
import groovy.json.JsonSlurper

/**
 * Script Name: prHygieneStatusChecks.groovy
 * Purpose: This script helps in adding PR Hygiene status checks e.g. files changed in a PR , lines changed etc within a PR.
       # These check helps teams/PR authors to keep a check on PR size and encourages them to create smaller PRs.
       # PR Hygiene status checks PR further helps PR reviewers to quickly understand if PR Hygiene Status checks are GREEN wrt limits defined in prHygiene MAP.
       # Very easy to integrate with any Jenkins pipeline.
 * Author: Vivek Dubey(https://github.com/vivekdubeyvkd)
 * Additional details:
    * This script updates the github checks for pull requests based on the flags set in prHygiene MAP for various attributes.
      Few important and supported flags are
       # context: The check name
       # status: PENDING | SUCCESS | FAILURE depending on the status of the check. It overrides the status if called several times for the same context
       # description: More meaningful information shown in the github checks for a PR
   * We are going to implement the initial checks for PR Size i.e. count of files changed and number of lines of code changed in a PR
   * Default values:
       # count of changed files : 10
       # count of lines of code changed: 300
   * By default, PR hygiene status checks do not block a PR for merge, so they act like notifications to keep PR author and PR reviewers informed about PR size.
       # These PR hygiene status checks can be configured as required to allow a PR merge by updating GitHub branch protection rule.
       # GitHub Repo Admins can update and set this rule for concerned GitHub repo
 **/

def checkAndUpdatePRStatus(String inputContext, String inputGithubCredentialsId, String inputDescription, String inputTargetUrl, Integer inputChangedCount, Integer inputChangedCountLimit) {
    if (inputChangedCount > inputChangedCountLimit) {
        status = "FAILURE"
    } else {
        status = "SUCCESS"
    }
    // update PR status check for number of files changed
    retry(2) {
        // Update GitHub API URL as per your GitHub setup
        githubNotify context: inputContext, credentialsId: inputGithubCredentialsId, gitApiUrl: 'https://github.com/api/v3/', description: inputDescription, status: status, targetUrl: inputTargetUrl
    }
    // print successful msg once PR status is updated
    echo "prHygiene: successfully set status=${status} description='${inputDescription}'  for context='${inputContext}'"
}

def prHygieneStatusChecks(Map config, String branchName = env.BRANCH_NAME) {
    // validate if PR hygiene status check is applicable
    def disableStatusCheckFlag = config?.disableStatusCheck
    if (disableStatusCheckFlag) {
        echo "prHygiene: skipping PR hygiene check because config.prHygiene.disableStatusCheck is set."
        return   
    }
    // check if branch is not null
    if (!branchName) {
        echo "prHygiene: we cannot check the PR because input github branch name is NULL"
        return
    }
    // check if branch name starts with PR-
    if (!branchName.startsWith("PR-")) {
        echo "prHygiene: we cannot check the PR because input github branch name does not start with PR and this is not a PR build"
        return
    }

    // define all required variables using config map and environment variables
    String gitOwner = env.OWNER_NAME?.toString()
    // check if gitOwner not null
    if (!gitOwner) {
        echo "prHygiene: we cannot check the PR because GitHub Org Name is not set."
        return
    }
    String gitRepo = env.REPO_NAME?.toString()
    // check if gitRepo not null
    if (!gitRepo) {
        echo "prHygiene: we cannot check the PR because GitHub Repo Name is not set."
        return
    }  
    // define variables using config map and environment vairables
    String targetUrl = env.BUILD_URL?.toString() ?: env.RUN_DISPLAY_URL?.toString()
    String githubCredentialsId = config?.githubCredentialsId ?: 'github-creds-on-jenkins'
    Integer changedFileCountLimit = config?.changedFileCountLimit?.toInteger() ?: 10
    Integer changedLineCountLimit = config?.changedLineCountLimit?.toInteger() ?: 300
    String prNumber = branchName.toString().replace("PR-", "") ?: env.CHANGE_ID?.toString()
    ArrayList listOfFilePathsToBeIgnored = config?.listOfFilePathsToBeIgnored ?: []
    ArrayList defaultFilePathsToBeIgnored = ["yarn.lock", "package-lock.json"] // default files to be ignored
    // combine user provided list of files or file paths to be ignored with default files or file paths to be ignored to create final listOfFilePathsToBeIgnored
    listOfFilePathsToBeIgnored.addAll(defaultFilePathsToBeIgnored) 
    // check to remove duplicate in cases where user might have added yarn.lock or package-lock.json in the Jenkinsfile as well in order to ignore those files explicitely
    listOfFilePathsToBeIgnored = listOfFilePathsToBeIgnored.unique() 
    

    // check if PR number is valid
    if (!prNumber?.trim()) {
        echo "prHygiene: we cannot check the PR because input PR number is NULL"
        return
    }

    // New GitOperations object
    withCredentials([usernamePassword(credentialsId: githubCredentialsId, usernameVariable: 'GITHUB_USERNAME', passwordVariable: 'GITHUB_TOKEN')]) {
        def githubOwnerAndRepo = gitOwner + "/" + gitRepo
        // Update GitHub API URL as per your GitHub setup
        def gitHubRestApiURL = "https://github.com/api/v3/repos/${githubOwnerAndRepo}/pulls/${prNumber}/files" 
        try {
            // call github rest API to get the PR details
            Integer returnStatus = sh(returnStatus: true, script: "#!/bin/sh -e\ncurl -s -H 'Authorization: token $GITHUB_TOKEN' -H 'Accept: application/vnd.github.v3+json' ${gitHubRestApiURL} > github_response.json")

            // check if github rest api call is successful
            if (returnStatus != 0) {
                echo "prHygiene: GitHub returned status=${returnStatus} from ${gitHubRestApiURL}"
                return
            }
            def prDetails = readJSON file: 'github_response.json'
            // check if prDetails is not null
            if (!prDetails) {
                echo "prHygiene: invalid PR Details JSON returned from ${gitHubRestApiURL}: 〖${prDetails}〗 — skipping"
                return
            }

            // get and compute pr attributes like changed files, changed lines of code etc
            int changedLineCount = 0
            int changedFileCount = 0
           
            // compute the changes files and changed line count
            // dynamic logic to ignore the file based on user inputs if provided or ignore generated yarn.lock or package-lock.json files by default 
            prDetails.each { files ->
                // adding logic to ignore files as per user inputs and default files to be ignored
                if(! (listOfFilePathsToBeIgnored.any { el -> files.filename.contains(el) })) {
                    changedLineCount = changedLineCount + 1 
                    changedFileCount++
                }
            }
               
            // status check for number of files changed
            checkAndUpdatePRStatus("Files Changed Check", githubCredentialsId, "Expected number of files changed to be <= ${changedFileCountLimit} but was ${changedFileCount}", targetUrl, changedFileCount, changedFileCountLimit)

            // status check for number of lines of code changed
            checkAndUpdatePRStatus("Lines Changed Check", githubCredentialsId, "Expected number of lines changed to be <= ${changedLineCountLimit} but was ${changedLineCount}; number of lines changed (meaning: added + deleted)", targetUrl, changedLineCount, changedLineCountLimit)
        } catch (err) {
            echo "prHygiene: exception while retrieving, parsing, or computing PR details from ${gitHubRestApiURL}: ${err}"
        }
    }
}


pipeline {
    agent any
    stages {
        stage('PR Hygiene Status Checks') {
            steps {
                script {
                    // define a config Map with defaults
                    // you can customize these values as per your requirement
                    config = [:]
                    config["changedFileCountLimit"] = 10
                    config["changedLineCountLimit"] = 300
                    config["disableStatusCheck"] = false
                    config["githubCredentialsId"] = "my-github-creds-on-jenkins"
                    prHygieneStatusChecks(config)
                }
            }
        }

    }
    post {
        always {
            println("success")
        }
    }
}
