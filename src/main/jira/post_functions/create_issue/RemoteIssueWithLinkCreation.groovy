package jira.post_functions.create_issue

import com.atlassian.applinks.api.ApplicationLink
import com.atlassian.jira.applinks.JiraApplicationLinkService
import com.atlassian.jira.bc.issue.link.RemoteIssueLinkService
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.link.RemoteIssueLinkBuilder
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import groovy.json.JsonSlurper
import groovy.transform.Field
import org.apache.http.HttpEntity
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.ContentType
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.log4j.Level
import org.apache.log4j.Logger

/**
 * App link is configured without OAuth without impersonation
 */

@Field final String SOURCE_APPLICATION_LINK_NAME = "Test Jira"
@Field final String SOURCE_EXECUTION_USERNAME = "service_user"
@Field final String TARGET_API_URL = "https://jira.example.com/rest/api/latest"
@Field final String TARGET_BASIC_AUTH = "Basic "
@Field final String TARGET_PROJECT_KEY = "TEST"
@Field final String TARGET_ISSUE_TYPE = "Task"
@Field final String TARGET_ISSUE_LABEL = "Label"

def postRequest = { String url, HttpEntity entity ->
    def httpClient = HttpClientBuilder.create().build()
    def httpPost = new HttpPost(url)
    httpPost.with {
        setHeader("Authorization", TARGET_BASIC_AUTH)
        setHeader("X-Atlassian-Token", "nocheck")
        setEntity(entity)
    }
    return httpClient.execute(httpPost)
}

def scriptName = this.class.name
def logger = Logger.getLogger(scriptName)
logger.setLevel(Level.INFO)
logger.info "PF START"

logger.info "STARTING REMOTE ISSUE CREATION"
def jsonRemoteIssueData = JsonNodeFactory.instance.objectNode()
def fields = jsonRemoteIssueData.putObject("fields")
fields.putObject("project").put("key", TARGET_PROJECT_KEY)
fields.putObject("issuetype").put("name", TARGET_ISSUE_TYPE)
fields.putObject("customfield_14601").put("value", "Анализ кода на уязвимости")
fields.putObject("customfield_11015").put("value", "Требование в ЦК по сопровождению Agile-команд")
fields.putArray("labels").add("${TARGET_ISSUE_LABEL}")
fields.put("summary", "${issue.summary}")
fields.put("description", "${issue.description}")

def issueEntity = new StringEntity(jsonRemoteIssueData.toString(), ContentType.APPLICATION_JSON)
def createIssue = postRequest("${TARGET_API_URL}/issue", issueEntity)
if (createIssue.statusLine.statusCode != 201) {
    logger.info "ERROR DURING REMOTE ISSUE CREATION - PF STOPPED"
    logger.info createIssue.entity.content.text
    return
}
def jsonResponse = new JsonSlurper().parseText(createIssue.entity.content.text) as Map
def createdIssueKey = jsonResponse["key"]
def createdIssueId = jsonResponse["id"]
logger.info "REMOTE ISSUE CREATED: ${createdIssueKey} - ${createdIssueId}"

logger.info "STARTING SOURCE REMOTE LINK CREATION"
def sourceExecutionUser = ComponentAccessor.userManager.getUserByName(SOURCE_EXECUTION_USERNAME)
def jiraApplicationLinkService = ComponentAccessor.getComponent(JiraApplicationLinkService)
def remoteIssueLinkService = ComponentAccessor.getComponent(RemoteIssueLinkService)
def applicationLink = jiraApplicationLinkService.applicationLinks.find { ApplicationLink applicationLink ->
    applicationLink.name == SOURCE_APPLICATION_LINK_NAME
} as ApplicationLink
def globalId = "appId=${applicationLink.id}&issueId=${createdIssueId}"
def issueRemoteLink = new RemoteIssueLinkBuilder().globalId(globalId)
issueRemoteLink.with {
    issueId(issue.id)
    applicationType("com.atlassian.jira")
    applicationName(applicationLink.name)
    relationship("relates to")
    url("${applicationLink.displayUrl}/browse/${createdIssueKey}")
    summary("${issue.summary}")
    title("${createdIssueKey}")
}
def validationResult = remoteIssueLinkService.validateCreate(sourceExecutionUser, issueRemoteLink.build())
if (!validationResult.valid) {
    logger.info "SOURCE REMOTE LINK VALIDATION RESULT IS NOT VALID - PF STOPPED"
    logger.info validationResult.errorCollection
    return
}
def link = remoteIssueLinkService.create(sourceExecutionUser, validationResult)
logger.info "SOURCE REMOTE LINK CREATED: ${link.remoteIssueLink.properties}"

logger.info "STARTING TARGET REMOTE LINK CREATION"
def sourceBaseUrl = ComponentAccessor.applicationProperties.getString("jira.baseurl")
def sourceJiraTitle = ComponentAccessor.applicationProperties.getString("jira.title")
def jsonRemoteLink = """
{
    "globalId": "appId=${applicationLink.id}&issueId=${issue.id}",
    "application": {                                            
        "type":"com.atlassian.jira",                      
        "name":"${sourceJiraTitle}"
    },
    "relationship":"relates to",                           
    "object": {                                            
        "url":"${sourceBaseUrl}/${issue.key}",
        "title":"${issue.key}",                             
        "summary":"${issue.summary}"
    }
}
"""
def createRemoteLink = postRequest(
        "${TARGET_API_URL}/issue/${createdIssueKey}/remotelink",
        new StringEntity(jsonRemoteLink, ContentType.APPLICATION_JSON)
)
if (createRemoteLink.statusLine.statusCode != 201) {
    logger.info "ERROR DURING TARGET REMOTE LINK CREATION (${createRemoteLink.statusLine.statusCode}) - PF STOPPED"
    logger.info createRemoteLink.entity.content.text
    return
}
logger.info "TARGET REMOTE LINK CREATED: ${createRemoteLink.entity.content.text}"
logger.info "PF END"