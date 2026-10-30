package eu.liehne.morpheus.infraexplorer

import com.morpheusdata.model.Account
import com.morpheusdata.model.User
import eu.liehne.morpheus.infraexplorer.source.InfraNode
import spock.lang.Specification

class AccessPolicySpec extends Specification {

	AccessPolicy policy = new AccessPolicy()

	def "cluster view needs at least read on clusters"() {
		expect:
		policy.canViewClusters(user(permissions)) == expected

		where:
		permissions                         || expected
		['infrastructure-cluster': 'full']  || true
		['infrastructure-cluster': 'read']  || true
		['infrastructure-cluster': 'none']  || false
		[:]                                 || false
		null                                || false
	}

	def "hosts and VMs need read on clusters and on compute"() {
		expect:
		policy.canViewServers(user(permissions)) == expected

		where:
		permissions                                                   || expected
		['infrastructure-cluster': 'read', 'admin-servers': 'read']   || true
		['infrastructure-cluster': 'full', 'admin-servers': 'full']   || true
		['infrastructure-cluster': 'read', 'admin-servers': 'none']   || false
		['infrastructure-cluster': 'none', 'admin-servers': 'full']   || false
		['admin-servers': 'full']                                     || false
	}

	def "storage view needs read on clusters and on storage"() {
		expect:
		policy.canViewStorage(user(permissions)) == expected

		where:
		permissions                                                            || expected
		['infrastructure-cluster': 'read', 'infrastructure-storage': 'read']   || true
		['infrastructure-cluster': 'read', 'infrastructure-storage': 'none']   || false
		['infrastructure-storage': 'full']                                     || false
	}

	def "no user, no access"() {
		expect:
		!policy.canViewClusters(null)
		!policy.canViewServers(null)
	}

	def "tenant check: master tenant sees all, other tenants only their own, missing data means no access"() {
		expect:
		policy.canSeeTenantObject(userAccount == null ? new User() : new User(account: userAccount), owner) == expected

		where:
		userAccount                                || owner                 || expected
		new Account(id: 1L, masterAccount: true)   || new Account(id: 5L)   || true
		new Account(id: 1L, masterAccount: true)   || null                  || true
		new Account(id: 5L)                        || new Account(id: 5L)   || true
		new Account(id: 5L)                        || new Account(id: 6L)   || false
		new Account(id: 5L)                        || null                  || false
		new Account(id: 5L)                        || new Account()         || false
		new Account()                              || new Account()         || false
		null                                       || new Account(id: 5L)   || false
	}

	def "offers VM actions by permission level, limited levels only on own VMs"() {
		given:
		User user = new User(id: 9L, permissions: permissions)

		expect:
		policy.vmActions(user, new InfraNode(createdById: createdBy, instanceCreatedById: instanceCreatedBy)) == expected

		where:
		permissions                                                                        | createdBy | instanceCreatedBy || expected
		['terminal': 'full', 'provisioning-power': 'full', 'provisioning-reconfigure': 'full'] | 1L    | null              || ['console', 'power', 'reconfigure']
		['terminal': 'provisioned', 'provisioning-power': 'user', 'provisioning-reconfigure': 'user'] | 1L | null       || []
		['terminal': 'provisioned', 'provisioning-power': 'user', 'provisioning-reconfigure': 'user'] | 9L | null       || ['console', 'power', 'reconfigure']
		['terminal': 'provisioned', 'provisioning-power': 'user']                          | null      | 9L                || ['console', 'power']
		['terminal': 'read', 'provisioning-power': 'read', 'provisioning-reconfigure': 'none'] | 9L    | 9L                || []
		[:]                                                                                | 9L        | 9L                || []
	}

	def "offers converting only for unmanaged VMs and with full access to Compute"() {
		given:
		Map<String, String> full = ['infrastructure-cluster': 'read', 'admin-servers': 'full']
		Map<String, String> read = ['infrastructure-cluster': 'read', 'admin-servers': 'read']

		expect:
		policy.vmActions(new User(id: 9L, permissions: full), new InfraNode(managed: false)) == ['folder', 'convert']
		policy.vmActions(new User(id: 9L, permissions: full), new InfraNode(managed: true)) == ['folder']
		policy.vmActions(new User(id: 9L, permissions: full), new InfraNode()) == ['folder']
		policy.vmActions(new User(id: 9L, permissions: read), new InfraNode(managed: false)) == []
	}

	def "allows creating instances only in the own tenant and with full access"() {
		expect:
		policy.instanceCreateTenant(new User(account: new Account(id: 2L), permissions: ['provisioning-add': 'full'])) == 2L
		policy.instanceCreateTenant(new User(account: new Account(id: 2L), permissions: ['provisioning-add': 'none'])) == null
		policy.instanceCreateTenant(new User(permissions: ['provisioning-add': 'full'])) == null
		policy.instanceCreateTenant(null) == null
	}

	def "allows renaming levels only with full access to plugins and compute"() {
		expect:
		policy.canEditLevels(new User(id: 1L, permissions: ['infrastructure-cluster': 'read', 'admin-servers': 'full', 'admin-plugins': 'full']))
		!policy.canEditLevels(new User(id: 1L, permissions: ['infrastructure-cluster': 'read', 'admin-servers': 'full', 'admin-plugins': 'read']))
		!policy.canEditLevels(new User(id: 1L, permissions: ['infrastructure-cluster': 'read', 'admin-servers': 'read', 'admin-plugins': 'full']))
	}

	def "offers cluster actions only with full access to clusters"() {
		expect:
		policy.clusterActions(new User(id: 1L, permissions: ['infrastructure-cluster': 'full'])) == ['refresh', 'edit']
		policy.clusterActions(new User(id: 1L, permissions: ['infrastructure-cluster': 'read'])) == []
		policy.clusterActions(new User(permissions: ['infrastructure-cluster': 'full'])) == []
		policy.clusterActions(null) == []
	}

	def "offers host actions with full access to compute, restart only with full power control"() {
		expect:
		policy.hostActions(new User(id: 1L, permissions: permissions)) == expected

		where:
		permissions                                                                                 || expected
		['infrastructure-cluster': 'read', 'admin-servers': 'full', 'provisioning-power': 'full']    || ['maintenance', 'edit', 'restart']
		['infrastructure-cluster': 'read', 'admin-servers': 'full', 'provisioning-power': 'user']    || ['maintenance', 'edit']
		['infrastructure-cluster': 'read', 'admin-servers': 'read', 'provisioning-power': 'full']    || []
		['infrastructure-cluster': 'none', 'admin-servers': 'full', 'provisioning-power': 'full']    || []
	}

	def "offers no VM actions without user id"() {
		expect:
		policy.vmActions(new User(permissions: ['terminal': 'full']), new InfraNode()) == []
		policy.vmActions(null, new InfraNode()) == []
	}

	def "tenant check by owner id"() {
		expect:
		policy.canSeeTenant(new User(account: userAccount), ownerId) == expected

		where:
		userAccount                              || ownerId || expected
		new Account(id: 1L, masterAccount: true) || 5L      || true
		new Account(id: 1L, masterAccount: true) || null    || true
		new Account(id: 5L)                      || 5L      || true
		new Account(id: 5L)                      || 6L      || false
		new Account(id: 5L)                      || null    || false
		new Account()                            || null    || false
	}

	def "tenant check without user"() {
		expect:
		!policy.canSeeTenantObject(null, new Account(id: 5L))
	}

	private static User user(Map<String, String> permissions) {
		new User(permissions: permissions)
	}
}
