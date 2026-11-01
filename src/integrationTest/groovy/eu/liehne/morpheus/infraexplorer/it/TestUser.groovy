package eu.liehne.morpheus.infraexplorer.it

import java.security.SecureRandom

/**
 * A restricted test user with its own role (names from the test prefix), to check that the plugin respects the
 * permissions of the signed-in user. {@link #remove} deletes both by name, so leftovers of an aborted run are removed
 * as well.
 */
class TestUser {

	private final MorpheusApi api
	private final TestEnvironment env
	final String roleName
	final String username
	private Object roleId
	private Object userId
	private String password

	TestUser(MorpheusApi api, TestEnvironment env) {
		this.api = api
		this.env = env
		this.roleName = env.testName('role')
		this.username = env.testName('user')
	}

	/** Creates the role without any permission and the user with this role. */
	TestUser create() {
		remove()
		roleId = api.post('/api/roles', [role: [authority: roleName, roleType: 'user',
			description: 'Infra Explorer integration test, removed automatically']]).role.id
		password = randomPassword()
		userId = api.post('/api/users', [user: [username: username, email: "${username}@example.invalid",
			firstName: 'Integration', lastName: 'Test', password: password, roles: [[id: roleId]]]]).user.id
		this
	}

	/** Sets the access level of a feature permission of the test role, e.g. {@code infrastructure-cluster}, {@code read}. */
	void grant(String permissionCode, String access) {
		api.put("/api/roles/$roleId/update-permission", [permissionCode: permissionCode, access: access])
	}

	/** New UI session of the test user; permissions are read at login. */
	UiSession login() {
		new UiSession(env).login(username, password)
	}

	/**
	 * Deletes user and role with the test names; returns what was deleted. Tries both even if one fails and then
	 * throws with all errors.
	 */
	List<String> remove() {
		List<String> removed = []
		List<String> errors = []
		try {
			List users = api.get("/api/users?max=1000&phrase=${URLEncoder.encode(username, 'UTF-8')}").users as List
			users.findAll { it.username == username }.each {
				api.delete("/api/users/${it.id}")
				removed << "user ${it.username}"
			}
		} catch (Exception e) {
			errors << "user $username: ${e.message}"
		}
		try {
			List roles = api.get("/api/roles?max=1000&phrase=${URLEncoder.encode(roleName, 'UTF-8')}").roles as List
			roles.findAll { it.authority == roleName }.each {
				api.delete("/api/roles/${it.id}")
				removed << "role ${it.authority}"
			}
		} catch (Exception e) {
			errors << "role $roleName: ${e.message}"
		}
		if (errors) {
			throw new IllegalStateException(errors.join('; '))
		}
		userId = null
		roleId = null
		removed
	}

	private static String randomPassword() {
		SecureRandom random = new SecureRandom()
		String chars = 'abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789'
		String body = (1..20).collect { chars[random.nextInt(chars.length())] }.join()
		"It-${body}-7!"
	}
}
