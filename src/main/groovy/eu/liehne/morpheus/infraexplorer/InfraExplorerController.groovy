package eu.liehne.morpheus.infraexplorer

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Permission
import com.morpheusdata.model.User
import com.morpheusdata.views.JsonResponse
import com.morpheusdata.views.ViewModel
import com.morpheusdata.web.PluginController
import com.morpheusdata.web.Route
import eu.liehne.morpheus.infraexplorer.source.InfraNode
import eu.liehne.morpheus.infraexplorer.source.InfraSource
import groovy.util.logging.Slf4j

/**
 * JSON routes for the overlay tree. Reachable in the browser under {@code /plugin/infra-explorer/...} with the
 * user's UI session. Each level is requested separately when a node is expanded.
 */
@Slf4j
class InfraExplorerController implements PluginController {

	static final String BASE = '/infra-explorer'
	/** Upper bound for one folder rename (about half a second per VM) */
	static final int MAX_RENAME_VMS = 100

	private final Plugin plugin
	private final MorpheusContext morpheus
	private final List<InfraSource> sources
	private final AccessPolicy accessPolicy
	private final FolderSettings folderSettings

	InfraExplorerController(Plugin plugin, MorpheusContext morpheus, List<InfraSource> sources,
							AccessPolicy accessPolicy = new AccessPolicy(),
							FolderSettings folderSettings = new FolderSettings(plugin, morpheus)) {
		this.plugin = plugin
		this.morpheus = morpheus
		this.sources = sources
		this.accessPolicy = accessPolicy
		this.folderSettings = folderSettings
	}

	@Override
	List<Route> getRoutes() {
		Permission clusters = Permission.build(AccessPolicy.CLUSTERS, 'read')
		[
			Route.build("${BASE}/clusters".toString(), 'clusters', clusters),
			Route.build("${BASE}/hosts".toString(), 'hosts', clusters),
			Route.build("${BASE}/vms".toString(), 'vms', clusters),
			Route.build("${BASE}/datastores".toString(), 'datastores', clusters),
			Route.build("${BASE}/networks".toString(), 'networks', clusters),
			Route.build("${BASE}/vm-folders".toString(), 'vmFolders', clusters),
			Route.build("${BASE}/vm-folder".toString(), 'vmFolder', clusters),
			Route.build("${BASE}/folder-rename".toString(), 'folderRename', clusters),
			Route.build("${BASE}/level-rename".toString(), 'levelRename', clusters)
		]
	}

	/** GET /plugin/infra-explorer/clusters */
	JsonResponse clusters(ViewModel model) {
		handle(model, accessPolicy.canViewClusters(model.user)) { User user ->
			List<InfraNode> clusters = sources.collectMany { it.listClusters(user) }
			List<String> actions = accessPolicy.clusterActions(user)
			clusters.each { it.actions = actions }
			[clusters: InfraNode.sortByName(clusters)*.toJson()]
		}
	}

	/** GET /plugin/infra-explorer/hosts?clusterId=… */
	JsonResponse hosts(ViewModel model) {
		handle(model, accessPolicy.canViewServers(model.user)) { User user ->
			Long clusterId = longParam(model, 'clusterId')
			List<InfraNode> hosts = firstFound { it.listHosts(user, clusterId) }
			List<String> actions = accessPolicy.hostActions(user)
			hosts?.each { it.actions = actions }
			hosts == null ? null : [clusterId: clusterId, hosts: hosts*.toJson()]
		}
	}

	/** GET /plugin/infra-explorer/vms?hostId=… */
	JsonResponse vms(ViewModel model) {
		handle(model, accessPolicy.canViewServers(model.user)) { User user ->
			Long hostId = longParam(model, 'hostId')
			List<InfraNode> vms = firstFound { it.listVms(user, hostId) }
			vms?.each { it.actions = accessPolicy.vmActions(user, it) }
			vms == null ? null : [hostId: hostId, vms: vms*.toJson()]
		}
	}

	/** GET /plugin/infra-explorer/datastores?clusterId=… */
	JsonResponse datastores(ViewModel model) {
		handle(model, accessPolicy.canViewStorage(model.user)) { User user ->
			Long clusterId = longParam(model, 'clusterId')
			List<InfraNode> datastores = firstFound { it.listDatastores(user, clusterId) }
			datastores == null ? null : [clusterId: clusterId, datastores: datastores*.toJson()]
		}
	}

	/**
	 * GET /plugin/infra-explorer/networks?clusterId=… – networks of a cluster with their VMs; VMs outside any
	 * visible network in {@code withoutNetwork}
	 */
	JsonResponse networks(ViewModel model) {
		handle(model, accessPolicy.canViewNetworks(model.user)) { User user ->
			Long clusterId = longParam(model, 'clusterId')
			List<InfraNode> networks = firstFound { it.listNetworks(user, clusterId) }
			if (networks == null) {
				return null
			}
			networks.each { network -> network.vms?.each { it.actions = accessPolicy.vmActions(user, it) } }
			InfraNode without = networks.find { it.id == null }
			Map body = [clusterId: clusterId, networks: networks.findAll { it.id != null }*.toJson()]
			if (without) {
				body.withoutNetwork = without.vms*.toJson()
			}
			body
		}
	}

	/** GET /plugin/infra-explorer/vm-folders – all visible VMs with host and folder path */
	JsonResponse vmFolders(ViewModel model) {
		handle(model, accessPolicy.canViewServers(model.user)) { User user ->
			List<String> levels = folderSettings.tagNames()
			List<InfraNode> vms = InfraNode.sortByName(sources.collectMany { it.listAllVms(user) })
			vms.each {
				it.folders = FolderSettings.folderPath(it, levels)
				it.actions = accessPolicy.vmActions(user, it)
			}
			Map body = [levels: levels, vms: vms*.toJson()]
			if (accessPolicy.canEditLevels(user)) {
				body.levelsEditable = true
			}
			Long createTenant = accessPolicy.instanceCreateTenant(user)
			if (createTenant != null) {
				body.create = [tenantId: createTenant]
			}
			body
		}
	}

	/**
	 * POST /plugin/infra-explorer/vm-folder – serverId, path (repeated, outermost first; none = no folder).
	 * Only POST, so the change is covered by the CSRF check of Morpheus. Read and write are not atomic: a tag change
	 * made by someone else at the same moment can be overwritten (accepted, see CONTRACT.md).
	 */
	JsonResponse vmFolder(ViewModel model) {
		if (model.request?.method != 'POST') {
			model.response?.setCharacterEncoding('UTF-8')
			return error(model, 405, 'method not allowed')
		}
		handle(model, accessPolicy.canEditFolders(model.user)) { User user ->
			Long serverId = longParam(model, 'serverId')
			List<String> levels = folderSettings.tagNames()
			List<String> path
			try {
				path = FolderSettings.cleanPath(model.request.getParameterValues('path') as List<String>, levels)
			} catch (IllegalArgumentException e) {
				throw new BadParameterException(e.message)
			}
			InfraNode vm = firstFound { it.setFolders(user, serverId, levels, path) }
			if (vm == null) {
				return null
			}
			log.info("Infra Explorer: user ${user.id} set folder path of server ${serverId} to ${path}")
			vm.folders = FolderSettings.folderPath(vm, levels)
			vm.actions = accessPolicy.vmActions(user, vm)
			[levels: levels, vm: vm.toJson()]
		}
	}

	/**
	 * POST /plugin/infra-explorer/folder-rename – tenantId, path (repeated, folder to rename, outermost first), name.
	 * Changes every visible VM below the folder; 404 if there is none (or none could be written). Each VM costs a full
	 * model query and a save, so folders with more than {@link #MAX_RENAME_VMS} VMs are refused.
	 */
	JsonResponse folderRename(ViewModel model) {
		if (model.request?.method != 'POST') {
			model.response?.setCharacterEncoding('UTF-8')
			return error(model, 405, 'method not allowed')
		}
		handle(model, accessPolicy.canEditFolders(model.user)) { User user ->
			Long tenantId = longParam(model, 'tenantId')
			List<String> levels = folderSettings.tagNames()
			List<String> path
			String name
			try {
				path = FolderSettings.cleanPath(model.request.getParameterValues('path') as List<String>, levels)
				List<String> names = FolderSettings.cleanPath([model.request.getParameter('name')], levels)
				if (!path || !names) {
					throw new IllegalArgumentException(path ? 'the new name must not be empty' : 'folder missing')
				}
				name = names[0]
				if (name == path.last()) {
					throw new IllegalArgumentException('the new name is the same as the current one')
				}
			} catch (IllegalArgumentException e) {
				throw new BadParameterException(e.message)
			}
			List<InfraNode> changed
			try {
				changed = sources.collectMany { it.renameFolder(user, tenantId, levels, path, name, MAX_RENAME_VMS) }
			} catch (IllegalStateException e) {
				throw new BadParameterException(e.message)
			}
			if (!changed) {
				return null
			}
			List<String> renamed = path.take(path.size() - 1) + [name]
			log.info("Infra Explorer: user ${user.id} renamed folder ${path} of tenant ${tenantId} to ${renamed} " +
				"(${changed.size()} VMs)")
			[levels: levels, path: renamed, changed: changed.size()]
		}
	}

	/**
	 * POST /plugin/infra-explorer/level-rename – from, to (repeated, same count): renames the tags of the levels on
	 * all visible VMs before the overlay saves the new level names in the plugin setting. {@code from} must be a
	 * configured level, {@code to} a new name that is no other level.
	 */
	JsonResponse levelRename(ViewModel model) {
		if (model.request?.method != 'POST') {
			model.response?.setCharacterEncoding('UTF-8')
			return error(model, 405, 'method not allowed')
		}
		handle(model, accessPolicy.canEditLevels(model.user)) { User user ->
			List<String> levels = folderSettings.tagNames()
			List<String> from = (model.request.getParameterValues('from') ?: []) as List<String>
			List<String> to = (model.request.getParameterValues('to') ?: []) as List<String>
			Map<String, String> renames = [:]
			try {
				if (!from || from.size() != to.size()) {
					throw new IllegalArgumentException('levels missing')
				}
				from.eachWithIndex { String old, int i ->
					String name = FolderSettings.cleanLevelName(to[i])
					if (!(old in levels)) {
						throw new IllegalArgumentException("unknown level ${old}".toString())
					}
					if (name != old) {
						renames[old] = name
					}
				}
				if (renames.values().any { it in levels } || renames.values().toSet().size() != renames.size()) {
					throw new IllegalArgumentException('new level names must be distinct and must not be an existing level')
				}
				if (!renames) {
					throw new IllegalArgumentException('no level name changed')
				}
			} catch (IllegalArgumentException e) {
				throw new BadParameterException(e.message)
			}
			Map<String, Integer> result = [changed: 0, skipped: 0]
			try {
				sources.each { InfraSource source ->
					source.renameTags(user, renames, MAX_RENAME_VMS).each { k, v -> result[k] += v }
				}
			} catch (IllegalStateException e) {
				throw new BadParameterException(e.message)
			}
			log.info("Infra Explorer: user ${user.id} renamed level tags ${renames} (${result})")
			[renames: renames, changed: result.changed, skipped: result.skipped]
		}
	}

	/** Asks the sources in turn; the first one that knows the object answers. */
	private <T> T firstFound(Closure<T> query) {
		for (InfraSource source : sources) {
			T result = query.call(source)
			if (result != null) {
				return result
			}
		}
		return null
	}

	/**
	 * Common frame: 403 without permission, 404 if the action returns null (missing or not visible), 500 with a
	 * generic message on errors (details only in the log).
	 */
	private JsonResponse handle(ViewModel model, boolean allowed, Closure<Map> action) {
		// Morpheus would otherwise send ISO-8859-1 and lose characters outside Latin-1 (e.g. in folder names)
		model.response?.setCharacterEncoding('UTF-8')
		if (model.user == null || !allowed) {
			return error(model, 403, 'forbidden')
		}
		try {
			Map body = action.call(model.user)
			return body == null ? error(model, 404, 'not found') : JsonResponse.of(body)
		} catch (BadParameterException e) {
			return error(model, 400, e.message)
		} catch (Exception e) {
			log.error("Infra Explorer: request failed", e)
			return error(model, 500, 'internal error')
		}
	}

	private static JsonResponse error(ViewModel model, int status, String message) {
		JsonResponse response = JsonResponse.of([error: message])
		response.status = status
		model.response?.setStatus(status)
		return response
	}

	private static Long longParam(ViewModel model, String name) {
		String value = model.request?.getParameter(name)
		if (!value?.isLong()) {
			throw new BadParameterException("parameter ${name} must be a number")
		}
		return value.toLong()
	}

	/** Invalid request parameter; the message is safe to show to the user. */
	static class BadParameterException extends RuntimeException {
		BadParameterException(String message) {
			super(message)
		}
	}

	@Override
	String getCode() {
		return 'infra-explorer.controller'
	}

	@Override
	String getName() {
		return 'Infra Explorer Controller'
	}

	@Override
	MorpheusContext getMorpheus() {
		return morpheus
	}

	@Override
	Plugin getPlugin() {
		return plugin
	}
}
