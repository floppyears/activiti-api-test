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

	static void Init() {
		TASK_NUM = 1
		CREATED_TASKS = []
		CONF = new ConfigSlurper().parse(new File("api-test-config.groovy").toURL())
		SECURE = false
		HTTP_HEAD = 'http'
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

	static String GetHostName() {
		return "${HTTP_HEAD}://${CONF.hostname}"
	}
}


abstract class Service {
	def created

	Service() {
		created = []
	}

	abstract void create(item)
	abstract void delete(item)

	void cleanUp() {
		created.each { delete(it) }
	}
}

class Task {
	def id
	boolean cascadeHistory = true
	String deleteReason
	String name
	String description
	Date dueDate = new Date()
}

class TaskService extends Service {
	TaskService() {
		super()
	}

	// Gets a list of all tasks
	void list() {
		println "--------------------------------------"
		println "Listing tasks..."
		def http = new HTTPBuilder(Globals.GetHostName())
		http.auth.basic(Globals.CONF.username, Globals.CONF.password)
	    http.request(GET, JSON) {
	        uri.path = '/activiti-rest/service/runtime/tasks'
	        uri.query = [:]
	
	  		headers.'User-Agent' = 'Mozilla/5.0 Ubuntu/8.10 Firefox/3.0.4'
	        response.success = { resp, json ->
	        	println "Tasks (${json.data.size()}):"
	        	json.data.each { Globals.PrintTask(it) }
	        }
	        response.failure = { resp ->
	        	println "[${resp.status} Failure!]"
	        }
	    }
	}

	void create(name, description, dueDate) {
		create([name:name, description:description, dueDate:dueDate] as Task)
	}

	// item is a Task
	void create(item) {
		println "--------------------------------------"
		println "Creating task: \"${item.name}\"..."
		def http = new HTTPBuilder(Globals.GetHostName())
		http.auth.basic(Globals.CONF.username, Globals.CONF.password)
	    http.request(POST, JSON) {
	        uri.path = '/activiti-rest/service/runtime/tasks' 
	        body = [
	        	"assignee": Globals.CONF.username.toString(),
	  			"delegationState": "pending",
	  			"description": item.description.toString(),
	  			"dueDate": item.dueDate.format("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
	  			"name": item.name.toString(),
	  			"owner": Globals.CONF.username.toString(),
	  			"priority": 20
	  		]
	
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
		println item
		println "--------------------------------------"
		println "Deleting task: ${item.id}..."
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
	        		println "Task successfully deleted"
	        	}
	        	else {
	        		println "[${resp.status}] Task could not be deleted."
	        	}
	        }
	        response.failure = { resp ->
	        	println "[${resp.status}] Http failure!"
	        }
	    }
	}

	void update(item) {

	}
}

class RestApi {
	TaskService task

	RestApi() {
		task = new TaskService()
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

	void getUsersTasks(userId) {
		println "--------------------------------------"
		println "Getting ${userId}'s tasks..."
		def http = new HTTPBuilder(Globals.GetHostName())
		http.auth.basic(Globals.CONF.username, Globals.CONF.password)
	    http.request(GET, JSON) {
	        uri.path = '/activiti-rest/service/runtime/tasks'
	        uri.query = [assignee:userId]
	
	  		headers.'User-Agent' = 'Mozilla/5.0 Ubuntu/8.10 Firefox/3.0.4'
	        response.success = { resp, json ->
	        	println "Tasks (${json.data.size()}):"
	        	json.data.each { Globals.PrintTask(it) }
	        }
	        response.failure = { resp ->
	        	println 'Failure!'
	        	println "Response status: ${resp.statusLine}"
	        }
	    }
	}
	
	void cleanUp() {
		println "Cleaing up..."
		getTasks()
		task.cleanUp()
		getTasks()
	}
}


def main() {
	Globals.Init()
    RestApi api = new RestApi()
    api.getUsers()
    api.getUsersTasks(Globals.CONF.username)
    api.task.list()
    api.task.create("Test Task ${Globals.GetTaskNum()}", 'Test task description.', new Date().plus(7))
    api.task.create("Test Task ${Globals.GetTaskNum()}", 'Test task description.', new Date().plus(14))
    api.task.create("Test Task ${Globals.GetTaskNum()}", 'Test task description.', new Date().plus(21))
    api.getUsersTasks(Globals.CONF.username)
    api.cleanUp()
}

main()

System.exit(0)