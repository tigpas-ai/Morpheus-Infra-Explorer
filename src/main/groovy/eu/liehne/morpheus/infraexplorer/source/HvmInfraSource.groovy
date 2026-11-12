package eu.liehne.morpheus.infraexplorer.source

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.MetadataTag
import com.morpheusdata.model.User
import eu.liehne.morpheus.infraexplorer.AccessPolicy
import eu.liehne.morpheus.infraexplorer.FolderSettings
import groovy.util.logging.Slf4j

/**
 * HVM clusters: hosts are servers whose serverGroup is the cluster (and that have no parent), VMs are servers whose
 * parentServer is the host. Discovered VMs have no instance. Datastores reference their cluster with
 * refType {@code ComputeServerGroup} and refId. Networks are tied to the hosts that carry them (network locations) and
 * to the VMs through their interfaces.
 *
 * Every query is scoped with {@code new DataQuery(user)} and only loads the properties needed ({@code propertyList}
 * with {@code listOptions}): loading complete model objects takes about half a second per server on a test appliance, the
 * property query a few milliseconds. Single objects are looked up by an id filter, never with {@code get(id)},
 * because {@code get} skips access control. On top of that, every returned object must pass the tenant check of
 * {@link AccessPolicy}, and hosts and VMs are only returned below a cluster the user can see.
 */
@Slf4j
class HvmInfraSource implements InfraSource {

	static final String CLUSTER_TYPE_CODE = 'mvm-cluster'
	static final String DATASTORE_REF_TYPE = 'ComputeServerGroup'
	/** Upper bound per query; listOptions is meant for dropdowns, so the limit is set explicitly */
	static final Long MAX_ROWS = 10000L

	static final List<String> CLUSTER_PROPS = ['id', 'name', 'status', 'type.name', 'owner.id']
	static final List<String> SERVER_PROPS = ['id', 'name', 'status', 'powerState', 'parentServer.id', 'serverGroup.id',
											  'account.id', 'account.name', 'computeServerType.code',
											  'computeServerType.managed', 'createdBy.id', 'maintenanceMode', 'maxCores', 'maxMemory',
											  'maxStorage']
	/** One row per disk of a server (left join: a server without disks gives one row with {@code null}) */
	static final List<String> DISK_PROPS = ['id', 'volumes.id', 'volumes.name', 'volumes.maxStorage', 'volumes.usedStorage', 'volumes.datastore.id',
										 'volumes.datastore.name']
	static final List<String> TAG_PROPS = ['id', 'metadata.name', 'metadata.value']
	static final List<String> WORKLOAD_PROPS = ['id', 'server.id', 'account.id', 'instance.id', 'instance.account.id',
												'instance.createdBy.id']
	static final List<String> DATASTORE_PROPS = ['id', 'name', 'status', 'active', 'type', 'datastoreType.name',
												 'storageSize', 'freeSpace', 'online', 'owner.id', 'refType', 'refId']
	/**
	 * One row per address of each interface of a server (left join: an interface without address gives one row with
	 * {@code null}). The interface has no address column of its own; {@code interfaces.ipAddress} empties the query.
	 */
	static final List<String> INTERFACE_PROPS = ['id', 'interfaces.id', 'interfaces.network.id', 'interfaces.name',
												 'interfaces.macAddress', 'interfaces.type.name', 'interfaces.primaryInterface',
												 'interfaces.dhcp', 'interfaces.addresses.address']
	static final List<String> NETWORK_PROPS = ['id', 'name', 'status', 'active', 'vlanId', 'cidr', 'cidrIPv6', 'type.name',
											   'owner.id', 'visibility']
	/** One row per location (host or cloud) of a network */
	static final List<String> NETWORK_LOCATION_PROPS = ['id', 'locations.refType', 'locations.refId']
	/** One row per subnet of a network */
	static final List<String> SUBNET_PROPS = ['id', 'subnets.id', 'subnets.name', 'subnets.cidr']
	static final String LOCATION_SERVER = 'ComputeServer'

	private final MorpheusContext morpheus
	private final AccessPolicy accessPolicy

	HvmInfraSource(MorpheusContext morpheus, AccessPolicy accessPolicy = new AccessPolicy()) {
		this.morpheus = morpheus
		this.accessPolicy = accessPolicy
	}

	@Override
	String getClusterTypeCode() {
		return CLUSTER_TYPE_CODE
	}

	@Override
	List<InfraNode> listClusters(User user) {
		return InfraNode.sortByName(visibleClusters(user).collect { toClusterNode(it) })
	}

	private List<Map> visibleClusters(User user) {
		DataQuery query = new DataQuery(user).withFilter('type.code', CLUSTER_TYPE_CODE)
		return rows(morpheus.services.cluster, query, CLUSTER_PROPS)
			.findAll { accessPolicy.canSeeTenant(user, it['owner.id'] as Long) }
	}

	@Override
	InfraNode findCluster(User user, Long clusterId) {
		if (clusterId == null) {
			return null
		}
		DataQuery query = new DataQuery(user).withFilter('id', clusterId).withFilter('type.code', CLUSTER_TYPE_CODE)
		Map cluster = rows(morpheus.services.cluster, query, CLUSTER_PROPS).find { (it.id as Long) == clusterId }
		if (cluster == null || !accessPolicy.canSeeTenant(user, cluster['owner.id'] as Long)) {
			return null
		}
		return toClusterNode(cluster)
	}

	@Override
	List<InfraNode> listHosts(User user, Long clusterId) {
		if (findCluster(user, clusterId) == null) {
			return null
		}
		List<Map> hosts = servers(user, new DataQuery(user).withFilter('serverGroup.id', clusterId))
			.findAll { (it['serverGroup.id'] as Long) == clusterId && isHost(user, it) }
		return InfraNode.sortByName(hosts.collect { toHostNode(it, clusterId) })
	}

	@Override
	InfraNode findHost(User user, Long hostId) {
		if (hostId == null) {
			return null
		}
		Map host = servers(user, new DataQuery(user).withFilter('id', hostId)).find { (it.id as Long) == hostId }
		if (host == null || !isHost(user, host)) {
			return null
		}
		Long clusterId = host['serverGroup.id'] as Long
		if (findCluster(user, clusterId) == null) {
			return null
		}
		return toHostNode(host, clusterId)
	}

	@Override
	List<InfraNode> listVms(User user, Long hostId) {
		if (findHost(user, hostId) == null) {
			return null
		}
		List<Map> vms = servers(user, new DataQuery(user).withFilter('parentServer.id', hostId))
			.findAll { (it['parentServer.id'] as Long) == hostId && accessPolicy.canSeeTenant(user, it['account.id'] as Long) }
		Map<Long, Map> instances = instancesByServer(user, vms.collect { it.id as Long })
		Map<Long, List<Map<String, Object>>> disks = disksByServer(user, vms.collect { it.id as Long })
		return InfraNode.sortByName(vms.collect { toVmNode(it, hostId, instances[it.id as Long], disks[it.id as Long]) })
	}

	@Override
	InfraNode findVm(User user, Long serverId) {
		if (serverId == null) {
			return null
		}
		Map vm = servers(user, new DataQuery(user).withFilter('id', serverId)).find { (it.id as Long) == serverId }
		Long hostId = vm?.get('parentServer.id') as Long
		if (hostId == null || !accessPolicy.canSeeTenant(user, vm['account.id'] as Long) || findHost(user, hostId) == null) {
			return null
		}
		InfraNode node = toVmNode(vm, hostId, instancesByServer(user, [serverId])[serverId])
		node.tenantId = vm['account.id'] as Long
		node.tenant = tenantName(user, node.tenantId, vm['account.name'] as String)
		return node
	}

	/**
	 * Loads the complete server (the only full model query: saving needs it) and saves it with the changed tag list.
	 * The model only writes changed properties, here {@code metadata}.
	 */
	@Override
	InfraNode setFolders(User user, Long serverId, List<String> levels, List<String> path) {
		InfraNode vm = findVm(user, serverId)
		return vm == null ? null : writeFolders(user, vm, levels, path)
	}

	/**
	 * Renames one folder for all VMs below it. The candidates come from {@link #listAllVms}, so only visible VMs are
	 * changed. Each server is loaded again and only changed if it still belongs to the tenant and its current tags
	 * still start with the path; then only the tag of the folder's level is replaced, deeper tags stay as they are.
	 * Skipped VMs are not returned.
	 */
	@Override
	List<InfraNode> renameFolder(User user, Long tenantId, List<String> levels, List<String> path, String newName,
								 int maxVms) {
		String levelTag = levels[path.size() - 1]
		List<InfraNode> below = listAllVms(user).findAll { InfraNode vm ->
			vm.tenantId == tenantId && FolderSettings.folderPath(vm, levels).take(path.size()) == path
		}
		if (below.size() > maxVms) {
			throw new IllegalStateException("the folder contains ${below.size()} VMs, at most ${maxVms} are renamed at once".toString())
		}
		return below.collect { InfraNode vm ->
			ComputeServer server = loadChecked(user, vm)
			if (server == null || (server.account?.id as Long) != tenantId) {
				return null
			}
			InfraNode current = new InfraNode(tags: tagMap(server.metadata))
			if (FolderSettings.folderPath(current, levels).take(path.size()) != path) {
				return null
			}
			List<MetadataTag> tags = (server.metadata ?: []).findAll { it?.name != levelTag }
			tags << new MetadataTag(name: levelTag, value: newName)
			server.setMetadata(tags)
			morpheus.services.computeServer.save(server)
			current.tags[levelTag] = newName
			vm.tags = current.tags
			vm
		}.findAll { it != null }
	}

	@Override
	Map<String, Integer> renameTags(User user, Map<String, String> renames, int maxVms) {
		List<InfraNode> carriers = listAllVms(user).findAll { InfraNode vm -> vm.tags?.keySet()?.any { it in renames } }
		if (carriers.size() > maxVms) {
			throw new IllegalStateException("${carriers.size()} VMs carry these tags, at most ${maxVms} are renamed at once".toString())
		}
		int changed = 0
		int skipped = 0
		carriers.each { InfraNode vm ->
			ComputeServer server = loadChecked(user, vm)
			List<MetadataTag> metadata = server?.metadata ?: []
			Set<String> present = metadata*.name as Set
			if (server == null || !present.any { it in renames } || renames.values().any { it in present }) {
				skipped++
				return
			}
			List<MetadataTag> tags = metadata.findAll { !(it?.name in renames) }
			metadata.findAll { it?.name in renames }.each { MetadataTag tag ->
				tags << new MetadataTag(name: renames[tag.name], value: tag.value)
			}
			server.setMetadata(tags)
			morpheus.services.computeServer.save(server)
			changed++
		}
		return [changed: changed, skipped: skipped]
	}

	/**
	 * Loads the complete server of a checked VM for writing (about half a second per server). It must still be the
	 * checked VM (same host, visible tenant), otherwise {@code null}.
	 */
	private ComputeServer loadChecked(User user, InfraNode vm) {
		ComputeServer server = morpheus.services.computeServer.find(new DataQuery(user).withFilter('id', vm.id))
		if (server == null || (server.id as Long) != vm.id || (server.parentServer?.id as Long) != vm.hostId ||
			!accessPolicy.canSeeTenant(user, server.account?.id as Long)) {
			return null
		}
		return server
	}

	/** Tag name → value of model tags; for duplicate names the first tag wins, like in the tree */
	private static Map<String, String> tagMap(List<MetadataTag> metadata) {
		Map<String, String> tags = [:]
		metadata?.each { MetadataTag tag ->
			if (tag?.name) {
				tags.putIfAbsent(tag.name, tag.value)
			}
		}
		return tags
	}

	/** Writes the folder path of a checked VM: level tags replaced, deeper levels removed, other tags kept */
	private InfraNode writeFolders(User user, InfraNode vm, List<String> levels, List<String> path) {
		ComputeServer server = loadChecked(user, vm)
		if (server == null) {
			return null
		}
		List<MetadataTag> tags = (server.metadata ?: []).findAll { !(it?.name in levels) }
		path.eachWithIndex { String value, int i -> tags << new MetadataTag(name: levels[i], value: value) }
		server.setMetadata(tags)
		morpheus.services.computeServer.save(server)
		vm.tags = path.withIndex().collectEntries { String value, int i -> [(levels[i]): value] }
		return vm
	}

	/** Five queries regardless of the number of hosts: clusters, their hosts, the hosts' VMs, tags, workloads. */
	@Override
	List<InfraNode> listAllVms(User user) {
		List<Long> clusterIds = visibleClusters(user).collect { it.id as Long }
		if (!clusterIds) {
			return []
		}
		List<Map> hosts = servers(user, new DataQuery(user).withFilter('serverGroup.id', 'in', clusterIds))
			.findAll { (it['serverGroup.id'] as Long) in clusterIds && isHost(user, it) }
		Map<Long, Map> hostsById = hosts.collectEntries { [(it.id as Long): it] }
		if (!hostsById) {
			return []
		}
		List<Map> vms = servers(user, new DataQuery(user).withFilter('parentServer.id', 'in', hostsById.keySet() as List))
			.findAll {
				hostsById.containsKey(it['parentServer.id'] as Long) &&
					accessPolicy.canSeeTenant(user, it['account.id'] as Long)
			}
		List<Long> vmIds = vms.collect { it.id as Long }
		Map<Long, Map<String, String>> tags = tagsByServer(user, vmIds)
		Map<Long, Map> instances = instancesByServer(user, vmIds)
		Map<Long, List<Map<String, Object>>> disks = disksByServer(user, vmIds)
		return InfraNode.sortByName(vms.collect { Map vm ->
			Long id = vm.id as Long
			Long hostId = vm['parentServer.id'] as Long
			InfraNode node = toVmNode(vm, hostId, instances[id], disks[id])
			node.hostName = hostsById[hostId].name
			node.tenantId = vm['account.id'] as Long
			node.tenant = tenantName(user, node.tenantId, vm['account.name'] as String)
			node.tags = tags[id] ?: [:]
			node
		})
	}

	/** Name of the tenant; the user's own tenant is complete in the session even if the row carries no name */
	private static String tenantName(User user, Long accountId, String name) {
		if (accountId == null) {
			return null
		}
		if (name) {
			return name
		}
		if (accountId == user.account?.id && user.account.name) {
			return user.account.name
		}
		return "Tenant ${accountId}".toString()
	}

	/** Tag name → value per server; one row per tag, for duplicate names the first tag wins */
	private Map<Long, Map<String, String>> tagsByServer(User user, List<Long> serverIds) {
		if (!serverIds) {
			return [:]
		}
		DataQuery query = new DataQuery(user).withFilter('id', 'in', serverIds)
		Map<Long, Map<String, String>> result = [:]
		rows(morpheus.services.computeServer, query, TAG_PROPS).each { Map row ->
			Long id = row.id as Long
			String name = row['metadata.name']
			if (id in serverIds && name) {
				result.computeIfAbsent(id) { [:] }.putIfAbsent(name, row['metadata.value'] as String)
			}
		}
		return result
	}

	/** Disks per server as {@code {name, size, used, storage, storageId}} (size in bytes), in the order of their ids; servers without disks are missing */
	private Map<Long, List<Map<String, Object>>> disksByServer(User user, List<Long> serverIds) {
		if (!serverIds) {
			return [:]
		}
		DataQuery query = new DataQuery(user).withFilter('id', 'in', serverIds)
		Set<Long> wanted = serverIds as Set
		Map<Long, Map<Long, Map<String, Object>>> byServer = [:]
		rows(morpheus.services.computeServer, query, DISK_PROPS).each { Map row ->
			Long id = row.id as Long
			Long diskId = row['volumes.id'] as Long
			if (!(id in wanted) || diskId == null) {
				return
			}
			byServer.computeIfAbsent(id) { new TreeMap<Long, Map<String, Object>>() }
				.putIfAbsent(diskId, [name: row['volumes.name'] ?: '', size: (row['volumes.maxStorage'] as Long) ?: 0L,
										  used: row['volumes.usedStorage'] as Long,
										  storageId: row['volumes.datastore.id'] as Long,
										  storage: row['volumes.datastore.name'] ?: null].findAll { it.value != null })
		}
		return byServer.collectEntries { Long id, Map<Long, Map<String, Object>> disks -> [(id): disks.values() as List] }
	}

	@Override
	List<InfraNode> listDatastores(User user, Long clusterId) {
		if (findCluster(user, clusterId) == null) {
			return null
		}
		DataQuery query = new DataQuery(user)
			.withFilter('refType', DATASTORE_REF_TYPE)
			.withFilter('refId', clusterId)
		List<Map> datastores = rows(morpheus.services.cloud.datastore, query, DATASTORE_PROPS)
			.findAll {
				it.refType == DATASTORE_REF_TYPE && (it.refId as Long) == clusterId &&
					accessPolicy.canSeeTenant(user, it['owner.id'] as Long)
			}
		return InfraNode.sortByName(datastores.collect { toDatastoreNode(it) })
	}

	@Override
	Map<Long, List<InfraNode>> listDatastoreVms(User user, Long clusterId) {
		if (findCluster(user, clusterId) == null) {
			return null
		}
		List<Map> hosts = servers(user, new DataQuery(user).withFilter('serverGroup.id', clusterId))
			.findAll { (it['serverGroup.id'] as Long) == clusterId && isHost(user, it) }
		Map<Long, Map> hostsById = hosts.collectEntries { [(it.id as Long): it] }
		if (!hostsById) {
			return [:]
		}
		List<Map> vms = servers(user, new DataQuery(user).withFilter('parentServer.id', 'in', hostsById.keySet() as List))
			.findAll {
				hostsById.containsKey(it['parentServer.id'] as Long) &&
					accessPolicy.canSeeTenant(user, it['account.id'] as Long)
			}
		List<Long> vmIds = vms.collect { it.id as Long }
		Map<Long, Map> instances = instancesByServer(user, vmIds)
		Map<Long, List<Map<String, Object>>> disks = disksByServer(user, vmIds)
		Map<Long, List<InfraNode>> result = [:]
		vms.each { Map vm ->
			Long id = vm.id as Long
			Long hostId = vm['parentServer.id'] as Long
			// One entry per datastore, with only the disks that lie there
			(disks[id] ?: []).findAll { it.storageId != null }.groupBy { it.storageId as Long }.each { Long storeId, List<Map<String, Object>> onStore ->
				InfraNode node = toVmNode(vm, hostId, instances[id])
				node.hostName = hostsById[hostId].name
				node.cores = null
				node.memory = null
				node.disks = onStore
				node.storage = onStore.sum { (it.size as Long) ?: 0L } as Long ?: null
				result.computeIfAbsent(storeId) { [] } << node
			}
		}
		return result.collectEntries { Long storeId, List<InfraNode> nodes -> [(storeId): InfraNode.sortByName(nodes)] }
	}

	/**
	 * Hosts and VMs of the cluster, the VMs' interfaces, all visible networks with their locations and subnets, and the
	 * VMs' instances: eight queries regardless of the size of the cluster.
	 */
	@Override
	List<InfraNode> listNetworks(User user, Long clusterId) {
		if (findCluster(user, clusterId) == null) {
			return null
		}
		List<Map> hosts = servers(user, new DataQuery(user).withFilter('serverGroup.id', clusterId))
			.findAll { (it['serverGroup.id'] as Long) == clusterId && isHost(user, it) }
		Map<Long, Map> hostsById = hosts.collectEntries { [(it.id as Long): it] }
		List<Map> vms = !hostsById ? [] : servers(user, new DataQuery(user).withFilter('parentServer.id', 'in', hostsById.keySet() as List))
			.findAll {
				hostsById.containsKey(it['parentServer.id'] as Long) &&
					accessPolicy.canSeeTenant(user, it['account.id'] as Long)
			}
		List<Long> vmIds = vms.collect { it.id as Long }
		// server id → network id (null: interface without network) → interfaces
		Map<Long, Map<Long, List<Map<String, Object>>>> attached = interfacesByServer(user, vmIds)

		Map<Long, Map> networks = visibleNetworks(user)
		Set<Long> carried = networksOnHosts(user, networks.keySet() as List, hostsById.keySet())
		Set<Long> used = attached.values().collectMany { it.keySet() }.findAll { it != null } as Set
		List<Long> networkIds = networks.keySet().findAll { it in carried || it in used } as List
		Map<Long, List<Map<String, Object>>> subnets = subnetsByNetwork(user, networkIds)
		Map<Long, Map> instances = instancesByServer(user, vmIds)

		Map<Long, List<InfraNode>> vmsByNetwork = [:]
		List<InfraNode> unattached = []
		vms.each { Map vm ->
			Long id = vm.id as Long
			Long hostId = vm['parentServer.id'] as Long
			Map<Long, List<Map<String, Object>>> byNetwork = attached[id] ?: [:]
			// Interfaces in networks that are not shown (none, or not visible) put the VM into the extra node once
			boolean outside = !byNetwork || byNetwork.keySet().any { !(it in networkIds) }
			byNetwork.findAll { it.key in networkIds }.each { Long networkId, List<Map<String, Object>> nics ->
				InfraNode node = toNetworkVmNode(vm, hostId, instances[id], nics)
				node.hostName = hostsById[hostId].name
				vmsByNetwork.computeIfAbsent(networkId) { [] } << node
			}
			if (outside) {
				InfraNode node = toNetworkVmNode(vm, hostId, instances[id],
					byNetwork.findAll { !(it.key in networkIds) }.values().flatten() as List<Map<String, Object>>)
				node.hostName = hostsById[hostId].name
				unattached << node
			}
		}
		List<InfraNode> result = InfraNode.sortByName(networkIds.collect { Long networkId ->
			InfraNode node = toNetworkNode(networks[networkId], subnets[networkId])
			node.clusterId = clusterId
			node.vms = InfraNode.sortByName(vmsByNetwork[networkId] ?: [])
			node.vmCount = node.vms.size()
			node
		})
		if (unattached) {
			result << new InfraNode(clusterId: clusterId, vms: InfraNode.sortByName(unattached), vmCount: unattached.size())
		}
		return result
	}

	/**
	 * Per server: network id → its interfaces there as {@code {name, macAddress, type, primary, dhcp, addresses}}, in
	 * the order of the interface ids. Interfaces without network are kept under the key {@code null}, so their VMs are
	 * not lost.
	 */
	private Map<Long, Map<Long, List<Map<String, Object>>>> interfacesByServer(User user, List<Long> serverIds) {
		if (!serverIds) {
			return [:]
		}
		DataQuery query = new DataQuery(user).withFilter('id', 'in', serverIds)
		// One row per address: collect them per interface
		Map<Long, Map<Long, Map<String, Object>>> byServer = [:]
		Map<Long, Long> networkOfInterface = [:]
		Set<Long> wanted = serverIds as Set
		rows(morpheus.services.computeServer, query, INTERFACE_PROPS).each { Map row ->
			Long id = row.id as Long
			Long nicId = row['interfaces.id'] as Long
			if (!(id in wanted) || nicId == null) {
				return
			}
			Map<String, Object> nic = byServer.computeIfAbsent(id) { new TreeMap<Long, Map<String, Object>>() }
				.computeIfAbsent(nicId) {
					networkOfInterface[nicId] = row['interfaces.network.id'] as Long
					[name: row['interfaces.name'] ?: null, macAddress: row['interfaces.macAddress'] ?: null,
					 type: row['interfaces.type.name'] ?: null, primary: row['interfaces.primaryInterface'] as Boolean,
					 dhcp: row['interfaces.dhcp'] as Boolean, addresses: new TreeSet<String>()]
				}
			if (row['interfaces.addresses.address']) {
				(nic.addresses as Set<String>) << row['interfaces.addresses.address'].toString()
			}
		}
		return byServer.collectEntries { Long id, Map<Long, Map<String, Object>> nics ->
			Map<Long, List<Map<String, Object>>> byNetwork = [:]
			nics.each { Long nicId, Map<String, Object> nic ->
				byNetwork.computeIfAbsent(networkOfInterface[nicId]) { [] } << (nic + [addresses: (nic.addresses as List)])
			}
			[(id): byNetwork]
		}
	}

	/**
	 * Networks the user may see, by id. Networks of another tenant count if they are shared publicly, as in Morpheus
	 * (only for users with a tenant, like every other check); inactive networks are left out. All networks of the
	 * appliance are read here (a few columns each): the cluster is not stored on the network.
	 */
	private Map<Long, Map> visibleNetworks(User user) {
		rows(morpheus.services.network, new DataQuery(user), NETWORK_PROPS)
			.findAll {
				it.active != false && (accessPolicy.canSeeTenant(user, it['owner.id'] as Long) ||
					(it.visibility?.toString() == 'public' && user?.account?.id != null))
			}
			.collectEntries { [(it.id as Long): it] }
	}

	/** Ids of the networks that one of the hosts carries */
	private Set<Long> networksOnHosts(User user, List<Long> networkIds, Set<Long> hostIds) {
		if (!networkIds || !hostIds) {
			return [] as Set
		}
		DataQuery query = new DataQuery(user).withFilter('id', 'in', networkIds)
		rows(morpheus.services.network, query, NETWORK_LOCATION_PROPS)
			.findAll {
				(it.id as Long) in networkIds && it['locations.refType'] == LOCATION_SERVER &&
					(it['locations.refId'] as Long) in hostIds
			}
			.collect { it.id as Long } as Set
	}

	/** Subnets (id, name, cidr) per network, sorted by name */
	private Map<Long, List<Map<String, Object>>> subnetsByNetwork(User user, List<Long> networkIds) {
		if (!networkIds) {
			return [:]
		}
		DataQuery query = new DataQuery(user).withFilter('id', 'in', networkIds)
		Map<Long, List<Map<String, Object>>> result = [:]
		rows(morpheus.services.network, query, SUBNET_PROPS).each { Map row ->
			Long id = row.id as Long
			if (id in networkIds && row['subnets.id'] != null) {
				Map<String, Object> subnet = [id: row['subnets.id'] as Long, name: row['subnets.name'], cidr: row['subnets.cidr']]
				result.computeIfAbsent(id) { [] } << subnet.findAll { it.value != null }
			}
		}
		result.values().each { list -> list.sort { a, b -> (a.name ?: '').toString().compareToIgnoreCase((b.name ?: '').toString()) } }
		return result
	}

	private List<Map> servers(User user, DataQuery query) {
		return rows(morpheus.services.computeServer, query, SERVER_PROPS)
	}

	/** Runs the query loading only the given properties; each row maps the property paths to their values. */
	private static List<Map> rows(service, DataQuery query, List<String> properties) {
		query.propertyList = properties
		query.max = MAX_ROWS
		List<Map> result = (service.listOptions(query) ?: []) as List<Map>
		if (result.size() >= MAX_ROWS) {
			log.warn("Infra Explorer: query for ${properties} reached ${MAX_ROWS} rows, the tree may be incomplete")
		}
		return result
	}

	private boolean isHost(User user, Map server) {
		server['parentServer.id'] == null && accessPolicy.canSeeTenant(user, server['account.id'] as Long)
	}

	/**
	 * Instance per VM as workload row (instance.id, instance.createdBy.id); servers with several visible workloads get
	 * the one with the lowest workload id.
	 */
	private Map<Long, Map> instancesByServer(User user, List<Long> serverIds) {
		if (!serverIds) {
			return [:]
		}
		DataQuery query = new DataQuery(user).withFilter('server.id', 'in', serverIds)
		List<Map> workloads = rows(morpheus.services.workload, query, WORKLOAD_PROPS)
			.findAll { Map w ->
				(w['server.id'] as Long) in serverIds && w['instance.id'] != null &&
					accessPolicy.canSeeTenant(user, (w['instance.account.id'] ?: w['account.id']) as Long)
			}
			.sort { it.id as Long }
		Map<Long, Map> result = [:]
		workloads.each { Map w -> result.putIfAbsent(w['server.id'] as Long, w) }
		return result
	}

	private static InfraNode toClusterNode(Map cluster) {
		new InfraNode(id: cluster.id as Long, name: cluster.name, status: cluster.status?.toString(),
			type: cluster['type.name'], url: "/infrastructure/clusters/${cluster.id}".toString())
	}

	private static InfraNode toHostNode(Map host, Long clusterId) {
		new InfraNode(id: host.id as Long, name: host.name, status: host.status?.toString(),
			powerState: host.powerState?.toString(), type: host['computeServerType.code'], clusterId: clusterId,
			maintenance: host.maintenanceMode as Boolean, url: serverUrl(host))
	}

	/** A VM below a network: no resources, but its interfaces there with their addresses */
	private static InfraNode toNetworkVmNode(Map vm, Long hostId, Map workload, List<Map<String, Object>> nics) {
		InfraNode node = toVmNode(vm, hostId, workload)
		node.cores = null
		node.memory = null
		node.storage = null
		node.interfaces = nics
		node.ipAddresses = nics.collectMany { it.addresses as List<String> }.unique().sort()
		return node
	}

	private static InfraNode toVmNode(Map vm, Long hostId, Map workload, List<Map<String, Object>> disks = null) {
		Long instanceId = workload?.get('instance.id') as Long
		// Configured sizes (not usage); without disks the total of the server is used. 0 means unknown.
		Long memory = (vm.maxMemory as Long) ?: null
		Long storage = disks ? disks.sum { (it.size as Long) ?: 0L } as Long : (vm.maxStorage as Long)
		new InfraNode(cores: (vm.maxCores as Integer) ?: null, memory: memory, storage: storage ?: null,
			disks: disks ?: null, id: vm.id as Long, name: vm.name, status: vm.status?.toString(),
			powerState: vm.powerState?.toString(), type: vm['computeServerType.code'], hostId: hostId,
			instanceId: instanceId, managed: vm['computeServerType.managed'] as Boolean, url: serverUrl(vm),
			instanceUrl: instanceId == null ? null : "/provisioning/instances/${instanceId}".toString(),
			createdById: vm['createdBy.id'] as Long, instanceCreatedById: workload?.get('instance.createdBy.id') as Long)
	}

	private static InfraNode toDatastoreNode(Map datastore) {
		String status = datastore.active == false ? 'inactive' : datastore.status?.toString()
		new InfraNode(id: datastore.id as Long, name: datastore.name, status: status,
			type: datastore['datastoreType.name'] ?: datastore.type, storageSize: datastore.storageSize as Long,
			freeSpace: datastore.freeSpace as Long, online: datastore.online as Boolean,
			url: "/infrastructure/storage/data-stores/${datastore.id}".toString())
	}

	private static InfraNode toNetworkNode(Map network, List<Map<String, Object>> subnets) {
		new InfraNode(id: network.id as Long, name: network.name, status: network.status?.toString(),
			type: network['type.name'], vlanId: network.vlanId as Integer, cidr: network.cidr ?: null,
			cidrIPv6: network.cidrIPv6 ?: null, subnets: subnets ?: null,
			url: "/infrastructure/networks/${network.id}".toString())
	}

	/** Detail page of hosts and VMs (managed or not) */
	private static String serverUrl(Map server) {
		"/infrastructure/servers/${server.id}".toString()
	}
}
