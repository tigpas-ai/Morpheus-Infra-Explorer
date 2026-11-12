package eu.liehne.morpheus.infraexplorer.it

import eu.liehne.morpheus.infraexplorer.FolderSettings
import groovy.json.JsonOutput
import org.opentest4j.TestAbortedException
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Stepwise

/**
 * Integration test on a real Morpheus appliance: uploads the built plugin, checks it via UI session and API and
 * restores the previous state afterwards (previous version with its settings and enabled state, or no plugin).
 * Nothing is changed if the previous state could not be restored. See docs/integration-test.md.
 */
@Stepwise
class InfraExplorerIntegrationSpec extends Specification {

	static final String CODE = TestEnvironment.PLUGIN_CODE
	static final String BASE = '/plugin/infra-explorer'
	static final String PATH_MONITOR_CODE = 'storage-path-monitor'
	/** Clusters and hosts checked in detail (keeps the run short on large appliances). */
	static final int MAX_CHECKED = 5

	@Shared TestEnvironment env
	@Shared MorpheusApi api
	@Shared PluginOperations ops
	@Shared PluginJar jar
	@Shared PluginBaseline baseline
	@Shared UiSession admin
	@Shared TestUser testUser
	/** Something in the appliance was changed and has to be restored. */
	@Shared boolean changed
	/** The jar under test was (possibly) uploaded. */
	@Shared boolean uploaded
	@Shared Map installed
	@Shared List<Map> clusters = []
	@Shared Map<Object, Set> datastoreIds = [:]
	@Shared Map<Object, Set> hostIds = [:]

	def setupSpec() {
		env = TestEnvironment.fromSystem()
		List<String> problems = env.problems()
		if (problems) {
			throw new PreconditionException('integration test not started:\n  ' + problems.join('\n  '))
		}
		jar = new PluginJar(env.jar)
		if (jar.code != CODE || !jar.version) {
			throw new PreconditionException("unexpected jar under test: $jar")
		}
		api = new MorpheusApi(env)
		ops = new ApiPluginOperations(api)
		// token valid and allowed to read what the test changes later (fails here instead of after the upload)
		['/api/whoami', '/api/plugins?max=1', '/api/roles?max=1', '/api/users?max=1'].each { api.get(it) }
		admin = new UiSession(env).login(env.user, env.password)
		baseline = PluginBaseline.capture(ops, CODE) { String version -> RestoreJarResolver.resolve(env, version) }
		testUser = new TestUser(api, env)
		log "appliance ${env.url}, jar under test $jar"
		log "state before the test: $baseline" + (baseline.restoreJar ? ", restore jar ${baseline.restoreJar.file}" : '')
	}

	def cleanupSpec() {
		List<String> errors = []
		if (changed && testUser) {
			try {
				List<String> removed = testUser.remove()
				if (removed) {
					log "removed test objects: ${removed.join(', ')}"
				}
			} catch (Exception e) {
				errors << "test objects ${testUser.username}/${testUser.roleName} not removed: ${e.message}"
			}
		}
		if (changed && baseline) {
			try {
				List<String> steps = baseline.restore(ops, uploaded, env.timeoutSeconds)
				log "restored: ${steps ? steps.join(', ') : 'nothing to do'} – now $baseline"
			} catch (Exception e) {
				errors << ("RESTORE FAILED: ${e.message}\n  expected: $baseline\n  " +
					"emergency: disable the plugin with PUT /api/plugins/<id> {\"plugin\":{\"enabled\":false}} " +
					'or install the previous jar manually (docs/integration-test.md)')
			}
		}
		if (errors) {
			throw new IllegalStateException(errors.join('\n'))
		}
	}

	def "uploads the plugin under test, which is loaded and keeps the previous settings"() {
		when:
		changed = true
		uploaded = true
		installed = ops.install(jar, env.timeoutSeconds)
		if (installed.enabled == false) {
			ops.update(installed.id, [enabled: true])
			installed = ops.find(CODE)
		}

		then:
		installed.status == 'loaded'
		installed.version == jar.version
		installed.enabled != false
		!baseline.installed || installed.id == baseline.id
		!baseline.installed || PluginBaseline.normalize(installed.config) == baseline.config
	}

	def "pages of signed-in users include the overlay of the new version and its assets"() {
		when:
		HttpResult page = admin.get('/dashboard')
		List<String> assets = (page.body =~ /\/assets\/plugin\/infra-explorer\/[^"']+/).collect { it as String }.unique()

		then:
		page.status == 200
		page.body.contains('id="ie-root"')
		page.body.contains("data-ie-version=\"${jar.version}\"")
		assets.collect { it.replaceAll(/\?.*/, '').tokenize('/').last() }.toSet() ==
			['infra-explorer.js', 'infra-explorer-messages.js', 'infra-explorer.css'] as Set
		assets.every { admin.get(it).status == 200 }

		and: 'not on the login page'
		!new UiSession(env).get('/login/auth').body.contains('id="ie-root"')
	}

	def "clusters lists the clusters known to the API"() {
		when:
		HttpResult result = admin.route("$BASE/clusters")
		clusters = result.json().clusters as List<Map>
		Map<Object, Map> apiClusters = (api.get('/api/clusters?max=1000').clusters as List<Map>).collectEntries { [it.id, it] }
		log "clusters: ${clusters.collect { "${it.name} (${it.id})" }}"

		then:
		result.status == 200
		clusters.every { apiClusters[it.id]?.name == it.name }
		clusters.every { it.url == "/infrastructure/clusters/${it.id}" }
		clusters*.name == clusters*.name.sort(false, String.CASE_INSENSITIVE_ORDER)
	}

	def "hosts, VMs, datastores and networks of the clusters match the API"() {
		expect:
		clusters.take(MAX_CHECKED).each { Map cluster -> checkCluster(cluster) }
	}

	def "vm-folders uses the folder levels of the setting folderTags"() {
		when:
		Map folders = admin.route("$BASE/vm-folders").json() as Map
		List<String> expected = FolderSettings.parse(JsonOutput.toJson(installed.config ?: [:]))

		then:
		folders.levels == expected
		folders.vms.every { it.folders instanceof List && it.folders.size() <= expected.size() && it.tenantId != null }
	}

	def "a changed folderTags setting takes effect immediately and can be reset"() {
		given:
		Map before = PluginBaseline.normalize(installed.config)
		List<String> levels = [env.testName('level-1'), env.testName('level-2')]

		when:
		ops.update(installed.id, [config: before + [folderTags: levels.join(',')]])
		List<String> changedLevels = admin.route("$BASE/vm-folders").json().levels as List
		ops.update(installed.id, [config: before])
		List<String> resetLevels = admin.route("$BASE/vm-folders").json().levels as List

		then:
		changedLevels == levels
		resetLevels == FolderSettings.parse(JsonOutput.toJson(before))
		PluginBaseline.normalize(ops.find(CODE).config) == before
	}

	def "invalid parameters are answered with a JSON error"() {
		expect:
		with(admin.route("$BASE/$path")) {
			status == expectedStatus
			json().error instanceof String
		}

		where:
		path                              || expectedStatus
		'hosts'                           || 400
		'hosts?clusterId=abc'             || 400
		'datastores?clusterId=999999999'  || 404
		'networks?clusterId=999999999'    || 404
		'vms?hostId=999999999'            || 404
	}

	def "plugin routes need a UI session and do not accept the API token"() {
		when:
		HttpResult anonymous = new UiSession(env).route("$BASE/clusters")
		HttpResult token = api.call('GET', "$BASE/clusters")

		then:
		anonymous.status == 302
		anonymous.location?.contains('/login')
		token.status == 302
		token.location?.contains('/login')
	}

	def "a user without permissions gets no data"() {
		when:
		testUser.create()
		log "created test user ${testUser.username} with role ${testUser.roleName}"
		HttpResult result = testUser.login().route("$BASE/clusters")

		then: 'Morpheus itself refuses the route (404 or 403), no cluster data'
		result.status in [403, 404]
		!result.body.contains('"clusters"')
	}

	def "a user with read access to clusters only sees clusters without actions"() {
		given:
		testUser.grant('infrastructure-cluster', 'read')
		UiSession session = testUser.login()
		Object clusterId = clusters ? clusters[0].id : 1

		when:
		HttpResult visible = session.route("$BASE/clusters")

		then:
		visible.status == 200
		(visible.json().clusters as List).every { !it.actions }
		session.route("$BASE/hosts?clusterId=$clusterId").status == 403
		session.route("$BASE/datastores?clusterId=$clusterId").status == 403
		session.route("$BASE/networks?clusterId=$clusterId").status == 403
		session.route("$BASE/vm-folders").status == 403
	}

	def "the storage path state of the Storage Path Monitor plugin refers to datastores of the cluster (optional)"() {
		given:
		Map monitor = ops.find(PATH_MONITOR_CODE)
		if (monitor?.status != 'loaded' || monitor.enabled == false) {
			throw new TestAbortedException("plugin $PATH_MONITOR_CODE not installed and enabled")
		}

		expect:
		clusters.take(MAX_CHECKED).each { Map cluster ->
			HttpResult result = admin.route("/plugin/$PATH_MONITOR_CODE/paths?clusterId=${cluster.id}")
			assert result.status == 200
			List<Map> datastores = result.json().datastores as List<Map>
			assert datastores.every { it.id in datastoreIds[cluster.id] }
			assert datastores.every { it.state in ['ok', 'degraded', 'down', 'unknown'] }
			assert datastores.every { ds -> (ds.hosts as List<Map>).every { it.hostId in hostIds[cluster.id] } }
			log "cluster ${cluster.name}: path state for ${datastores.size()} datastore(s)"
		}
	}

	private void checkCluster(Map cluster) {
		Object id = cluster.id
		Map apiCluster = api.get("/api/clusters/$id").cluster as Map
		Set apiServerIds = (apiCluster.servers ?: [])*.id as Set

		HttpResult hostsResult = admin.route("$BASE/hosts?clusterId=$id")
		assert hostsResult.status == 200
		Map hosts = hostsResult.json() as Map
		assert hosts.clusterId == id
		assert hosts.hosts.every { it.clusterId == id && it.id in apiServerIds }
		assert apiServerIds.isEmpty() || hosts.hosts
		hostIds[id] = hosts.hosts*.id as Set

		hosts.hosts.take(MAX_CHECKED).each { Map host ->
			HttpResult vmsResult = admin.route("$BASE/vms?hostId=${host.id}")
			assert vmsResult.status == 200
			Map vms = vmsResult.json() as Map
			assert vms.hostId == host.id
			assert vms.vms.every { it.hostId == host.id && it.actions instanceof List && it.url == "/infrastructure/servers/${it.id}" }
		}

		HttpResult datastoresResult = admin.route("$BASE/datastores?clusterId=$id")
		assert datastoresResult.status == 200
		List<Map> datastores = datastoresResult.json().datastores as List<Map>
		Set apiDatastoreIds = (api.get("/api/clusters/$id/datastores?max=1000").datastores as List<Map>)*.id as Set
		assert datastores.every { it.id in apiDatastoreIds }
		datastoreIds[id] = datastores*.id as Set

		HttpResult networksResult = admin.route("$BASE/networks?clusterId=$id")
		assert networksResult.status == 200
		Map networks = networksResult.json() as Map
		assert networks.clusterId == id
		assert networks.networks.every { it.id != null && it.name && it.vms instanceof List && it.vmCount == it.vms.size() }
		// VMs below a network list their interfaces there (columns of the interface query) and no resources
		networks.networks.each { net ->
			net.vms.each { vm ->
				assert vm.interfaces instanceof List && !vm.containsKey('cores') && !vm.containsKey('disks')
				assert vm.ipAddresses.every { ip -> vm.interfaces.any { it.addresses?.contains(ip) } }
			}
		}

		log "cluster ${cluster.name}: ${hosts.hosts.size()} host(s), ${datastores.size()} datastore(s), " +
			"${networks.networks.size()} network(s)"
	}

	private static void log(String message) {
		println "[integration test] $message"
	}
}
