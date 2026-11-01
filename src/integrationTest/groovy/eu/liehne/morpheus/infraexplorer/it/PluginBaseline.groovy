package eu.liehne.morpheus.infraexplorer.it

import java.util.jar.JarFile

/** Code and version from the manifest of a plugin jar. */
class PluginJar {
	final File file
	final String code
	final String version

	PluginJar(File file) {
		this.file = file
		JarFile jar = new JarFile(file)
		try {
			def attributes = jar.manifest?.mainAttributes
			this.code = attributes?.getValue('Morpheus-Code')
			this.version = attributes?.getValue('Plugin-Version')
		} finally {
			jar.close()
		}
	}

	@Override
	String toString() {
		"$code $version (${file.name})"
	}
}

/** The state cannot be restored safely – raised before anything is changed. */
class PreconditionException extends RuntimeException {
	PreconditionException(String message) {
		super(message)
	}
}

/** Plugin operations the baseline needs; implemented with the API, replaced by a fake in unit tests. */
interface PluginOperations {
	/** The plugin with this code ({@code id, code, version, status, enabled, config}) or null. */
	Map find(String code)

	/**
	 * Uploads the jar and waits until this upload is installed (also when the same version was installed before):
	 * the plugin has the version of the jar, a final status and a changed {@code lastUpdated}. Returns the plugin;
	 * fails on status {@code error} or after the timeout.
	 */
	Map install(PluginJar jar, int timeoutSeconds)

	void update(Object id, Map plugin)

	void delete(Object id)

	/** Waits until no plugin with this code is listed. */
	void waitForAbsence(String code, int timeoutSeconds)
}

/**
 * State of the plugin before the test (installed version, settings, enabled) and how to get back to it.
 * {@link #capture} fails with a {@link PreconditionException} if the state cannot be restored, e.g. because the jar
 * of the installed version is not available – the test then stops before any change.
 */
class PluginBaseline {

	final String code
	final boolean installed
	final Object id
	final String version
	final boolean enabled
	final Map<String, String> config
	final PluginJar restoreJar

	private PluginBaseline(String code, Map plugin, PluginJar restoreJar) {
		this.code = code
		this.installed = plugin != null
		this.id = plugin?.id
		this.version = plugin?.version
		this.enabled = plugin?.enabled != false
		this.config = normalize(plugin?.config)
		this.restoreJar = restoreJar
	}

	/**
	 * Reads the current state. {@code jarResolver} returns the jar of an installed version (or null if it is not
	 * available).
	 */
	static PluginBaseline capture(PluginOperations ops, String code, Closure<File> jarResolver) {
		Map plugin = ops.find(code)
		if (plugin == null) {
			return new PluginBaseline(code, null, null)
		}
		if (plugin.status != 'loaded') {
			throw new PreconditionException("plugin $code ${plugin.version} has status '${plugin.status}' " +
				'(expected loaded) – the previous state could not be restored reliably')
		}
		File file = jarResolver.call(plugin.version as String)
		if (file == null || !file.file) {
			throw new PreconditionException("no jar of the installed version $code ${plugin.version} available to " +
				'restore it afterwards – set INFRA_EXPLORER_RESTORE_JAR (see docs/integration-test.md)')
		}
		PluginJar jar = new PluginJar(file)
		if (jar.code != code || jar.version != plugin.version) {
			throw new PreconditionException("restore jar $jar does not match the installed version $code ${plugin.version}")
		}
		new PluginBaseline(code, plugin, jar)
	}

	/**
	 * Brings the plugin back to this state and verifies it. {@code uploaded}: a jar was uploaded during the test, so
	 * the installed code may differ even with the same version number. Returns the steps taken.
	 */
	List<String> restore(PluginOperations ops, boolean uploaded, int timeoutSeconds) {
		List<String> steps = []
		Map current = ops.find(code)
		if (!installed) {
			if (current != null) {
				ops.delete(current.id)
				ops.waitForAbsence(code, timeoutSeconds)
				steps << "removed $code ${current.version}"
			}
		} else {
			if (uploaded || current == null || current.version != version || current.status != 'loaded') {
				current = ops.install(restoreJar, timeoutSeconds)
				steps << "reinstalled $code $version from ${restoreJar.file.name}"
			}
			if (normalize(current.config) != config) {
				ops.update(current.id, [config: config])
				steps << "restored settings of $code"
			}
			if ((current.enabled != false) != enabled) {
				ops.update(current.id, [enabled: enabled])
				steps << "restored enabled=$enabled of $code"
			}
		}
		List<String> differences = differences(ops.find(code))
		if (differences) {
			throw new IllegalStateException("plugin $code was not restored: ${differences.join('; ')}")
		}
		steps
	}

	/** Differences between this state and a plugin as listed by the API (null = not installed). */
	List<String> differences(Map current) {
		if (!installed) {
			return current == null ? [] : ["installed (${current.version}) but was not before"]
		}
		if (current == null) {
			return ["not installed, expected $version"]
		}
		List<String> differences = []
		if (current.version != version) {
			differences << "version ${current.version}, expected $version"
		}
		if (current.status != 'loaded') {
			differences << "status ${current.status}, expected loaded"
		}
		if ((current.enabled != false) != enabled) {
			differences << "enabled ${current.enabled}, expected $enabled"
		}
		if (normalize(current.config) != config) {
			differences << "settings ${normalize(current.config)}, expected $config"
		}
		differences
	}

	@Override
	String toString() {
		installed ? "$code $version (id $id, enabled $enabled, settings $config)" : "$code not installed"
	}

	static Map<String, String> normalize(Object config) {
		Map<String, String> result = new TreeMap<>()
		if (config instanceof Map) {
			config.each { k, v ->
				if (v != null) {
					result[k as String] = v as String
				}
			}
		}
		result
	}
}

/** {@link PluginOperations} via the Morpheus API. */
class ApiPluginOperations implements PluginOperations {

	private final MorpheusApi api

	ApiPluginOperations(MorpheusApi api) {
		this.api = api
	}

	Map find(String code) {
		List plugins = api.get('/api/plugins?max=1000').plugins as List
		plugins.find { it.code == code } as Map
	}

	Map install(PluginJar jar, int timeoutSeconds) {
		Object before = find(jar.code)?.lastUpdated
		api.upload('/api/plugins/upload', jar.file)
		long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
		Map plugin = null
		while (System.currentTimeMillis() < deadline) {
			plugin = find(jar.code)
			boolean thisUpload = plugin?.version == jar.version && (before == null || plugin.lastUpdated != before)
			if (thisUpload && plugin.status == 'error') {
				throw new IllegalStateException("plugin $jar could not be loaded: ${plugin.statusMessage}")
			}
			if (thisUpload && plugin.status != 'installing') {
				return plugin
			}
			sleep(2000)
		}
		throw new IllegalStateException("plugin $jar not installed after $timeoutSeconds s " +
			"(currently ${plugin?.version ?: 'not installed'} ${plugin?.status ?: ''}, last updated ${plugin?.lastUpdated})")
	}

	void update(Object id, Map plugin) {
		api.put("/api/plugins/$id", [plugin: plugin])
	}

	void delete(Object id) {
		api.delete("/api/plugins/$id")
	}

	void waitForAbsence(String code, int timeoutSeconds) {
		long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
		while (find(code) != null) {
			if (System.currentTimeMillis() > deadline) {
				throw new IllegalStateException("plugin $code still listed $timeoutSeconds s after deleting it")
			}
			sleep(2000)
		}
	}
}

/**
 * Finds the jar of an installed version: INFRA_EXPLORER_RESTORE_JAR (a jar or a directory with
 * {@code infra-explorer-<version>-all.jar}), otherwise build/releases, otherwise built from the git tag
 * {@code v<version>} with scripts/build-release.sh.
 */
class RestoreJarResolver {

	static File resolve(TestEnvironment env, String version) {
		String name = "infra-explorer-$version-all.jar"
		if (env.restoreJar) {
			File given = new File(env.restoreJar)
			return given.directory ? new File(given, name) : given
		}
		if (env.projectDir == null) {
			return null
		}
		File releases = new File(env.projectDir, 'build/releases')
		File jar = new File(releases, name)
		if (jar.file) {
			return jar
		}
		File script = new File(env.projectDir, 'scripts/build-release.sh')
		if (!script.file) {
			return null
		}
		println "[integration test] building the installed version $version from tag v$version"
		Process process = new ProcessBuilder('bash', script.absolutePath, version, releases.absolutePath)
			.directory(env.projectDir)
			.redirectError(ProcessBuilder.Redirect.INHERIT)
			.start()
		process.inputStream.text
		process.waitFor() == 0 && jar.file ? jar : null
	}
}
