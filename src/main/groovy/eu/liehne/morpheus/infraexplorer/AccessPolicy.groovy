package eu.liehne.morpheus.infraexplorer

import com.morpheusdata.model.Account
import com.morpheusdata.model.User
import eu.liehne.morpheus.infraexplorer.source.InfraNode

/**
 * Access checks for the signed-in user.
 *
 * Role checks use the Morpheus feature permissions ({@code user.permissions}: code → highest access level
 * none/read/full). The tenant check is applied by the sources on top of the user-scoped queries, because
 * {@code DataQuery(user)} is not guaranteed to restrict by resource permissions. All checks fail closed: missing
 * data means no access.
 */
class AccessPolicy {

	/** Infrastructure → Clusters */
	static final String CLUSTERS = 'infrastructure-cluster'
	/** Infrastructure → Compute (hosts and VMs) */
	static final String SERVERS = 'admin-servers'
	/** Infrastructure → Storage */
	static final String STORAGE = 'infrastructure-storage'
	/** Infrastructure → Networks */
	static final String NETWORKS = 'infrastructure-networks'

	/** Power Control (start, stop, restart): none, user (own workloads), full */
	static final String POWER = 'provisioning-power'
	/** Reconfigure: none, user (own workloads), full */
	static final String RECONFIGURE = 'provisioning-reconfigure'
	/** Administration → Plugins (the level names are the plugin setting folderTags) */
	static final String PLUGINS = 'admin-plugins'
	/** Instances: Add */
	static final String ADD_INSTANCES = 'provisioning-add'
	/** Remote Console: none, provisioned (own workloads), full */
	static final String CONSOLE = 'terminal'

	static final String ACTION_CONSOLE = 'console'
	static final String ACTION_POWER = 'power'
	static final String ACTION_RECONFIGURE = 'reconfigure'
	static final String ACTION_FOLDER = 'folder'
	static final String ACTION_CONVERT = 'convert'
	static final String ACTION_REFRESH = 'refresh'
	static final String ACTION_EDIT = 'edit'
	static final String ACTION_MAINTENANCE = 'maintenance'
	static final String ACTION_RESTART = 'restart'

	private static final Set<String> READ_LEVELS = ['read', 'full'] as Set

	boolean canRead(User user, String code) {
		return READ_LEVELS.contains(user?.permissions?.get(code))
	}

	boolean canViewClusters(User user) {
		return canRead(user, CLUSTERS)
	}

	/** Hosts and VMs are shown below clusters, so both permissions are needed. */
	boolean canViewServers(User user) {
		return canViewClusters(user) && canRead(user, SERVERS)
	}

	/** Datastores are shown per cluster, so both permissions are needed. */
	boolean canViewStorage(User user) {
		return canViewClusters(user) && canRead(user, STORAGE)
	}

	/** Networks are shown per cluster with the VMs connected to them, so all three permissions are needed. */
	boolean canViewNetworks(User user) {
		return canViewServers(user) && canRead(user, NETWORKS)
	}

	/**
	 * Actions offered for a VM. "full" allows the action on every visible VM; the levels limited to own workloads
	 * ("user", "provisioned") only on VMs whose server or instance the user created. Morpheus checks again when the
	 * action is executed, so this only decides what the tree offers.
	 */
	List<String> vmActions(User user, InfraNode vm) {
		if (user?.id == null || vm == null) {
			return []
		}
		boolean own = vm.createdById == user.id || vm.instanceCreatedById == user.id
		List<String> actions = []
		if (allows(user, CONSOLE, 'provisioned', own)) {
			actions << ACTION_CONSOLE
		}
		if (allows(user, POWER, 'user', own)) {
			actions << ACTION_POWER
		}
		if (allows(user, RECONFIGURE, 'user', own)) {
			actions << ACTION_RECONFIGURE
		}
		if (canEditFolders(user)) {
			actions << ACTION_FOLDER
		}
		// Converting a discovered VM changes the server (agent install), like editing it
		if (vm.managed == false && canEditFolders(user)) {
			actions << ACTION_CONVERT
		}
		return actions
	}

	/**
	 * Tenant in which the user may create instances (always the own one, Morpheus provisions in the session's tenant),
	 * or {@code null}.
	 */
	Long instanceCreateTenant(User user) {
		Long tenantId = user?.account?.id
		return tenantId != null && user.permissions?.get(ADD_INSTANCES) == 'full' ? tenantId : null
	}

	/**
	 * Renaming levels changes the plugin setting (Morpheus checks Plugins itself when it is saved) and, if wanted, the
	 * tags of the VMs: full access to Plugins and the right to change folders.
	 */
	boolean canEditLevels(User user) {
		return canEditFolders(user) && user.permissions?.get(PLUGINS) == 'full'
	}

	/** Cluster actions: refresh (short or daily) and edit need full access to Clusters */
	List<String> clusterActions(User user) {
		return user?.id != null && user.permissions?.get(CLUSTERS) == 'full' ? [ACTION_REFRESH, ACTION_EDIT] : []
	}

	/**
	 * Host actions: maintenance mode and edit change the host and need full access to Compute; restarting the
	 * hypervisor also needs full Power Control (no own-workload level for hosts).
	 */
	List<String> hostActions(User user) {
		if (user?.id == null || !canViewServers(user) || user.permissions?.get(SERVERS) != 'full') {
			return []
		}
		List<String> actions = [ACTION_MAINTENANCE, ACTION_EDIT]
		if (user.permissions?.get(POWER) == 'full') {
			actions << ACTION_RESTART
		}
		return actions
	}

	/** Folders are tags on the server; changing a server needs full access to Compute. */
	boolean canEditFolders(User user) {
		return canViewServers(user) && user.permissions?.get(SERVERS) == 'full'
	}

	private static boolean allows(User user, String code, String ownLevel, boolean own) {
		String level = user.permissions?.get(code)
		return level == 'full' || (level == ownLevel && own)
	}

	/**
	 * Users of the master tenant may see objects of every tenant (as far as Morpheus returns them); all other users
	 * only objects owned by their own tenant.
	 */
	boolean canSeeTenantObject(User user, Account owner) {
		return canSeeTenant(user, owner?.id)
	}

	/** Same check by the id of the owning tenant, for queries that only load the id */
	boolean canSeeTenant(User user, Long ownerId) {
		Account userAccount = user?.account
		if (userAccount?.id == null) {
			return false
		}
		if (userAccount.masterAccount) {
			return true
		}
		return ownerId != null && ownerId == userAccount.id
	}
}
