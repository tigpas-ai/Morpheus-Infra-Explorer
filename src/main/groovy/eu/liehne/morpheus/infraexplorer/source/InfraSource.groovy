package eu.liehne.morpheus.infraexplorer.source

import com.morpheusdata.model.User

/**
 * Supplies clusters, hosts and VMs of one cluster type. Further cluster types are added as further implementations;
 * the controller and the overlay do not depend on the type.
 *
 * All methods only return objects the given user may see. "Not found" and "not visible" are both reported as
 * {@code null}, so callers cannot tell them apart.
 */
interface InfraSource {

	/** Morpheus cluster type code handled by this source, e.g. {@code mvm-cluster} */
	String getClusterTypeCode()

	List<InfraNode> listClusters(User user)

	InfraNode findCluster(User user, Long clusterId)

	/** Hosts of a cluster, or {@code null} if the cluster is not visible or of another type */
	List<InfraNode> listHosts(User user, Long clusterId)

	/** Host including {@code clusterId}, or {@code null} if it does not belong to a visible cluster of this type */
	InfraNode findHost(User user, Long hostId)

	/** VMs running on a host, or {@code null} if the host is not visible */
	List<InfraNode> listVms(User user, Long hostId)

	/** All VMs below the visible clusters of this type, with {@code hostName}, {@code tenantId}, {@code tenant} and {@code tags} */
	List<InfraNode> listAllVms(User user)

	/** VM with host and instance, or {@code null} if it is not below a visible cluster of this type */
	InfraNode findVm(User user, Long serverId)

	/**
	 * Sets the folder path of a VM: one tag per level, deeper levels removed, other tags kept. Returns the VM, or
	 * {@code null} if it is not visible. The caller checks the permission to change the VM.
	 */
	InfraNode setFolders(User user, Long serverId, List<String> levels, List<String> path)

	/**
	 * Renames the folder {@code path} (outermost first) of a tenant for all visible VMs below it, at its own level.
	 * Returns the changed VMs (empty if none matched).
	 * @throws IllegalStateException (message for the user) if more than {@code maxVms} VMs are below the folder
	 */
	List<InfraNode> renameFolder(User user, Long tenantId, List<String> levels, List<String> path, String newName,
								 int maxVms)

	/**
	 * Renames tags (old name → new name, value kept) on all visible VMs that carry one of the old names. VMs that
	 * already carry a new name, or changed in between, are skipped.
	 * @return [changed: n, skipped: n]
	 * @throws IllegalStateException (message for the user) if more than {@code maxVms} VMs carry the tags
	 */
	Map<String, Integer> renameTags(User user, Map<String, String> renames, int maxVms)

	/** Datastores of a cluster, or {@code null} if the cluster is not visible or of another type */
	List<InfraNode> listDatastores(User user, Long clusterId)

	/**
	 * Networks of a cluster with the VMs of the cluster connected to them ({@code vms}, each VM once per network with
	 * its {@code ipAddresses} there, and {@code vmCount}), sorted by name. A network belongs to the cluster if one of its
	 * hosts carries it or a VM of the cluster is connected to it. VMs with an interface outside any visible network
	 * are collected in one extra node with {@code id == null} at the end. {@code null} if the cluster is not visible
	 * or of another type.
	 */
	List<InfraNode> listNetworks(User user, Long clusterId)
}
