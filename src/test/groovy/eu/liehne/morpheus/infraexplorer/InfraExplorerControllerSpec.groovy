package eu.liehne.morpheus.infraexplorer

import com.morpheusdata.core.MorpheusContext
import io.reactivex.rxjava3.core.Single
import com.morpheusdata.model.Account
import com.morpheusdata.model.User
import com.morpheusdata.views.JsonResponse
import com.morpheusdata.views.ViewModel
import eu.liehne.morpheus.infraexplorer.source.InfraNode
import eu.liehne.morpheus.infraexplorer.source.InfraSource
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import spock.lang.Shared
import spock.lang.Specification

class InfraExplorerControllerSpec extends Specification {

	@Shared User admin = new User(id: 1L, permissions: ['infrastructure-cluster': 'full', 'admin-servers': 'full'])
	@Shared User clustersOnly = new User(id: 2L, permissions: ['infrastructure-cluster': 'read', 'admin-servers': 'none'])
	@Shared User storage = new User(id: 4L, permissions: ['infrastructure-cluster': 'read', 'infrastructure-storage': 'read'])
	@Shared User readOnly = new User(id: 5L, permissions: ['infrastructure-cluster': 'read', 'admin-servers': 'read'])
	@Shared User nobody = new User(id: 3L, permissions: ['infrastructure-cluster': 'none'])

	InfraSource source = Mock()
	MorpheusContext morpheus = Stub {
		getSettings(_) >> Single.just('{"folderTags":"Folder-1,Folder-2"}')
	}
	InfraExplorerController controller = new InfraExplorerController(new InfraExplorerPlugin(), morpheus, [source])

	def "registers the tree routes with the cluster permission"() {
		expect:
		controller.routes*.url == ['/infra-explorer/clusters', '/infra-explorer/hosts', '/infra-explorer/vms',
								   '/infra-explorer/datastores', '/infra-explorer/vm-folders', '/infra-explorer/vm-folder',
								   '/infra-explorer/folder-rename', '/infra-explorer/level-rename']
		controller.routes*.method == ['clusters', 'hosts', 'vms', 'datastores', 'vmFolders', 'vmFolder', 'folderRename', 'levelRename']
		controller.routes.every { it.permissions*.code == ['infrastructure-cluster'] }
	}

	def "returns the clusters of all sources as JSON"() {
		given:
		source.listClusters(admin) >> [new InfraNode(id: 4L, name: 'test-cluster', status: 'ok', type: 'HVM')]

		when:
		JsonResponse response = controller.clusters(model(admin))

		then:
		response.status == 200
		response.data == [clusters: [[id: 4L, name: 'test-cluster', status: 'ok', type: 'HVM', actions: ['refresh', 'edit']]]]
	}

	def "returns hosts and VMs for the requested parent"() {
		given:
		source.listHosts(admin, 4L) >> [new InfraNode(id: 13L, name: 'host-1', powerState: 'on', clusterId: 4L)]
		source.listVms(admin, 13L) >> [new InfraNode(id: 16L, name: 'vm', powerState: 'off', hostId: 13L)]

		expect:
		controller.hosts(model(admin, [clusterId: '4'])).data == [clusterId: 4L, hosts: [[id: 13L, name: 'host-1', powerState: 'on', clusterId: 4L, actions: ['maintenance', 'edit']]]]
		controller.vms(model(admin, [hostId: '13'])).data == [hostId: 13L, vms: [[id: 16L, name: 'vm', powerState: 'off', hostId: 13L, actions: ['folder']]]]
	}

	def "answers 403 without the required feature permissions and does not query the source"() {
		when:
		JsonResponse response = controller."$route"(model(user, params))

		then:
		response.status == 403
		0 * source._

		where:
		route      | user         | params
		'clusters' | nobody       | [:]
		'clusters' | null         | [:]
		'hosts'    | clustersOnly | [clusterId: '4']
		'vms'      | clustersOnly | [hostId: '13']
		'datastores' | clustersOnly | [clusterId: '4']
		'hosts'    | storage      | [clusterId: '4']
		'vmFolders' | clustersOnly | [:]
	}

	def "returns all VMs with folder path from the configured tags, without the raw tags"() {
		given:
		source.listAllVms(admin) >> [
			new InfraNode(id: 2L, name: 'web-1', hostName: 'host-1', tenantId: 1L, tenant: 'Main', tags: ['Folder-1': 'Prod', 'Folder-2': 'Web', 'Owner': 'x']),
			new InfraNode(id: 1L, name: 'App', hostName: 'host-2', tags: [:])
		]

		when:
		Map data = controller.vmFolders(model(admin)).data

		then:
		data.levels == ['Folder-1', 'Folder-2']
		data.vms == [[id: 1L, name: 'App', hostName: 'host-2', folders: [], actions: ['folder']],
					 [id: 2L, name: 'web-1', hostName: 'host-1', tenantId: 1L, tenant: 'Main', folders: ['Prod', 'Web'], actions: ['folder']]]
	}

	def "names the tenant for new instances if the user may add instances"() {
		given:
		User creator = new User(id: 6L, account: new Account(id: 2L), permissions: ['infrastructure-cluster': 'read',
			'admin-servers': 'read', 'provisioning-add': 'full'])
		source.listAllVms(_) >> []

		expect:
		controller.vmFolders(model(creator)).data.create == [tenantId: 2L]
		!controller.vmFolders(model(admin)).data.containsKey('create')
	}

	def "offers the actions the user may start on each VM"() {
		given:
		User operator = new User(id: 9L, permissions: ['infrastructure-cluster': 'read', 'admin-servers': 'read',
													   'terminal': 'full', 'provisioning-power': 'user'])
		source.listVms(operator, 13L) >> [new InfraNode(id: 16L, name: 'own', createdById: 9L),
										  new InfraNode(id: 17L, name: 'other', createdById: 1L)]

		expect:
		controller.vms(model(operator, [hostId: '13'])).data.vms*.actions == [['console', 'power'], ['console']]
	}

	def "returns the datastores of a cluster for users with storage permission"() {
		given:
		source.listDatastores(storage, 4L) >> [new InfraNode(id: 14L, name: 'cluster-store', storageSize: 1000L, freeSpace: 400L, online: true)]

		expect:
		controller.datastores(model(storage, [clusterId: '4'])).data ==
			[clusterId: 4L, datastores: [[id: 14L, name: 'cluster-store', storageSize: 1000L, freeSpace: 400L, online: true]]]
		controller.datastores(model(storage, [clusterId: 'x'])).status == 400
	}

	def "answers 404 when no source knows or shows the object"() {
		given:
		source.listHosts(admin, 99L) >> null
		def m = model(admin, [clusterId: '99'])

		when:
		JsonResponse response = controller.hosts(m)

		then:
		response.status == 404
		1 * m.response.setStatus(404)
	}

	def "answers 400 for a missing or non-numeric id"() {
		expect:
		controller.hosts(model(admin, params)).status == 400

		where:
		params << [[:], [clusterId: 'abc'], [clusterId: '4 OR 1=1']]
	}

	def "answers 500 with a generic message when a source fails"() {
		given:
		source.listClusters(admin) >> { throw new IllegalStateException('database details') }

		when:
		JsonResponse response = controller.clusters(model(admin))

		then:
		response.status == 500
		response.data == [error: 'internal error']
	}

	def "answers 404 for VMs of an unknown or invisible host"() {
		given:
		source.listVms(admin, 77L) >> null

		expect:
		controller.vms(model(admin, [hostId: '77'])).status == 404
	}

	def "merges clusters of several sources sorted by name and asks sources in turn for hosts"() {
		given:
		InfraSource second = Mock()
		def multi = new InfraExplorerController(new InfraExplorerPlugin(), Stub(MorpheusContext), [source, second])
		source.listClusters(admin) >> [new InfraNode(id: 1L, name: 'zeta')]
		second.listClusters(admin) >> [new InfraNode(id: 2L, name: 'Alpha')]
		source.listHosts(admin, 2L) >> null
		second.listHosts(admin, 2L) >> [new InfraNode(id: 5L, name: 'h', clusterId: 2L)]

		expect:
		multi.clusters(model(admin)).data.clusters*.name == ['Alpha', 'zeta']
		multi.hosts(model(admin, [clusterId: '2'])).data.hosts*.id == [5L]
	}

	def "sets the folder path of a VM by POST"() {
		when:
		Map data = controller.vmFolder(model(admin, [serverId: '16', path: [' Prod ', 'Web', '']], 'POST')).data

		then:
		1 * source.setFolders(admin, 16L, ['Folder-1', 'Folder-2'], ['Prod', 'Web']) >>
			new InfraNode(id: 16L, name: 'vm', tags: ['Folder-1': 'Prod', 'Folder-2': 'Web'])
		data == [levels: ['Folder-1', 'Folder-2'], vm: [id: 16L, name: 'vm', folders: ['Prod', 'Web'], actions: ['folder']]]
	}

	def "removes the folder of a VM when no path is given"() {
		when:
		controller.vmFolder(model(admin, [serverId: '16'], 'POST'))

		then:
		1 * source.setFolders(admin, 16L, _, []) >> new InfraNode(id: 16L, tags: [:])
	}

	def "sends the answers as UTF-8"() {
		given:
		HttpServletResponse response = Mock()
		ViewModel viewModel = model(admin)
		viewModel.response = response

		when:
		controller.clusters(viewModel)

		then:
		1 * response.setCharacterEncoding('UTF-8')
	}

	def "renames a folder for the VMs below it"() {
		when:
		Map data = controller.folderRename(model(admin, [tenantId: '1', path: ['Prod', 'Web'], name: ' Shop '], 'POST')).data

		then:
		1 * source.renameFolder(admin, 1L, ['Folder-1', 'Folder-2'], ['Prod', 'Web'], 'Shop', 100) >> [new InfraNode(id: 1L), new InfraNode(id: 2L)]
		data == [levels: ['Folder-1', 'Folder-2'], path: ['Prod', 'Shop'], changed: 2]
	}

	def "rejects folder renames that are no POST, without permission, invalid or without VMs"() {
		given:
		source.renameFolder(admin, 1L, _, ['Empty'], 'New', _) >> []
		source.renameFolder(admin, 1L, _, ['Big'], 'New', _) >> { throw new IllegalStateException('too many') }

		expect:
		controller.folderRename(model(admin, [tenantId: '1', path: 'Prod', name: 'New'], 'GET')).status == 405
		controller.folderRename(model(readOnly, [tenantId: '1', path: 'Prod', name: 'New'], 'POST')).status == 403
		controller.folderRename(model(admin, [tenantId: 'x', path: 'Prod', name: 'New'], 'POST')).status == 400
		controller.folderRename(model(admin, [tenantId: '1', name: 'New'], 'POST')).status == 400
		controller.folderRename(model(admin, [tenantId: '1', path: 'Prod', name: ' '], 'POST')).status == 400
		controller.folderRename(model(admin, [tenantId: '1', path: 'Prod', name: "a\u200Bb"], 'POST')).status == 400
		controller.folderRename(model(admin, [tenantId: '1', path: 'Empty', name: 'New'], 'POST')).status == 404
		controller.folderRename(model(admin, [tenantId: '1', path: 'Prod', name: 'Prod'], 'POST')).status == 400
		controller.folderRename(model(admin, [tenantId: '1', path: 'Prod', name: 'Prod'], 'POST')).data ==
			[error: 'the new name is the same as the current one']
		controller.folderRename(model(admin, [tenantId: '1', path: 'Prod', name: 'x' * 101], 'POST')).status == 400
		controller.folderRename(model(admin, [tenantId: '1', path: ['a', 'b', 'c'], name: 'New'], 'POST')).status == 400
		controller.folderRename(model(admin, [tenantId: '1', path: 'Big', name: 'New'], 'POST')).data == [error: 'too many']
	}

	def "renames level tags for users who may change plugins and folders"() {
		given:
		User levelAdmin = new User(id: 11L, permissions: ['infrastructure-cluster': 'read', 'admin-servers': 'full',
			'admin-plugins': 'full'])

		when:
		Map data = controller.levelRename(model(levelAdmin, [from: ['Folder-1', 'Folder-2'], to: [' Location ', 'Folder-2']], 'POST')).data

		then:
		1 * source.renameTags(levelAdmin, ['Folder-1': 'Location'], 100) >> [changed: 3, skipped: 1]
		data == [renames: ['Folder-1': 'Location'], changed: 3, skipped: 1]
	}

	def "rejects level renames that are no POST, without permission or invalid"() {
		given:
		User levelAdmin = new User(id: 11L, permissions: ['infrastructure-cluster': 'read', 'admin-servers': 'full',
			'admin-plugins': 'full'])

		expect:
		controller.levelRename(model(levelAdmin, [from: 'Folder-1', to: 'A'], 'GET')).status == 405
		controller.levelRename(model(admin, [from: 'Folder-1', to: 'A'], 'POST')).status == 403
		controller.levelRename(model(levelAdmin, [from: 'Folder-9', to: 'A'], 'POST')).status == 400
		controller.levelRename(model(levelAdmin, [from: 'Folder-1', to: 'Folder-2'], 'POST')).status == 400
		controller.levelRename(model(levelAdmin, [from: ['Folder-1', 'Folder-2'], to: ['A', 'A']], 'POST')).status == 400
		controller.levelRename(model(levelAdmin, [from: 'Folder-1', to: 'A,B'], 'POST')).status == 400
		controller.levelRename(model(levelAdmin, [from: 'Folder-1', to: 'Folder-1'], 'POST')).status == 400
		controller.levelRename(model(levelAdmin, [from: 'Folder-1'], 'POST')).status == 400
		controller.levelRename(model(levelAdmin, [from: 'Folder-1'], 'POST')).data == [error: 'levels missing']
	}

	def "does not touch tags for invalid level renames"() {
		given:
		User levelAdmin = new User(id: 11L, permissions: ['infrastructure-cluster': 'read', 'admin-servers': 'full',
			'admin-plugins': 'full'])

		when:
		controller.levelRename(model(levelAdmin, [from: 'Folder-1', to: 'Folder-2'], 'POST'))

		then:
		0 * source.renameTags(*_)
	}

	def "marks the levels as editable for users who may change plugins and folders"() {
		given:
		User levelAdmin = new User(id: 11L, permissions: ['infrastructure-cluster': 'read', 'admin-servers': 'full',
			'admin-plugins': 'full'])
		source.listAllVms(_) >> []

		expect:
		controller.vmFolders(model(levelAdmin)).data.levelsEditable == true
		!controller.vmFolders(model(admin)).data.containsKey('levelsEditable')
	}

	def "answers 405 for folder changes without request"() {
		expect:
		controller.vmFolder(new ViewModel(user: admin, response: Mock(HttpServletResponse))).status == 405
	}

	def "rejects folder changes that are no POST, without permission, invalid or for unknown VMs"() {
		given:
		source.setFolders(admin, 77L, _, _) >> null

		expect:
		controller.vmFolder(model(admin, [serverId: '16', path: 'Prod'], 'GET')).status == 405
		controller.vmFolder(model(clustersOnly, [serverId: '16', path: 'Prod'], 'POST')).status == 403
		controller.vmFolder(model(readOnly, [serverId: '16', path: 'Prod'], 'POST')).status == 403
		controller.vmFolder(model(admin, [serverId: 'x', path: 'Prod'], 'POST')).status == 400
		controller.vmFolder(model(admin, [serverId: '16', path: ['a', 'b', 'c']], 'POST')).status == 400
		controller.vmFolder(model(admin, [serverId: '16', path: ['', 'b']], 'POST')).status == 400
		controller.vmFolder(model(admin, [serverId: '77', path: 'Prod'], 'POST')).status == 404
	}

	def "does not pass messages of unexpected exceptions to the client"() {
		given:
		source.listHosts(admin, 4L) >> { throw new IllegalArgumentException('internal detail') }

		when:
		JsonResponse response = controller.hosts(model(admin, [clusterId: '4']))

		then:
		response.status == 500
		response.data == [error: 'internal error']
	}

	private ViewModel model(User user, Map params = [:], String method = 'GET') {
		HttpServletRequest request = Stub {
			getParameter(_) >> { String name -> params[name] instanceof List ? params[name][0] : params[name] }
			getParameterValues(_) >> { String name ->
				def value = params[name]
				value == null ? null : (value instanceof List ? value : [value]) as String[]
			}
			getMethod() >> method
		}
		new ViewModel(user: user, request: request, response: Mock(HttpServletResponse))
	}
}
