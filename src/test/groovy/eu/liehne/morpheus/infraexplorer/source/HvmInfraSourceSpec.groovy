package eu.liehne.morpheus.infraexplorer.source

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.synchronous.MorpheusSynchronousWorkloadService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousDatastoreService
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerGroupService
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService
import com.morpheusdata.core.synchronous.network.MorpheusSynchronousNetworkService
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.ComputeServerInterfaceType
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.ComputeServerGroupType
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.DatastoreType
import com.morpheusdata.model.Instance
import com.morpheusdata.model.MetadataTag
import com.morpheusdata.model.NetAddress
import com.morpheusdata.model.Network
import com.morpheusdata.model.NetworkLocation
import com.morpheusdata.model.NetworkSubnet
import com.morpheusdata.model.NetworkType
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.User
import com.morpheusdata.model.Workload
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import com.morpheusdata.model.projection.DatastoreIdentity
import spock.lang.Specification

/**
 * The services are replaced by in-memory lists that apply the query filters. Per user they return a configurable
 * set of objects; for the tenant cases they deliberately return too much, as Morpheus is not guaranteed to filter
 * by resource permissions.
 */
class HvmInfraSourceSpec extends Specification {

	Account master = new Account(id: 1L, name: 'Main', masterAccount: true)
	Account tenantA = new Account(id: 2L)
	Account tenantB = new Account(id: 3L)

	User admin = new User(id: 7L, account: master)
	User userA = new User(id: 8L, account: tenantA)
	User noAccount = new User(id: 9L)

	ComputeServerGroup hvmCluster = cluster(1L, 'test-cluster', 'mvm-cluster', master)
	ComputeServerGroup otherCluster = cluster(2L, 'test-kube', 'kubernetes-cluster', master)
	ComputeServerGroup clusterA = cluster(3L, 'test-a', 'mvm-cluster', tenantA)
	ComputeServer host2 = host(14L, 'host-2', hvmCluster, master)
	ComputeServer host1 = host(13L, 'host-1', hvmCluster, master)
	ComputeServer kubeHost = host(20L, 'kube-1', otherCluster, master)
	ComputeServer hostA = host(40L, 'test-host-a', clusterA, tenantA)
	ComputeServer managedVm = vm(30L, 'test-vm', host1, ComputeServer.PowerState.off, master)
	ComputeServer discoveredVm = vm(16L, 'discovered-vm', host1, ComputeServer.PowerState.on, master,
		new ComputeServerType(code: 'kvmUnmanaged', managed: false))
	ComputeServer vmA = vm(41L, 'test-vm-a', hostA, ComputeServer.PowerState.on, tenantA)
	ComputeServer vmB = vm(42L, 'test-vm-b', hostA, ComputeServer.PowerState.on, tenantB)
	/** A server in the cluster that has a parent, i.e. not a host */
	ComputeServer nestedVm = vm(43L, 'test-nested', host1, ComputeServer.PowerState.on, master)

	Datastore sharedLun = datastore(14L, 'cluster-store', hvmCluster, master, new DatastoreType(name: 'Clustered Datastore'))
	Datastore localDir = datastore(10L, 'Local', hvmCluster, master, null, false)
	Datastore kubeStore = datastore(20L, 'kube-store', otherCluster, master, null)
	Datastore storeA = datastore(40L, 'test-store-a', clusterA, tenantA, null)
	Datastore storeB = datastore(41L, 'test-store-b', clusterA, tenantB, null)
	/** Same refId as the HVM cluster, but referencing another kind of object */
	Datastore otherRef = new Datastore(id: 50L, name: 'test-other', refType: 'ComputeZone', refId: 1L, owner: master)

	/** Carried by host-2 of the HVM cluster, with two subnets */
	Network netA = network(60L, 'test-net-a', master, [host2], 24, '10.0.0.0/24')
	/** Not carried by any host of the cluster, only used by a VM */
	Network netB = network(61L, 'test-net-b', master, [], null, null)
	Network kubeNet = network(62L, 'test-kube-net', master, [kubeHost], null, '10.9.0.0/24')
	Network inactiveNet = network(63L, 'test-inactive', master, [host1], null, null)
	Network netOfB = network(64L, 'test-net-tenant-b', tenantB, [hostA], null, null)
	Network sharedNet = network(65L, 'test-net-shared', master, [hostA], null, '10.5.0.0/24')

	List everything = [hvmCluster, otherCluster, clusterA, host1, host2, kubeHost, hostA, managedVm, discoveredVm,
					   vmA, vmB, sharedLun, localDir, kubeStore, storeA, storeB, otherRef,
					   netA, netB, kubeNet, inactiveNet, netOfB, sharedNet,
					   new Workload(id: 99L, server: managedVm, account: master, instance: new Instance(id: 500L, account: master)),
					   new Workload(id: 98L, server: managedVm, account: master, instance: new Instance(id: 501L, account: master, createdBy: new User(id: 8L))),
					   new Workload(id: 97L, server: vmA, account: tenantB, instance: new Instance(id: 600L, account: tenantB))]

	/** Objects the stubbed services return, per user */
	Map<User, List> returned = [:]
	List<DataQuery> queries = []
	List<ComputeServer> saved = []
	/** Servers in maintenance mode (column without model property) */
	Set<Long> maintenanceIds = []
	/** Overrides what computeServer.find returns, e.g. a server changed after the visibility check */
	ComputeServer loaded
	/** Networks shared publicly (column without model property) */
	Set<Long> publicNetworkIds = []
	/** Deliver ids and other whole numbers as Integer, as the database layer may do */
	boolean integerValues = false

	HvmInfraSource source

	def setup() {
		nestedVm.serverGroup = hvmCluster
		managedVm.createdBy = new User(id: 7L)
		managedVm.metadata = [new MetadataTag(name: 'Folder-1', value: 'Prod'), new MetadataTag(name: 'Folder-2', value: 'Web'),
							  new MetadataTag(name: 'Folder-1', value: 'ignored')]
		netA.subnets = [new NetworkSubnet(id: 71L, name: 'test-sub-2', cidr: '10.0.0.128/25'),
						new NetworkSubnet(id: 70L, name: 'test-sub-1', cidr: '10.0.0.0/25')]
		inactiveNet.active = false
		// Two interfaces in the same network (one VM, two addresses there), one in a network no host carries
		managedVm.interfaces = [nic(netA, '10.0.0.5', 'fe80::5'), nic(netA, '10.0.0.6'), nic(netB, '172.16.0.5'),
								nic(inactiveNet, '10.1.0.5')]
		managedVm.interfaces[0].with { name = 'eth0'; macAddress = '52:54:00:00:00:01'; primaryInterface = true; dhcp = false
									   type = new ComputeServerInterfaceType(name: 'VirtIO') }
		managedVm.maxCores = 4L
		managedVm.maxMemory = 16L * 1024 * 1024 * 1024
		managedVm.volumes = [new StorageVolume(id: 902L, name: 'data', maxStorage: 200L * 1024 * 1024 * 1024),
							 new StorageVolume(id: 901L, name: 'root', maxStorage: 50L * 1024 * 1024 * 1024,
									  datastore: new DatastoreIdentity(id: 14L, name: 'cluster-store'))]
		discoveredVm.interfaces = [nic(null)]
		vmA.interfaces = [nic(netOfB, '10.4.0.7'), nic(sharedNet, '10.5.0.7')]
		returned[admin] = everything + [nestedVm]
		returned[userA] = everything
		returned[noAccount] = everything

		def clusterService = Stub(MorpheusSynchronousComputeServerGroupService) {
			listOptions(_) >> { DataQuery q -> options(q, ComputeServerGroup) }
		}
		def serverService = Stub(MorpheusSynchronousComputeServerService) {
			listOptions(_) >> { DataQuery q -> options(q, ComputeServer) }
			find(_) >> { DataQuery q -> queries << q; loaded != null ? loaded : query(q, ComputeServer)[0] }
			save(_) >> { ComputeServer server -> saved << server; server }
		}
		def workloadService = Stub(MorpheusSynchronousWorkloadService) {
			listOptions(_) >> { DataQuery q -> options(q, Workload) }
		}
		def datastoreService = Stub(MorpheusSynchronousDatastoreService) {
			listOptions(_) >> { DataQuery q -> options(q, Datastore) }
		}
		def cloudService = Stub(MorpheusSynchronousCloudService) {
			getDatastore() >> datastoreService
		}
		def networkService = Stub(MorpheusSynchronousNetworkService) {
			listOptions(_) >> { DataQuery q -> options(q, Network) }
		}
		def services = Stub(MorpheusServices) {
			getNetwork() >> networkService
			getCloud() >> cloudService
			getCluster() >> clusterService
			getComputeServer() >> serverService
			getWorkload() >> workloadService
		}
		MorpheusContext morpheus = Stub {
			getServices() >> services
		}
		source = new HvmInfraSource(morpheus)
	}

	def "lists only HVM clusters, sorted by name"() {
		when:
		List<InfraNode> clusters = source.listClusters(admin)

		then:
		clusters*.name == ['test-a', 'test-cluster']
		clusters.find { it.id == 1L }.status == 'ok'
		clusters.find { it.id == 1L }.type == 'HVM'
		clusters.find { it.id == 1L }.url == '/infrastructure/clusters/1'
	}

	def "lists hosts of a cluster sorted by name, without servers that have a parent"() {
		when:
		List<InfraNode> hosts = source.listHosts(admin, 1L)

		then:
		hosts*.name == ['host-1', 'host-2']
		hosts.every { it.clusterId == 1L && it.powerState == 'on' && it.type == 'mvmHost' }
		hosts*.url == ['/infrastructure/servers/13', '/infrastructure/servers/14']
	}

	def "marks hosts in maintenance mode"() {
		given:
		maintenanceIds << 14L

		expect:
		source.listHosts(admin, 1L).collectEntries { [(it.name): it.maintenance] } == ['host-1': false, 'host-2': true]
	}

	def "returns null for hosts of a cluster of another type, an unknown cluster or a missing id"() {
		expect:
		source.listHosts(admin, 2L) == null
		source.listHosts(admin, 404L) == null
		source.listHosts(admin, null) == null
	}

	def "lists VMs of a host with power state and the instance with the lowest workload id"() {
		when:
		List<InfraNode> vms = source.listVms(admin, 13L)

		then:
		vms*.name == ['discovered-vm', 'test-nested', 'test-vm']
		vms.find { it.id == 30L }.instanceId == 501L
		vms.find { it.id == 30L }.powerState == 'off'
		vms.find { it.id == 16L }.instanceId == null
		vms.every { it.hostId == 13L }
		vms.find { it.id == 16L }.managed == false
		vms.find { it.id == 30L }.managed == true
		vms.find { it.id == 16L }.url == '/infrastructure/servers/16'
	}

	def "returns null for VMs of a host outside a visible HVM cluster or of a server that is no host"() {
		expect:
		source.listVms(admin, 20L) == null
		source.listVms(admin, 30L) == null
		source.findHost(admin, 43L) == null
	}

	def "finds a host including its cluster"() {
		expect:
		source.findHost(admin, 14L).clusterId == 1L
		source.findHost(admin, 20L) == null
	}

	def "a tenant user sees only objects of the own tenant even if the services return more"() {
		expect:
		source.listClusters(userA)*.id == [3L]
		source.listHosts(userA, 1L) == null
		source.listVms(userA, 13L) == null
		source.listHosts(userA, 3L)*.id == [40L]
		source.listVms(userA, 40L)*.id == [41L]
	}

	def "does not reveal the instance of a foreign tenant"() {
		expect:
		source.listVms(userA, 40L).find { it.id == 41L }.instanceId == null
	}

	def "a user without tenant sees nothing"() {
		expect:
		source.listClusters(noAccount).isEmpty()
		source.listHosts(noAccount, 1L) == null
		source.listVms(noAccount, 13L) == null
	}

	def "lists datastores of a cluster with type, capacity and page"() {
		when:
		List<InfraNode> stores = source.listDatastores(admin, 1L)

		then:
		stores*.name == ['cluster-store', 'Local']
		with(stores[0]) {
			type == 'Clustered Datastore'
			storageSize == 1000L
			freeSpace == 400L
			online == true
			status == 'provisioned'
			url == '/infrastructure/storage/data-stores/14'
		}
		stores[1].type == 'generic'
		stores[1].online == false
	}

	def "marks inactive datastores"() {
		given:
		localDir.active = false

		expect:
		source.listDatastores(admin, 1L).find { it.id == 10L }.status == 'inactive'
	}

	def "returns datastores only below a visible HVM cluster of the own tenant"() {
		expect:
		source.listDatastores(admin, 2L) == null
		source.listDatastores(admin, 404L) == null
		source.listDatastores(userA, 1L) == null
		source.listDatastores(userA, 3L)*.id == [40L]
		source.listDatastores(noAccount, 1L) == null
	}

	def "lists all VMs below visible HVM clusters with host name and tags"() {
		when:
		List<InfraNode> vms = source.listAllVms(admin)

		then:
		vms*.name == ['discovered-vm', 'test-nested', 'test-vm', 'test-vm-a', 'test-vm-b']
		vms.find { it.id == 30L }.hostName == 'host-1'
		vms.find { it.id == 30L }.tags == ['Folder-1': 'Prod', 'Folder-2': 'Web']
		vms.find { it.id == 30L }.instanceId == 501L
		vms.find { it.id == 41L }.hostName == 'test-host-a'
		vms.find { it.id == 16L }.tags == [:]
	}

	def "names the tenant of every VM"() {
		when:
		List<InfraNode> vms = source.listAllVms(admin)

		then:
		vms.find { it.id == 30L }.tenantId == 1L
		vms.find { it.id == 30L }.tenant == 'Main'
		vms.find { it.id == 42L }.tenantId == 3L
		vms.find { it.id == 42L }.tenant == 'Tenant 3'
	}

	def "takes the tenant name from the session if the server only carries the id"() {
		given:
		User named = new User(id: 10L, account: new Account(id: 2L, name: 'Team A'))
		returned[named] = everything

		expect:
		source.listAllVms(named)*.tenant == ['Team A']
	}

	def "returns creator of server and instance and the instance page"() {
		when:
		InfraNode vm = source.listVms(admin, 13L).find { it.id == 30L }

		then:
		vm.createdById == 7L
		vm.instanceCreatedById == 8L
		vm.instanceUrl == '/provisioning/instances/501'
		source.listVms(admin, 13L).find { it.id == 16L }.instanceUrl == null
	}

	def "finds a VM below a visible cluster only"() {
		expect:
		source.findVm(admin, 30L).instanceId == 501L
		source.findVm(admin, 30L).hostId == 13L
		source.findVm(admin, 13L) == null
		source.findVm(userA, 41L).id == 41L
		source.findVm(userA, 42L) == null
		source.findVm(userA, 30L) == null
		source.findVm(admin, null) == null
	}

	def "sets the folder tags and keeps other tags"() {
		given:
		managedVm.metadata = [new MetadataTag(name: 'Folder-1', value: 'Old'), new MetadataTag(name: 'Folder-3', value: 'Deep'),
							  new MetadataTag(name: 'Owner', value: 'x')]

		when:
		InfraNode vm = source.setFolders(admin, 30L, ['Folder-1', 'Folder-2', 'Folder-3'], ['Prod', 'Web'])

		then:
		vm.id == 30L
		vm.tags == ['Folder-1': 'Prod', 'Folder-2': 'Web']
		saved*.id == [30L]
		saved[0].metadata.collect { "${it.name}=${it.value}".toString() } == ['Owner=x', 'Folder-1=Prod', 'Folder-2=Web']
	}

	def "removes all folder tags for an empty path"() {
		when:
		source.setFolders(admin, 30L, ['Folder-1', 'Folder-2'], [])

		then:
		saved[0].metadata.isEmpty()
	}

	def "writes nothing if the loaded server is no longer the checked VM"() {
		given:
		loaded = changed

		expect:
		source.setFolders(admin, 30L, ['Folder-1'], ['Prod']) == null
		saved.isEmpty()

		where:
		changed << [
			new ComputeServer(id: 31L, parentServer: new ComputeServer(id: 13L), account: new Account(id: 1L)),
			new ComputeServer(id: 30L, parentServer: new ComputeServer(id: 14L), account: new Account(id: 1L))
		]
	}

	def "writes nothing if the server moved to another tenant in between"() {
		given:
		loaded = new ComputeServer(id: 41L, parentServer: hostA, account: tenantB)

		expect:
		source.setFolders(userA, 41L, ['Folder-1'], ['Prod']) == null
		saved.isEmpty()
	}

	def "finds and changes VMs with ids delivered as Integer"() {
		given:
		integerValues = true

		expect:
		source.findVm(admin, 30L).hostId == 13L
		source.findVm(admin, 30L).tenant == 'Main'
		source.setFolders(admin, 30L, ['Folder-1'], ['Prod']).id == 30L
		saved*.id == [30L]
	}

	def "renames a folder for all VMs below it in the tenant, at its level"() {
		given:
		managedVm.metadata = [new MetadataTag(name: 'Folder-1', value: 'Prod'), new MetadataTag(name: 'Folder-2', value: 'Web'),
							  new MetadataTag(name: 'Owner', value: 'x')]
		discoveredVm.metadata = [new MetadataTag(name: 'Folder-1', value: 'Prod')]
		nestedVm.metadata = [new MetadataTag(name: 'Folder-1', value: 'Test'), new MetadataTag(name: 'Folder-2', value: 'Web')]
		List<String> levels = ['Folder-1', 'Folder-2']

		when:
		List<InfraNode> changed = source.renameFolder(admin, 1L, levels, ['Prod'], 'Live', 100)

		then:
		changed*.id.sort() == [16L, 30L]
		saved.find { it.id == 30L }.metadata.collect { "${it.name}=${it.value}".toString() } as Set == ['Owner=x', 'Folder-1=Live', 'Folder-2=Web'] as Set
		saved.find { it.id == 16L }.metadata.collect { "${it.name}=${it.value}".toString() } == ['Folder-1=Live']
	}

	def "keeps deeper tags behind a gap when renaming"() {
		given:
		managedVm.metadata = [new MetadataTag(name: 'Folder-1', value: 'Prod'), new MetadataTag(name: 'Folder-3', value: 'X')]

		when:
		source.renameFolder(admin, 1L, ['Folder-1', 'Folder-2', 'Folder-3'], ['Prod'], 'Live', 100)

		then:
		saved[0].metadata.collect { "${it.name}=${it.value}".toString() } == ['Folder-3=X', 'Folder-1=Live']
	}

	def "skips VMs whose server changed after listing"() {
		given:
		managedVm.metadata = [new MetadataTag(name: 'Folder-1', value: 'Prod')]
		loaded = changed

		expect:
		source.renameFolder(admin, 1L, ['Folder-1'], ['Prod'], 'Live', 100).isEmpty()
		saved.isEmpty()

		where:
		changed << [
			new ComputeServer(id: 30L, parentServer: new ComputeServer(id: 14L), account: new Account(id: 1L),
				metadata: [new MetadataTag(name: 'Folder-1', value: 'Prod')]),
			new ComputeServer(id: 30L, parentServer: new ComputeServer(id: 13L), account: new Account(id: 2L),
				metadata: [new MetadataTag(name: 'Folder-1', value: 'Prod')]),
			new ComputeServer(id: 30L, parentServer: new ComputeServer(id: 13L), account: new Account(id: 1L),
				metadata: [new MetadataTag(name: 'Folder-1', value: 'Test')])
		]
	}

	def "refuses to rename folders with too many VMs"() {
		given:
		managedVm.metadata = [new MetadataTag(name: 'Folder-1', value: 'Prod')]
		discoveredVm.metadata = [new MetadataTag(name: 'Folder-1', value: 'Prod')]

		when:
		source.renameFolder(admin, 1L, ['Folder-1'], ['Prod'], 'Live', 1)

		then:
		thrown(IllegalStateException)
		saved.isEmpty()
	}

	def "renames a subfolder only below its parent"() {
		given:
		managedVm.metadata = [new MetadataTag(name: 'Folder-1', value: 'Prod'), new MetadataTag(name: 'Folder-2', value: 'Web')]
		discoveredVm.metadata = [new MetadataTag(name: 'Folder-1', value: 'Test'), new MetadataTag(name: 'Folder-2', value: 'Web')]

		when:
		List<InfraNode> changed = source.renameFolder(admin, 1L, ['Folder-1', 'Folder-2'], ['Prod', 'Web'], 'Shop', 100)

		then:
		changed*.id == [30L]
		saved*.id == [30L]
	}

	def "renames only VMs of the given tenant the user can see"() {
		given:
		vmA.metadata = [new MetadataTag(name: 'Folder-1', value: 'Prod')]
		vmB.metadata = [new MetadataTag(name: 'Folder-1', value: 'Prod')]

		expect:
		source.renameFolder(userA, 3L, ['Folder-1'], ['Prod'], 'Live', 100).isEmpty()
		source.renameFolder(userA, 2L, ['Folder-1'], ['Prod'], 'Live', 100)*.id == [41L]
		saved*.id == [41L]
	}

	def "renames level tags on all visible VMs and keeps the values"() {
		given:
		managedVm.metadata = [new MetadataTag(name: 'Folder-1', value: 'Prod'), new MetadataTag(name: 'Owner', value: 'x')]
		discoveredVm.metadata = [new MetadataTag(name: 'Folder-1', value: 'Test')]
		vmA.metadata = [new MetadataTag(name: 'Owner', value: 'y')]

		when:
		Map result = source.renameTags(admin, ['Folder-1': 'Location'], 100)

		then:
		result == [changed: 2, skipped: 0]
		saved*.id.sort() == [16L, 30L]
		saved.find { it.id == 30L }.metadata.collect { "${it.name}=${it.value}".toString() } as Set == ['Owner=x', 'Location=Prod'] as Set
		saved.find { it.id == 16L }.metadata.collect { "${it.name}=${it.value}".toString() } == ['Location=Test']
	}

	def "skips VMs that already carry the new tag name"() {
		given:
		managedVm.metadata = [new MetadataTag(name: 'Folder-1', value: 'Prod'), new MetadataTag(name: 'Location', value: 'Old')]

		expect:
		source.renameTags(admin, ['Folder-1': 'Location'], 100) == [changed: 0, skipped: 1]
		saved.isEmpty()
	}

	def "renames level tags only on VMs the user can see"() {
		given:
		vmA.metadata = [new MetadataTag(name: 'Folder-1', value: 'Prod')]
		vmB.metadata = [new MetadataTag(name: 'Folder-1', value: 'Prod')]

		expect:
		source.renameTags(userA, ['Folder-1': 'Location'], 100) == [changed: 1, skipped: 0]
		saved*.id == [41L]
	}

	def "refuses to rename level tags on too many VMs"() {
		given:
		managedVm.metadata = [new MetadataTag(name: 'Folder-1', value: 'Prod')]
		discoveredVm.metadata = [new MetadataTag(name: 'Folder-1', value: 'Test')]

		when:
		source.renameTags(admin, ['Folder-1': 'Location'], 1)

		then:
		thrown(IllegalStateException)
		saved.isEmpty()
	}

	def "does not change VMs the user cannot see"() {
		expect:
		source.setFolders(userA, 30L, ['Folder-1'], ['Prod']) == null
		source.setFolders(admin, 13L, ['Folder-1'], ['Prod']) == null
		saved.isEmpty()
	}

	def "copes with ids delivered as Integer"() {
		given:
		integerValues = true

		expect:
		source.listClusters(admin)*.id == [3L, 1L]
		source.listHosts(admin, 1L)*.id == [13L, 14L]
		source.listVms(admin, 13L).find { it.id == 30L }.instanceId == 501L
		source.listDatastores(admin, 1L)*.id == [14L, 10L]
		with(source.listAllVms(admin).find { it.id == 30L }) {
			hostName == 'host-1'
			tenantId == 1L
			instanceId == 501L
			tags == ['Folder-1': 'Prod', 'Folder-2': 'Web']
		}
		source.listAllVms(userA)*.id == [41L]
	}

	def "lists all VMs only of the own tenant"() {
		expect:
		source.listAllVms(userA)*.id == [41L]
		source.listAllVms(noAccount).isEmpty()
	}

	def "lists the networks of a cluster with the VMs connected to them"() {
		when:
		List<InfraNode> networks = source.listNetworks(admin, 1L)

		then: 'carried by a host of the cluster or used by one of its VMs; inactive and foreign ones left out'
		networks*.name == ['test-net-a', 'test-net-b', null]
		with(networks[0]) {
			id == 60L
			clusterId == 1L
			vlanId == 24
			cidr == '10.0.0.0/24'
			type == 'HVM Standard Network'
			url == '/infrastructure/networks/60'
			subnets == [[id: 70L, name: 'test-sub-1', cidr: '10.0.0.0/25'], [id: 71L, name: 'test-sub-2', cidr: '10.0.0.128/25']]
			vmCount == 1
			vms*.id == [30L]
			vms[0].ipAddresses == ['10.0.0.5', '10.0.0.6', 'fe80::5']
			vms[0].hostName == 'host-1'
			vms[0].instanceId == 501L
		}
		networks[1].vms*.ipAddresses == [['172.16.0.5']]

		and: 'VMs without network or with an interface in a network that is not shown, once each'
		networks[2].id == null
		networks[2].vms*.name == ['discovered-vm', 'test-nested', 'test-vm']
		networks[2].vmCount == 3
		networks[2].vms.find { it.id == 30L }.ipAddresses == ['10.1.0.5']
	}

	def "lists networks of a cluster without VMs and returns null for other clusters"() {
		given:
		returned[admin] = everything - [managedVm, discoveredVm]

		expect:
		source.listNetworks(admin, 1L)*.name == ['test-net-a']
		source.listNetworks(admin, 1L)[0].vmCount == 0
		source.listNetworks(admin, 2L) == null
		source.listNetworks(admin, 99L) == null
		source.listNetworks(admin, null) == null
	}

	def "lists only networks of the own tenant or shared publicly"() {
		when:
		List<InfraNode> networks = source.listNetworks(userA, 3L)

		then:
		networks*.name == [null]
		networks[0].vms*.id == [41L]

		when:
		publicNetworkIds << 65L
		networks = source.listNetworks(userA, 3L)

		then:
		networks*.name == ['test-net-shared', null]
		networks[0].vms[0].ipAddresses == ['10.5.0.7']
		networks[1].vms[0].ipAddresses == ['10.4.0.7']
	}

	def "VMs carry the configured cores, memory, total disk size and the disks"() {
		when:
		InfraNode vm = source.listAllVms(admin).find { it.id == 30L }
		InfraNode bare = source.listAllVms(admin).find { it.id == 16L }
		InfraNode inHost = source.listVms(admin, 13L).find { it.id == 30L }

		then:
		vm.cores == 4
		vm.memory == 16L * 1024 * 1024 * 1024
		vm.storage == 250L * 1024 * 1024 * 1024
		vm.disks*.name == ['root', 'data']
		vm.disks*.storage == ['cluster-store', null]
		vm.disks*.size == [50L * 1024 * 1024 * 1024, 200L * 1024 * 1024 * 1024]
		inHost.disks == vm.disks
		vm.toJson().cores == 4
		bare.disks == null
		!bare.toJson().containsKey('disks')
	}

	def "VMs below a network carry their interfaces there instead of the resources"() {
		when:
		InfraNode vm = source.listNetworks(admin, 1L)[0].vms[0]

		then:
		vm.interfaces*.addresses == [['10.0.0.5', 'fe80::5'], ['10.0.0.6']]
		vm.interfaces[0].subMap(['name', 'macAddress', 'type', 'primary', 'dhcp']) ==
			[name: 'eth0', macAddress: '52:54:00:00:00:01', type: 'VirtIO', primary: true, dhcp: false]
		vm.cores == null
		vm.disks == null
		vm.toJson().interfaces.size() == 2
		!vm.toJson().containsKey('cores')
	}

	def "lists the VMs with a disk on a datastore, with only the disks there"() {
		when:
		Map<Long, List<InfraNode>> byStore = source.listDatastoreVms(admin, 1L)

		then:
		byStore.keySet() == [14L] as Set
		byStore[14L]*.id == [30L]
		with(byStore[14L][0]) {
			hostName == 'host-1'
			disks == [[name: 'root', size: 50L * 1024 * 1024 * 1024, storageId: 14L, storage: 'cluster-store']]
			storage == 50L * 1024 * 1024 * 1024
		}
		source.listDatastoreVms(admin, 99L) == null
	}

	def "needs eight queries for the networks of a cluster"() {
		when:
		source.listNetworks(admin, 1L)

		then:
		queries.size() == 8
		queries.every { it.user.is(admin) }
	}

	def "needs six queries for all VMs"() {
		when:
		source.listAllVms(admin)

		then:
		queries.size() == 6
	}

	def "scopes every query to the requesting user"() {
		when:
		source.listClusters(admin)
		source.listHosts(admin, 1L)
		source.listVms(admin, 13L)
		source.listDatastores(admin, 1L)
		source.listAllVms(admin)

		then:
		!queries.isEmpty()
		queries.every { it.user.is(admin) }
	}

	/**
	 * Emulates listOptions with a propertyList: one map per object with the requested property paths. Paths into a
	 * collection (metadata.*, interfaces.*, interfaces.addresses.*, locations.*, subnets.*) give one row per element like
	 * the database join, or one row with null values for an empty collection.
	 */
	private List<Map> options(DataQuery q, Class type) {
		assert q.propertyList && q.max
		query(q, type).collectMany { item ->
			joinRows(item, q.propertyList).collect { it + [value: item.id] }
		}.collect { Map row -> integerValues ? row.collectEntries { k, v -> [(k): v instanceof Long ? v as Integer : v] } : row }
	}

	private List<Map> joinRows(Object item, List<String> paths) {
		List<Map> rows = [[:]]
		paths.groupBy { it.tokenize('.')[0] }.each { String head, List<String> group ->
			def value = resolve(item, head)
			List<String> rest = group.findAll { it != head }*.substring(head.length() + 1)
			List<Map> part
			if (value instanceof Collection) {
				part = ((value ?: [null]) as List).collectMany { joinRows(it, rest) }
			} else if (rest) {
				part = joinRows(value, rest)
			} else {
				part = [[:]]
			}
			part = part.collect { Map sub ->
				Map row = sub.collectEntries { k, v -> [("${head}.${k}".toString()): v] }
				if (head in group) {
					row[head] = value instanceof Collection ? null : value
				}
				row
			}
			rows = rows.collectMany { Map row -> part.collect { row + it } }
		}
		rows
	}

	/** Database columns the plugin model does not have (e.g. maintenanceMode); missing properties resolve to null */
	private Object resolve(Object item, String path) {
		if (path == 'maintenanceMode') {
			return item instanceof ComputeServer ? item.id in maintenanceIds : null
		}
		if (path == 'visibility') {
			return item instanceof Network ? (item.id in publicNetworkIds ? 'public' : 'private') : null
		}
		path.tokenize('.').inject(item) { obj, prop -> obj != null && obj.hasProperty(prop) ? obj."$prop" : null }
	}

	private List query(DataQuery q, Class type) {
		queries << q
		(returned[q.user] ?: []).findAll { type.isInstance(it) && q.filters.every { DataFilter f -> matches(it, f) } }
	}

	private boolean matches(Object item, DataFilter filter) {
		def actual = resolve(item, filter.name)
		switch (filter.operator) {
			case '==':
			case '=':
			case null:
				return actual == filter.value
			case 'in':
				return actual in filter.value
			default:
				throw new IllegalArgumentException("operator ${filter.operator} not supported in spec")
		}
	}

	private static ComputeServerGroup cluster(Long id, String name, String typeCode, Account owner) {
		new ComputeServerGroup(id: id, name: name, status: ComputeServerGroup.Status.ok, owner: owner,
			type: new ComputeServerGroupType(code: typeCode, name: typeCode == 'mvm-cluster' ? 'HVM' : 'Kubernetes'))
	}

	private static Datastore datastore(Long id, String name, ComputeServerGroup cluster, Account owner,
									  DatastoreType type, Boolean online = true) {
		new Datastore(id: id, name: name, refType: 'ComputeServerGroup', refId: cluster.id, owner: owner,
			datastoreType: type, storageSize: 1000L, freeSpace: 400L, online: online, status: 'provisioned')
	}

	private static Network network(Long id, String name, Account owner, List<ComputeServer> hosts, Integer vlanId,
								   String cidr) {
		new Network(id: id, name: name, owner: owner, active: true, status: 'ok', vlanId: vlanId, cidr: cidr,
			type: new NetworkType(name: 'HVM Standard Network'),
			locations: hosts.collect { new NetworkLocation(refType: 'ComputeServer', refId: it.id) })
	}

	private long nicIds = 900L

	private ComputeServerInterface nic(Network network, String... addresses) {
		new ComputeServerInterface(id: nicIds++, network: network, addresses: addresses.collect { new NetAddress(address: it) })
	}

	private static ComputeServer host(Long id, String name, ComputeServerGroup cluster, Account account) {
		new ComputeServer(id: id, name: name, status: 'provisioned', powerState: ComputeServer.PowerState.on,
			serverGroup: cluster, account: account, computeServerType: new ComputeServerType(code: 'mvmHost'))
	}

	private static ComputeServer vm(Long id, String name, ComputeServer host, ComputeServer.PowerState power,
									Account account, ComputeServerType type = new ComputeServerType(code: 'mvmvm')) {
		def parent = new ComputeServerIdentityProjection(id: host.id, name: host.name)
		new ComputeServer(id: id, name: name, status: 'provisioned', powerState: power, parentServer: parent,
			account: account, computeServerType: type)
	}
}
