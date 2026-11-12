package eu.liehne.morpheus.infraexplorer.source

import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString

/**
 * One node of the infrastructure tree (cluster, host, VM, datastore or network), independent of the cluster type.
 * Field set and JSON names are part of CONTRACT.md.
 */
@ToString(includeNames = true, ignoreNulls = true)
@EqualsAndHashCode
class InfraNode {
	Long id
	String name
	/** Morpheus status of the object, e.g. "ok" for clusters, "provisioned" for servers */
	String status
	/** on, off, paused, unknown – hosts and VMs only */
	String powerState
	/** Display name of the cluster type, code of the server type or display name of the datastore type */
	String type
	/** Cluster of a host */
	Long clusterId
	/** Host of a VM */
	Long hostId
	/** Instance of a managed VM; null for discovered VMs */
	Long instanceId
	/** VMs only: false for discovered/unmanaged VMs */
	Boolean managed
	/** Morpheus UI page of the object (path relative to the appliance) */
	String url
	/** VM view only: name of the host the VM runs on */
	String hostName
	/** VM view only: tenant (Morpheus account) the VM belongs to */
	Long tenantId
	String tenant
	/** VM view only: folder path from the configured folder tags, outermost first; empty without folder */
	List<String> folders
	/** Tags of the object (name → value); internal, not part of the JSON */
	Map<String, String> tags
	/** VMs only: user who created the server or its instance; internal, for permissions limited to own workloads */
	Long createdById
	Long instanceCreatedById
	/** Actions the user may start from the tree: VMs console, power, …; hosts maintenance, …; clusters refresh, edit */
	List<String> actions
	/** Hosts only: in maintenance mode */
	Boolean maintenance
	/** VMs with instance only: Morpheus UI page of the instance */
	String instanceUrl

	/** VMs only: configured (not used) resources – vCPUs, memory and total disk size in bytes, the disks as {name, size} */
	Integer cores
	Long memory
	Long storage
	List<Map<String, Object>> disks

	/** Datastores only: capacity and free space in bytes, availability */
	Long storageSize
	Long freeSpace
	Boolean online

	/** Networks only: VLAN, IP networks (CIDR) of the network and its subnets, VMs connected to it and their number */
	Integer vlanId
	String cidr
	String cidrIPv6
	List<Map<String, Object>> subnets
	List<InfraNode> vms
	Integer vmCount
	/** VMs below a network only: addresses of the VM's interfaces in this network */
	List<String> ipAddresses
	/** VMs below a network only: the interfaces in this network as {name, macAddress, type, primary, dhcp, addresses} */
	List<Map<String, Object>> interfaces

	Map<String, Object> toJson() {
		[id: id, name: name, status: status, powerState: powerState, type: type,
		 clusterId: clusterId, hostId: hostId, instanceId: instanceId, managed: managed, url: url, storageSize: storageSize, freeSpace: freeSpace,
		 online: online, hostName: hostName, tenantId: tenantId, tenant: tenant, folders: folders, instanceUrl: instanceUrl,
		 maintenance: maintenance, actions: actions, vlanId: vlanId, cidr: cidr, cidrIPv6: cidrIPv6, subnets: subnets,
		 vmCount: vmCount, vms: vms?.collect { it.toJson() }, ipAddresses: ipAddresses, cores: cores, memory: memory,
		 storage: storage, disks: disks, interfaces: interfaces].findAll { it.value != null }
	}

	/** Sorts in place by name, case-insensitive; nodes without name first. */
	static List<InfraNode> sortByName(List<InfraNode> nodes) {
		nodes.sort { a, b -> (a.name ?: '').compareToIgnoreCase(b.name ?: '') }
	}
}
