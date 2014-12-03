@Grab(group='org.codehaus.groovy.modules.http-builder', module='http-builder', version='0.5.0-RC2' )
import groovyx.net.http.*
import static groovyx.net.http.ContentType.*
import static groovyx.net.http.Method.*

class Globals {
	static ConfigObject CONF
	static int TASK_NUM
	static int[] CREATED_TASKS
	static boolean SECURE
	static String HTTP_HEAD
	static RestApi API

	static void Init() {
		TASK_NUM = 1
		CREATED_TASKS = []
		CONF = new ConfigSlurper().parse(new File("api-test-config.groovy").toURL())
		SECURE = false
		HTTP_HEAD = 'http'
		API = new RestApi()
	}

	static void SetHttps(httpSecure) {
		SECURE = httpSecure
		HTTP_HEAD = SECURE ? 'https' : 'http'
	}

	static void addCreatedTask(id) {
		CREATED_TASKS.add(id)
	}

	static int GetTaskNum() {
		def taskNum = TASK_NUM
		TASK_NUM++
		return taskNum
	}

	static void PrintTask(task) {
		println "[${task.id}] ${task.name}"
	}

	static void PrintDefinition(obj) {
		println "[${obj.id}] ${obj.name}"
	}

	static String GetHostName() {
		return "${HTTP_HEAD}://${CONF.hostname}"
	}
}

enum LogLevel {
	None,
	Short,
	Verbose
}

abstract class Service {
	def created
	def name

	Service(name) {
		created = []
		this.name = name
	}

	abstract void create(item)
	abstract void delete(item)

	void cleanUp() {
		println "${name}: cleaning up..."
		created.each { delete(it) }
	}
}

class ProcessDefinitionService extends Service {
	ProcessDefinitionService(name) {
		super(name)
	}

	def list(query = [:]) {
		def definitions = []
		def http = new HTTPBuilder(Globals.GetHostName())
		http.auth.basic(Globals.CONF.username, Globals.CONF.password)
	    http.request(GET, JSON) {
	        uri.path = '/activiti-rest/service/repository/process-definitions'
	        uri.query = query
	
	  		headers.'User-Agent' = 'Mozilla/5.0 Ubuntu/8.10 Firefox/3.0.4'
	        response.success = { resp, json ->
	        	//if(Globals.logLevel != LogLevel.None)
	        		println "Process definitions (${json.data.size()}):"
	        	json.data.each {
	        		Globals.PrintDefinition(it) 
	        		definitions.add(new ProcessDefinition(it))
	        	}
	        }
	        response.failure = { resp ->
	        	println "[${resp.status} Failure!]"
	        }
	    }
	    return definitions
	}

	def getVariables(definition) {
		def api = Globals.API
		def tasks = api.task.list(["processDefinitionId":definition.id])
		println tasks
		tasks.each { 
			println api.task.getVariables(it).toString()
			println it.variables
		}
	}

	/**
	 * bpmn = business process model and notation
	 */
	void getBpm(item, query = [:]) {
		def definitions = []
		def http = new HTTPBuilder(Globals.GetHostName())
		http.auth.basic(Globals.CONF.username, Globals.CONF.password)
	    http.request(GET, JSON) {
	        uri.path = "/activiti-rest/service/repository/process-definitions/${item.id}/model"
	        uri.query = query
	
	  		headers.'User-Agent' = 'Mozilla/5.0 Ubuntu/8.10 Firefox/3.0.4'
	        response.success = { resp, json ->
	        	//println "${item.id} BPM:"
	        	//println json.each { println it }
	        }
	        response.failure = { resp ->
	        	println "[${resp.status}] Failure!"
	        }
	    }
	}

	void create(item) {

	}

	void delete(item) {

	}
}

class ProcessDefinition {
	def id
	def url
	def key
	def version
	String name
	String description
	def deploymentId
	def deploymentUrl
	def resource
	def diagramResource
	def category
	def graphicalNotationDefined
	def suspended
	def startFormDefined

	ProcessInstance toProcessInstance() {
		return [definition: this] as ProcessInstance
	}

	def toMap() {
		this.class.declaredFields.findAll { !it.synthetic }.collectEntries {
      		[ (it.name):this."$it.name" ]
    	}
	}
}

class ProcessInstance {
	def id
	String businessKey
	def variables
	ProcessDefinition definition
}

class ProcessInstanceService extends Service {
	ProcessInstanceService(name) {
		super(name)
	}

	void printOut(processInstance) {
		println "[${processInstance.id}] ${processInstance.definition.name}"
	}

	void list(query = [:]) {
		def http = new HTTPBuilder(Globals.GetHostName())
		http.auth.basic(Globals.CONF.username, Globals.CONF.password)
	    http.request(GET, JSON) {
	        uri.path = '/activiti-rest/service/runtime/process-instances'
	        uri.query = query
	
	  		headers.'User-Agent' = 'Mozilla/5.0 Ubuntu/8.10 Firefox/3.0.4'
	        response.success = { resp, json ->
	        	println "Process Instances (${json.data.size()}):"
	        	json.data.each { println "[${it.id}] ${it.activityId}" }
	        }
	        response.failure = { resp ->
	        	println "[${resp.status}] Failure!"
	        }
	    }
	}

	void createFromList(definitions) {
		definitions.each { create(it.toProcessInstance()) }
	}

	/**
	 * To properly create a process instance the variables for the process 
	 * definition must be set, otherwise the instance will not be created
	 * and the http request will return an error message.
	 */
	void create(item) {
		def http = new HTTPBuilder(Globals.GetHostName())
		http.auth.basic(Globals.CONF.username, Globals.CONF.password)
	    http.request(POST, JSON) {
	        uri.path = '/activiti-rest/service/runtime/process-instances' 
	        body = [
	        	"processDefinitionId": item.definition.id,
   				"businessKey": item.businessKey,
   				"variables": item.variables
	  		]
	
	  		headers.'User-Agent' = 'Mozilla/5.0 Ubuntu/8.10 Firefox/3.0.4'
	        response.success = { resp, json ->
	        	item.id = json.id
	        	if (resp.status == 201) {
	        		println "Created instance: [${item.id}] ${item.definition.name}"
	        	}
	        	else {
	        		println "[${resp.status}] Instance was not created."
	        	}
	        	created.add(item)
	        }

	        response.failure = { resp, reader ->
	        	println "[${resp.status}] Http failure: ${reader.errorMessage}"
	        }
	    }
	}

	void delete(item) {
		def http = new HTTPBuilder(Globals.GetHostName())
		http.auth.basic(Globals.CONF.username, Globals.CONF.password)
	    http.request(DELETE, JSON) {
	        uri.path = "/activiti-rest/service/runtime/process-instances/${item.id}"
	        uri.query = [:]
	
	  		headers.'User-Agent' = 'Mozilla/5.0 Ubuntu/8.10 Firefox/3.0.4'
	        response.success = { resp, json ->
	        	if (resp.status == 204) {
	        		println "Process instance successfully deleted"
	        	}
	        	else {
	        		println "[${resp.status}] Process instance could not be deleted."
	        	}
	        }
	        response.failure = { resp, reader ->
	        	println "[${resp.status}] failure: ${reader.errorMessage}"
	        }
	    }
	}
}

class Task {
	def id
	def cascadeHistory = true
	def deleteReason
	def name
	def description
	def dueDate = new Date()
	def url
	def owner = Globals.CONF.username.toString()
	def assignee = Globals.CONF.username.toString()
	def delegationState = "pending"
	def createTime
	def priority = 20
	def suspended
	def taskDefinitionKey
	def tenantId
	def category
	def formKey
	def parentTaskId
	def parentTaskUrl
	def executionId
	def executionUrl
	def processInstanceId
	def processInstanceUrl
	def processDefinitionId
	def processDefinitionUrl
	def variables

	def toMap() {
		this.class.declaredFields.findAll { !it.synthetic }.collectEntries {
      		[ (it.name):this."$it.name" ]
    	}
	}

	def toCreateMap() {
		return [
	        "assignee": assignee,
	  		"delegationState": delegationState,
	  		"description": description,
	  		"dueDate": dueDate,
	  		"name": name,
	  		"owner": owner,
	  		"priority": priority
	    ]
	}
}

class TaskService extends Service {
	TaskService(name) {
		super(name)
	}

	// Gets a list of all tasks
	void list(query = [:]) {
		println query
		def http = new HTTPBuilder(Globals.GetHostName())
		http.auth.basic(Globals.CONF.username, Globals.CONF.password)
	    http.request(POST, JSON) {
	        uri.path = '/activiti-rest/service/query/tasks'
	        body = query
	  		headers.'User-Agent' = 'Mozilla/5.0 Ubuntu/8.10 Firefox/3.0.4'
	        response.success = { resp, json ->
	        	println "Tasks (${json.data.size()}):"
	        	def tasks = []
	        	json.data.each { 
	        		Globals.PrintTask(it) 
	        		tasks.add(new Task(it))
	        	}
	        	return tasks
	        }
	        response.failure = { resp, reader ->
	        	println "[${resp.status}] failure: ${reader.errorMessage}"
	        }
	    }
	}
	
	void create(name, description, dueDate) {
		create([name:name.toString(), description:description.toString(), dueDate:dueDate.format("yyyy-MM-dd'T'HH:mm:ss.SSSZ")] as Task)
	}

	// item is a Task
	void create(item) {
		def http = new HTTPBuilder(Globals.GetHostName())
		http.auth.basic(Globals.CONF.username, Globals.CONF.password)
	    http.request(POST, JSON) {
	        uri.path = '/activiti-rest/service/runtime/tasks' 
	        body = item.toCreateMap()
	
	  		headers.'User-Agent' = 'Mozilla/5.0 Ubuntu/8.10 Firefox/3.0.4'
	        response.success = { resp, json ->
	        	if (resp.status == 201) {
	        		println "Created task: [${json.id}] ${json.name}"
	        	}
	        	else {
	        		println "[${resp.status}] Task was not created."
	        	}
	        	created.add(new Task(id:json.id))
	        }
	        response.failure = { resp ->
	        	println "[${resp.status}] Http failure!"
	        }
	    }
	}

	// Item is a Task
	void delete(item) {
		def http = new HTTPBuilder(Globals.GetHostName())
		http.auth.basic(Globals.CONF.username, Globals.CONF.password)
	    http.request(DELETE, JSON) {
	        uri.path = "/activiti-rest/service/runtime/tasks/${item.id}"
	        uri.query = [
	        	"cascadeHistory": item.cascadeHistory,
	        	"reason": item.deleteReason
	        ]
	
	  		headers.'User-Agent' = 'Mozilla/5.0 Ubuntu/8.10 Firefox/3.0.4'
	        response.success = { resp, json ->
	        	if (resp.status == 204) {
	        		println "Task ${item.id} successfully deleted"
	        	}
	        	else {
	        		println "[${resp.status}] Task could not be deleted."
	        	}
	        }
	        response.failure = { resp, reader ->
	        	println "[${resp.status}] failure: ${reader.errorMessage}"
	        }
	    }
	}

	void update(item, query) {
		println "--------------------------------------"
		println "Updating task: ${item.id}..."
		def http = new HTTPBuilder(Globals.GetHostName())
		http.auth.basic(Globals.CONF.username, Globals.CONF.password)
	    http.request(PUT, JSON) {
	        uri.path = "/activiti-rest/service/runtime/tasks/${item.id}"
	        body = query
	
	  		headers.'User-Agent' = 'Mozilla/5.0 Ubuntu/8.10 Firefox/3.0.4'
	        response.success = { resp, json ->
	        	println "Task successfully updated"
	        }
	        response.failure = { resp, reader ->
	        	println "[${resp.status}] failure: ${reader.errorMessage}"
	        }
	    }
	}
}

class RestApi {
	TaskService task
	def process

	RestApi() {
		task = new TaskService("TaskService")
		process = [
			instance: new ProcessInstanceService("ProcessInstanceService"),
			definition: new ProcessDefinitionService("ProcessDefinitionService")
		]
	}
	
	void getUsers() {
		println "--------------------------------------"
		println "Getting users..."
	    def http = new HTTPBuilder(Globals.GetHostName())
	    http.auth.basic(Globals.CONF.username, Globals.CONF.password)
	    http.request(GET, JSON) {
	        uri.path = '/activiti-rest/service/identity/users'
	        uri.query = [:]
	
	  		headers.'User-Agent' = 'Mozilla/5.0 Ubuntu/8.10 Firefox/3.0.4'
	        response.success = { resp, json ->
	        	json.data.each { println "[${it.id}] ${it.firstName} ${it.lastName}" }
	        }
	        response.failure = { resp ->
	        	println 'failure!'
	        	println "response status: ${resp.statusLine}"
	        }
	    }
	}
	
	void cleanUp() {
		println "Cleaing up..."
		task.cleanUp()
		process.definition.cleanUp()
		process.instance.cleanUp()
	}
}


def main() {
	Globals.Init()
    RestApi api = Globals.API
    api.task.list()
    api.task.create("Test Task ${Globals.GetTaskNum()}", 'Test task description.', new Date().plus(7))
    api.task.create("Test Task ${Globals.GetTaskNum()}", 'Test task description.', new Date().plus(14))
    api.task.create("Test Task ${Globals.GetTaskNum()}", 'Test task description.', new Date().plus(21))
    api.task.update([id:api.task.created[0].id], ["assignee":Globals.CONF.username2.toString()])
    api.task.list(["assignee":Globals.CONF.username.toString()])
    api.task.list(["assignee":Globals.CONF.username2.toString()])
    def definitions = api.process.definition.list()
    api.process.instance.createFromList(definitions)
    api.process.instance.list()
    api.cleanUp()
}

main()

System.exit(0)