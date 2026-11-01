package eu.liehne.morpheus.infraexplorer.it

/**
 * Connection data and options of the integration test, read only from environment variables and the system
 * properties set by the Gradle task. See docs/integration-test.md.
 */
class TestEnvironment {

	static final String PLUGIN_CODE = 'infra-explorer'

	final String url
	final String token
	final String user
	final String password
	final String caCert
	final boolean insecure
	final String prefix
	final String restoreJar
	final int timeoutSeconds
	final File jar
	final File projectDir

	private TestEnvironment(Map<String, String> env, Map<String, String> props) {
		url = env.MORPHEUS_URL?.trim()?.replaceAll('/+$', '')
		token = env.MORPHEUS_TOKEN
		user = env.MORPHEUS_ADMIN_USER
		password = env.MORPHEUS_ADMIN_PASSWORD
		caCert = env.MORPHEUS_CACERT ?: null
		insecure = env.MORPHEUS_INSECURE == '1'
		prefix = (env.TEST_PREFIX ?: env.LAB_TEST_PREFIX)?.trim()
		restoreJar = env.INFRA_EXPLORER_RESTORE_JAR ?: null
		timeoutSeconds = (env.INFRA_EXPLORER_TEST_TIMEOUT ?: '300') as int
		jar = props['infraExplorer.jar'] ? new File(props['infraExplorer.jar']) : null
		projectDir = props['infraExplorer.projectDir'] ? new File(props['infraExplorer.projectDir']) : null
	}

	static TestEnvironment fromSystem() {
		Map<String, String> props = [:]
		System.properties.each { k, v -> props[k as String] = v as String }
		new TestEnvironment(System.getenv(), props)
	}

	/** Problems that prevent a safe run; empty if the test may start. */
	List<String> problems() {
		List<String> problems = []
		if (!url?.startsWith('https://') && !url?.startsWith('http://')) {
			problems << 'MORPHEUS_URL is not set (e.g. https://morpheus.example.com)'
		} else if (URI.create(url).userInfo) {
			problems << 'MORPHEUS_URL must not contain credentials'
		}
		if (!token) {
			problems << 'MORPHEUS_TOKEN is not set (API token of a user who may manage plugins, users and roles)'
		}
		if (!user || !password) {
			problems << 'MORPHEUS_ADMIN_USER and MORPHEUS_ADMIN_PASSWORD are not set (UI login of an administrator)'
		}
		if (!prefix) {
			problems << 'TEST_PREFIX is not set (name prefix for test objects, e.g. it-test-)'
		} else if (!(prefix ==~ /[A-Za-z0-9][A-Za-z0-9._-]{1,30}/)) {
			problems << 'TEST_PREFIX may only contain letters, digits, ".", "_" and "-" (2-31 characters)'
		}
		if (caCert && !new File(caCert).file) {
			problems << "MORPHEUS_CACERT does not point to a file: $caCert"
		}
		if (!jar?.file) {
			problems << "plugin jar not found: $jar (run via ./gradlew integrationTest)"
		}
		problems
	}

	/** Name of a test object: prefix plus a fixed suffix, so that leftovers of aborted runs can be found again. */
	String testName(String suffix) {
		"${prefix}ie-${suffix}"
	}
}
