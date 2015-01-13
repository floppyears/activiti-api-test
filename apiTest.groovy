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
	static HTTPBuilder HTTP
	static RESTClient RESTAPI

	static void Init() {
		TASK_NUM = 1
		CREATED_TASKS = []
		CONF = new ConfigSlurper().parse(new File("api-test-config.groovy").toURL())
		SECURE = false
		HTTP_HEAD = 'http'
		API = new RestApi()
		HTTP = new HTTPBuilder(GetHostName() + '/activiti-rest/service/')
		RESTAPI = new RESTClient(GetHostName() + '/activiti-rest/service/')
		RESTAPI.auth.basic(CONF.username, CONF.password)
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

enum MapType {
	Create,
	Delete,
	Update
}

abstract class Service {
	def area 
	def serviceId
	def created
	def name
	def historic = false

	Service(name, area, serviceId) {
		this.created = []
		this.name = name
		this.area = area
		this.serviceId = serviceId
	}

	def getPrefix() {
		historic ? "history/historic-$serviceId" : "$area/$serviceId"
	}

	def list(query = [:]) {
		def resp = Globals.RESTAPI.get(path: getPrefix(), query: query, requestContentType: JSON)
	    return resp.data.data
	}

	def get(id) {
		def resp = Globals.RESTAPI.get(path: "${getPrefix()}/$id", requestContentType: JSON)
		return resp.data
	}

	def create(item) {
	    def resp = Globals.RESTAPI.post(path: getPrefix(), body: item.toMap(MapType.Create), requestContentType: JSON)
		updateItem(item, resp.data)
	    created.add(item)
	    return resp.data
	}

	def update(item) {
		def resp = Globals.RESTAPI.put(path: "${getPrefix()}/${item.id}", body: item.toMap(MapType.Update), requestContentType: JSON)
		updateItem(item, resp.data)
	    return resp.data
	}

	def updateItem(item, jsonData) {
		def itemToUpdate = historic ? item.history : item
		itemToUpdate.class.declaredFields.findAll { !it.synthetic && jsonData.containsKey("$it.name") }.each {
			item."$it.name" = jsonData."$it.name"
		}
		itemToUpdate.id = jsonData.id
	}

	def refresh(item) {
		updateItem(item, get(item.id))
	}

	def delete(item) {
		def resp = Globals.RESTAPI.delete(path: "${getPrefix()}/$item.id", requestContentType: JSON)
		resp.status == 204
	}

	void cleanUp() {
		println "${name}: cleaning up..."
		created.each { delete(it) }
	}
}

class ProcessDefinitionService extends Service {
	ProcessDefinitionService(name, area, serviceId) {
		super(name, area, serviceId)
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
	def getBpmn(item) {
		def resp = Globals.RESTAPI.get(path: "${getPrefix()}/$item.id/model", requestContentType: JSON)
		return resp.data
	}

	def delete(item) {

	}
}

class ProcessDefinition {
	def id
	def url
	def key
	def version
	def name
	def description
	def deploymentId
	def deploymentUrl
	def resource
	def diagramResource
	def category
	def graphicalNotationDefined
	def suspended
	def startFormDefined

	ProcessInstance toProcessInstance() {
		[ definition: this ] as ProcessInstance
	}

	def toMap() {
		this.class.declaredFields.findAll { !it.synthetic }.collectEntries {
      		[ (it.name):this."$it.name" ]
    	}
	}
}

class HistoricProcessInstance {
	def id
    def businessKey
    def processDefinitionId
    def processDefinitionUrl
    def startTime
    def endTime
    def durationInMillis
    def startUserId
    def startActivityId
    def endActivityId
    def deleteReason
    def superProcessInstanceId
    def url
    def variables
    def tenantId
}

class ProcessInstance {
	def id
	def variables
	def url
	def businessKey
	def suspended
	def ended
	def processDefinitionId
	def processDefinitionUrl
	def activityId
	def tenantId
	def completed
	ProcessDefinition definition
	HistoricProcessInstance history = new HistoricProcessInstance()

	def toMap() {
		this.class.declaredFields.findAll { !it.synthetic }.collectEntries {
      		[ (it.name):this."$it.name" ]
    	}
	}

	def toMap(mapType) {
		def asMap
		switch(mapType) {
		case MapType.Create:
		case MapType.Update:
		    asMap = [
		    	processDefinitionId: processDefinitionId,
		    	businessKey: businessKey,
		    	variables: variables
	    	]	
			break
		default:
			asMap = this.toMap()
		}
		return asMap
	}
}

class ProcessInstanceService extends Service {
	ProcessInstanceService(name, area, serviceId) {
		super(name, area, serviceId)
	}

	void printOut(processInstance) {
		println "[${processInstance.id}] ${processInstance.definition.name}"
	}

	def createFromList(definitions) {
		definitions.each {
			def instance = it.toProcessInstance()
			instance.variables = getStartVariables(it)
			create(instance)
		}
	}

	def getStartVariables(definition) {
		def variables = []
		def startVars = Globals.API.form.get([processDefinitionId: definition.id]).formProperties
		if(startVars.size() > 0) println "Please enter set start variables for $definition.id:"
		startVars.each {
			def line
			if(it.type == "enum") {
				def enumIds = it.enumValues.id.join(", ")
				line = System.console().readLine("> [$it.type] [$enumIds] $it.name: ")
			}
			else {
				line = System.console().readLine("> [$it.type] $it.name: ")
			}
			variables.add([name: it.id, value: line])
		}
		return variables
	}

	/**
	 * To properly create a process instance the variables for the process 
	 * definition must be set, otherwise the instance will not be created
	 * and the http request will return an error message.
	 */
	def create(item) {
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
	        	println "[${resp.status}] http [$item.definition.id]: ${reader.errorMessage}"
	        }
	    }
	}

	def delete(item) {
		def http = new HTTPBuilder(Globals.GetHostName())
		http.auth.basic(Globals.CONF.username, Globals.CONF.password)
	    http.request(DELETE, JSON) {
	        uri.path = "/activiti-rest/service/runtime/process-instances/${item.id}"
	        uri.query = [:]
	
	  		headers.'User-Agent' = 'Mozilla/5.0 Ubuntu/8.10 Firefox/3.0.4'
	        response.success = { resp, json ->
	        	if (resp.status != 204) {
	        		println "[${resp.status}] Process instance could not be deleted."
	        	}
	        }
	        response.failure = { resp, reader ->

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

	def update(jsonData) {
		this.class.declaredFields.findAll { !it.synthetic }.each {
			it = jsonData[it.name]
		}
	}

	def toMap() {
		this.class.declaredFields.findAll { !it.synthetic }.collectEntries {
      		[ (it.name):this."$it.name" ]
    	}
	}

	def toMap(mapType) {
		def asMap
		switch(mapType) {
		case MapType.Create:
		case MapType.Update:
		    asMap = [
	        	assignee: assignee,
	  			delegationState: delegationState,
	  			description: description,
	  			dueDate: dueDate,
	  			name: name,
	  			owner: owner,
	  			parentTaskId: parentTaskId,
	  			priority: priority
	    	]	
			break
		default:
			asMap = this.toMap()
		}
		return asMap
	}
}

class TaskService extends Service {
	TaskService(name, area, serviceId) {
		super(name, area, serviceId)
	}

	def create(name, description, dueDate) {
		create([name:name.toString(), description:description.toString(), dueDate:dueDate.format("yyyy-MM-dd'T'HH:mm:ss.SSSZ")] as Task)
	}

	def list(query = [:]) {
		def resp = Globals.RESTAPI.post(path: "query/${serviceId}", body: query, requestContentType: JSON)
	    return resp.data.data
	}


	// Item is a Task
	// Tried to use Service's delete method, but got an authentication error.
	def delete(item) {
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
	        	return resp
	        }
	        response.failure = { resp, reader ->
	        	//println "[${resp.status}] failure: ${reader.errorMessage}"
	        	return resp.status
	        }
	    }
	}

	def takeAction(item, query) {
		def resp = Globals.RESTAPI.post(path: "${getPrefix()}/${item.id}", body: query, requestContentType: JSON)
		return resp.data
	}
}

class FormService extends Service {
	FormService(name, area, serviceId) {
		super(name, area, serviceId)
	}

	def get(query) {
		def resp = Globals.RESTAPI.get(path: getPrefix(), query: query, requestContentType: JSON)
		return resp.data
	}

	def delete(item) {

	}
}

class User {
	def id
	def firstName
	def lastName
	def url
	def email

	def toMap() {
		this.class.declaredFields.findAll { !it.synthetic }.collectEntries {
      		[ (it.name):this."$it.name" ]
    	}
	}

	def toMap(mapType) {
		def asMap
		switch(mapType) {
		case MapType.Create:
		case MapType.Update:
		    asMap = [
		    	firstName: firstName,
		    	lastName: lastName,
		    	url: url,
		    	email: email
	    	]	
			break
		default:
			asMap = this.toMap()
		}
		return asMap
	}
}

class UserService extends Service {
	UserService(name, area, serviceId) {
		super(name, area, serviceId)
	}

	def delete(item) {

	}
}

class RestApi {
	TaskService task
	FormService form
	UserService user
	def process

	RestApi() {
		task = new TaskService("TaskService", "runtime", "tasks")
		form = new FormService("FormService", "form", "form-data")
		user = new UserService("UserService", "identity", "users")
		process = [
			instance: new ProcessInstanceService("ProcessInstanceService", "runtime", "process-instances"),
			definition: new ProcessDefinitionService("ProcessDefinitionService", "repository", "process-definitions")
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

    // Set the email of the person who will request vacation time to the test email.
    // Given the email task works, they should get an email if the request is approved.
    User kermit = api.user.get(Globals.CONF.username)
    kermit.email = Globals.CONF.testEmail
    api.user.update(kermit)

    // Create a few tasks, update assignee by updating the task (not by claiming)
    api.task.create("Test Task ${Globals.GetTaskNum()}", 'Test task description.', new Date().plus(7))
    api.task.create("Test Task ${Globals.GetTaskNum()}", 'Test task description.', new Date().plus(14))
    api.task.create("Test Task ${Globals.GetTaskNum()}", 'Test task description.', new Date().plus(21))
    def taskUp = api.task.created[0]
    taskUp.assignee = Globals.CONF.username2
    api.task.update(taskUp)

    // Get all deployed process definitions
    def definitions = api.process.definition.list().collect { new ProcessDefinition(it) }
    // Create a process instance for each definition
    api.process.instance.createFromList(definitions)

    // Get the handle vacation request task
    def handleVacReq = api.task.list([name:"Handle vacation request"])[0]
    // In case there are two or more vacation request tasks, get the correct process instance
    def vacationReq = new ProcessInstance(api.process.instance.get(handleVacReq.processInstanceId))
    api.task.takeAction(handleVacReq, [action:"claim", assignee:Globals.CONF.username])

    // vars = variables required to be filled to create an instance
    def vars = api.form.get([taskId:handleVacReq.id]).formProperties
    // variables = the user input for the vars
    def variables = []
    // For each required var in vars, ask the user for input and add it to variables
    vars.each {
		def line
		if(it.type == "enum") {
			def enumIds = it.enumValues.id.join(", ")
			line = System.console().readLine("> [$it.type] [$enumIds] $it.name: ")
		}
		else {
			line = System.console().readLine("> [$it.type] $it.name: ")
		}
		variables.add([name: it.id, value: line])
	}
	// Go through the Vacation Request process, complete the handle vacation request task
	api.task.takeAction(handleVacReq, [action:"complete", variables:variables])
	// Set the processInstanceService to query the historic datatable.
	// When a process instance is completed, it is removed from the normal datatable and
	// added to the historic datatable.
	api.process.instance.historic = true
	vacationReq.history = new HistoricProcessInstance(api.process.instance.get(vacationReq.id))
	// If found in the historic datatable it should be completed, but I checked the end date
	// as an extra form of validation.
	assert vacationReq.history.endTime != null

	// Set the processInstanceService back to querying the normal (non-historic) data.
	api.process.instance.historic = false
    
    def instances = api.process.instance.list().collect { new ProcessInstance(it) }

    // Go through the process of claiming, and completing another process
    def hardTask = new Task(api.task.list([name: "Investigate hardware"])[0])
    def softTask = new Task(api.task.list([name: "Investigate software"])[0])
    api.task.takeAction(hardTask, [action:"claim", assignee:Globals.CONF.username2])
    api.task.takeAction(softTask, [action:"claim", assignee:Globals.CONF.username2])
    api.task.takeAction(hardTask, [action:"complete"])
    api.task.takeAction(softTask, [action:"complete"])
    api.task.list([name: "Write report"]).each { api.task.takeAction(new Task(it), [action:"complete"]) }

    // Tries to delete anything that was created. Basically trying to keep the database clean.
    api.cleanUp()
}

main()

System.exit(0)


