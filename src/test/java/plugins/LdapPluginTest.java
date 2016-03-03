package plugins;

import org.hamcrest.Matchers;
import org.jenkinsci.test.acceptance.docker.DockerContainerHolder;
import org.jenkinsci.test.acceptance.docker.fixtures.LdapContainer;
import org.jenkinsci.test.acceptance.junit.*;
import org.jenkinsci.test.acceptance.plugins.ldap.LdapDetails;
import org.jenkinsci.test.acceptance.plugins.ldap.LdapEnvironmentVariable;
import org.jenkinsci.test.acceptance.plugins.ldap.SearchForGroupsLdapGroupMembershipStrategy;
import org.jenkinsci.test.acceptance.po.GlobalSecurityConfig;
import org.jenkinsci.test.acceptance.po.LdapSecurityRealm;
import org.jenkinsci.test.acceptance.po.Login;
import org.jenkinsci.test.acceptance.po.User;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.jvnet.hudson.test.Issue;

import javax.inject.Inject;

import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.jenkinsci.test.acceptance.Matchers.*;

import java.io.IOException;
import java.util.NoSuchElementException;

import static org.hamcrest.MatcherAssert.assertThat;


/**
 * Feature: Tests for LdapPlugin.
 * This test suite always runs against the latest ldap plugin version.
 *
 * @author Michael Prankl
 */
@WithDocker
@WithPlugins("ldap@1.10")
public class LdapPluginTest extends AbstractJUnitTest {

    @Inject
    DockerContainerHolder<LdapContainer> ldap;

    /**
     * "Jenkins is using ldap as security realm"
     */
    private void useLdapAsSecurityRealm(LdapDetails ldapDetails) {
        GlobalSecurityConfig security = new GlobalSecurityConfig(jenkins);
        security.configure();
        LdapSecurityRealm realm = security.useRealm(LdapSecurityRealm.class);
        realm.configure(ldapDetails);
        security.save();
    }

    /**
     * Creates default ldap connection details from a running docker LdapContainer.
     *
     * @param ldapContainer a docker LdapContainer
     * @return default ldap connection details
     */
    private LdapDetails createDefaults(LdapContainer ldapContainer) {
        return new LdapDetails(ldapContainer.getHost(), ldapContainer.getPort(), ldapContainer.getManagerDn(), ldapContainer.getManagerPassword(), ldapContainer.getRootDn());
    }
    
    /**
     * Creates default ldap connection details without manager credentials from a running docker LdapContainer.
     * 
     * @param ldapContainer
     * @return default ldap connection details without the manager credentials
     */
    private LdapDetails createDefaultsWithoutManagerCred(LdapContainer ldapContainer) {
        return new LdapDetails(ldapContainer.getHost(), ldapContainer.getPort(), "", "", ldapContainer.getRootDn());
    }

    /**
     * Scenario: Login with ldap uid and password
     * Given I have a docker fixture "ldap"
     * And Jenkins is using ldap as security realm
     * When I login with user "jenkins" and password "root"
     * Then I will be successfully logged on as user "jenkins"
     */
    @Test
    public void login_ok() {
        // Given
        useLdapAsSecurityRealm(createDefaults(ldap.get()));
        // When
        Login login = jenkins.login();
        login.doLogin("jenkins", "root");
        // Then
        assertThat(jenkins, hasLoggedInUser("jenkins"));
    }

    /**
     * Scenario: Login with ldap uid and password
     * Given I have a docker fixture "ldap" that allows anonymous binding
     * And Jenkins is using ldap as security realm
     * When I login with user "jenkins" and password "root" without manager credentials
     * Then I will be successfully logged on as user "jenkins"
     */
    @Test
    public void login_ok_anonymous_binding() {
        // Given
        useLdapAsSecurityRealm(createDefaultsWithoutManagerCred(ldap.get()));
        // When
        Login login = jenkins.login();
        login.doLogin("jenkins", "root");
        // Then
        assertThat(jenkins, hasLoggedInUser("jenkins"));
    }

    /**
     * Scenario: Login with ldap uid and a wrong password
     * Given I have a docker fixture "ldap"
     * And Jenkins is using ldap as security realm
     * When I login with user "jenkins" and password "thisisawrongpassword"
     * Then I will not be logged on as user "jenkins"
     */
    @Test
    public void login_wrong_password() {
        // Given
        useLdapAsSecurityRealm(createDefaults(ldap.get()));
        // When
        Login login = jenkins.login();
        login.doLogin("jenkins", "thisisawrongpassword");
        // Then
        assertThat(jenkins, not(hasLoggedInUser("jenkins")));
    }

    /**
     * Scenario: Login with ldap uid and a not existent user
     * Given I have a docker fixture "ldap"
     * And Jenkins is using ldap as security realm
     * When I login with user "maggie" and password "simpson"
     * Then I will not be logged on as user "maggie"
     */
    @Test
    public void login_no_such_user() {
        // Given
        useLdapAsSecurityRealm(createDefaults(ldap.get()));
        // When
        Login login = jenkins.login();
        login.doLogin("maggie", "simpson");
        // Then
        assertThat(jenkins, not(hasLoggedInUser("maggie")));
    }

    /**
     * Scenario: No ldap server running
     * Given docker fixture "ldap" is not running
     * When I configure Jenkins to use a not running ldap host as security realm
     * Then Jenkins will tell me he cannot connect to "ldap"
     * And I will not be able to login with user "jenkins" and password "root"
     */
    @Test
    public void login_no_ldap() {
        // Given
        // don't start docker fixture here
        // When
        GlobalSecurityConfig security = new GlobalSecurityConfig(jenkins);
        security.configure();
        LdapSecurityRealm realm = security.useRealm(LdapSecurityRealm.class);
        int freePort = findAvailablePort();
        LdapDetails notRunningLdap = new LdapDetails("localhost", freePort, "cn=admin,dc=jenkins-ci,dc=org", "root", "dc=jenkins-ci,dc=org");
        realm.configure(notRunningLdap);
        security.save();
        // Then
        assertThat(security.open(), hasContent("Unable to connect to localhost:" + freePort));
        Login login = jenkins.login();
        login.doLogin("jenkins", "root");
        assertThat(jenkins, not(hasLoggedInUser("jenkins")));
    }

    /**
     * Scenario: login with a user which is in organizational unit "People" and user search base is "ou=People"
     * Given I have a docker fixture "ldap"
     * And Jenkins is using ldap as security realm with user search base "ou=People"
     * When I login with user "homer" and password "simpson"
     * Then I will be logged on as user "homer"
     */
    @Test
    public void login_search_base_people_ok() {
        // Given
        useLdapAsSecurityRealm(createDefaults(ldap.get()).userSearchBase("ou=People"));
        // When
        Login login = jenkins.login();
        login.doLogin("homer", "cisco");
        // Then
        assertThat(jenkins, hasLoggedInUser("homer"));
    }

    /**
     * Scenario: login with a user which is NOT in organizational unit "People" and user search base is "ou=People"
     * Given I have a docker fixture "ldap"
     * And Jenkins is using ldap as security realm with user search base "ou=People"
     * When I login with user "jenkins" and password "root"
     * Then I will not be logged on as user "jenkins"
     */
    @Test
    public void login_search_base_people_not_found() {
        // Given
        useLdapAsSecurityRealm(createDefaults(ldap.get()).userSearchBase("ou=People"));
        // When
        Login login = jenkins.login();
        login.doLogin("jenkins", "root");
        // Then
        assertThat(jenkins, not(hasLoggedInUser("jenkins")));
    }

    /**
     * Scenario: login with email address
     * Given I have a docker fixture "ldap"
     * And Jenkins is using ldap as security realm with user search filter "mail={0}"
     * When I login with email "jenkins@jenkins-ci.org" and password "root"
     * Then I will be logged on as user "jenkins@jenkins-ci.org"
     */
    @Test
    public void login_email_ok() {
        // Given
        useLdapAsSecurityRealm(createDefaults(ldap.get()).userSearchFilter("mail={0}"));
        // When
        Login login = jenkins.login();
        login.doLogin("jenkins@jenkins-ci.org", "root");
        // Then
        assertThat(jenkins, hasLoggedInUser("jenkins@jenkins-ci.org"));
    }
    
    /**
     * Scenario: login with email address
     * Given I have a docker fixture "ldap"
     * And Jenkins is using ldap as security realm with user search filter "invalid={0}"
     * When I login with email "jenkins@jenkins-ci.org" and password "root"
     * Or when I login with user "jenkins" and password "root"
     * Then I will not be able to log in
     */
    @Test
    public void invalid_user_search_filter() {
        // Given
        useLdapAsSecurityRealm(createDefaults(ldap.get()).userSearchFilter("invalid={0}"));
        // When
        Login login = jenkins.login();
        login.doLogin("jenkins@jenkins-ci.org", "root");
        // Then
        assertThat(jenkins, not(hasLoggedInUser("jenkins@jenkins-ci.org")));
        // When
        login = jenkins.login();
        login.doLogin("jenkins", "root");
        // Then
        assertThat(jenkins, not(hasLoggedInUser("jenkins")));
    }

    /**
     * Scenario: fallback to alternate server
     * Given I have a docker fixture "ldap"
     * And Jenkins is using a not running ldap server as primary and "ldap" as fallback security realm
     * When I login with "jenkins" and password "root"
     * Then I will be successfully logged in as user "jenkins"
     */
    @Test
    public void login_use_fallback_server() {
        // Given
        LdapContainer ldapContainer = ldap.get();
        GlobalSecurityConfig securityConfig = new GlobalSecurityConfig(jenkins);
        securityConfig.configure();
        LdapSecurityRealm realm = securityConfig.useRealm(LdapSecurityRealm.class);
        int freePort = this.findAvailablePort();
        LdapDetails ldapDetails = new LdapDetails("", 0, ldapContainer.getManagerDn(), ldapContainer.getManagerPassword(), ldapContainer.getRootDn());
        // Fallback-Config: primary server is not running, alternative server is running docker fixture
        ldapDetails.setHostWithPort(ldapContainer.getHost()+":" + freePort + " " + ldapContainer.getHost() + ":" + ldapContainer.getPort());
        realm.configure(ldapDetails);
        securityConfig.save();

        // When
        Login login = jenkins.login();
        login.doLogin("jenkins", "root");

        // Then
        assertThat(jenkins, hasLoggedInUser("jenkins"));

    }

    /**
     * Scenario: resolve email address
     * Given I have a docker fixture "ldap"
     * And Jenkins is using ldap as security realm
     * When I login with user "jenkins" and password "root"
     * Then I will be logged on as user "jenkins"
     * And the resolved mail address is "jenkins@jenkins-ci.org"
     */
    @Test
    public void resolve_email() {
        // Given
        useLdapAsSecurityRealm(createDefaults(ldap.get()));
        // When
        Login login = jenkins.login();
        login.doLogin("jenkins", "root");
        // Then
        assertThat(jenkins, hasLoggedInUser("jenkins"));
        User u = new User(jenkins, "jenkins");
        assertThat(u, mailAddressIs("jenkins@jenkins-ci.org"));
    }

    /**
     * Scenario: do not resolve email address
     * Given I have a docker fixture "ldap"
     * And Jenkins is using ldap as security realm and resolve email address is disabled
     * When I login with user "jenkins" and password "root"
     * Then I will be logged on as user "jenkins"
     * And there will not be any resolved email address"
     */
    @Test
    public void do_not_resolve_email() {
        // Given
        LdapDetails details = createDefaults(ldap.get());
        details.setDisableLdapEmailResolver(true);
        useLdapAsSecurityRealm(details);
        // When
        Login login = jenkins.login();
        login.doLogin("jenkins", "root");
        // Then
        assertThat(jenkins, hasLoggedInUser("jenkins"));
        User u = new User(jenkins, "jenkins");
        assertThat(u.mail(), nullValue());
    }
    
   /**
   * Scenario: do not resolve email address
   * Given I have a docker fixture "ldap"
   * And Jenkins is using ldap as security realm and cache is enabled
   * When I login with user "jenkins" and password "root"
   * Then I will be logged on as user "jenkins"
   * Nothing much can be tested here apart from being able to use the security realm with the option activated
   */
    @Test
    public void enable_cache() throws IOException {
        // Given
        LdapDetails details = createDefaults(ldap.get());
        details.setEnableCache(true);
        useLdapAsSecurityRealm(details);
        // When
        Login login = jenkins.login();
        login.doLogin("jenkins", "root");
        // Then
        assertThat(jenkins, hasLoggedInUser("jenkins"));
    }
    
    /**
     * Scenario: can use environment variables
     * Given I have a docker fixture "ldap"
     * And Jenkins is using ldap as security realm and the java.naming.ldap.typesOnly env var is se to true
     * When I login with user "jenkins" and password "root"
     * Then I will not be logged on as user "jenkins" because the values for the LDAP attributes
     * have not been sent as defined in the environment variable.
     */
    @Test
    public void use_environment_varibales() {
        // Given
        LdapDetails details = createDefaultsWithoutManagerCred(ldap.get());
        details.addEnvironmentVariable(new LdapEnvironmentVariable("java.naming.ldap.typesOnly", "true"));
        useLdapAsSecurityRealm(details);
        // When
        Login login = jenkins.login();
        login.doLogin("jenkins", "root");
        // Then
        assertThat(jenkins, not(hasLoggedInUser("jenkins")));
        assertThat(getElement(by.tagName("pre")).getText(), Matchers.containsString("NoSuchElementException"));
    }
   
    /**
     * Scenario: resolve group memberships of user with default configuration
     * Given I have a docker fixture "ldap"
     * And Jenkins is using ldap as security realm
     * When I login with user "jenkins" and password "root"
     * Then "jenkins" will be member of following groups: "ldap1", "ldap2"
     */
    @Test
    public void resolve_group_memberships_with_defaults() {
        // Given
        useLdapAsSecurityRealm(createDefaults(ldap.get()));
        // When
        Login login = jenkins.login();
        login.doLogin("jenkins", "root");
        User userJenkins = new User(jenkins, "jenkins");
        // Then
        assertThat(userJenkins, isMemberOf("ldap1"));
        assertThat(userJenkins, isMemberOf("ldap2"));
    }

    /**
     * Scenario: resolve group memberships of user with default configuration
     * Given I have a docker fixture "ldap"
     * And Jenkins is using ldap as security realm
     * When I login with user "homer" and password "cisco"
     * Then "homer" will be member of group "ldap2"
     * And "homer" will not be member of group "ldap1"
     */
    @Test
    public void resolve_group_memberships_with_defaults_negative() {
        // Given
        useLdapAsSecurityRealm(createDefaults(ldap.get()));
        // When
        Login login = jenkins.login();
        login.doLogin("homer", "cisco");
        User homer = new User(jenkins, "homer");
        // Then
        assertThat(homer, isMemberOf("ldap2"));
        assertThat(homer, not(isMemberOf("ldap1")));
    }

    /**
     * Scenario: using custom group search base "ou=Applications" (contains no groups)
     * Given I have a docker fixture "ldap"
     * And Jenkins is using ldap as security realm with group search base "ou=Applications"
     * When I login with user "jenkins" and password "root"
     * Then "jenkins" will not be member of groups "ldap1" and "ldap2"
     */
    @Test
    public void custom_group_search_base() {
        // Given
        useLdapAsSecurityRealm(createDefaults(ldap.get()).groupSearchBase("ou=Applications"));
        // When
        Login login = jenkins.login();
        login.doLogin("jenkins", "root");
        User userJenkins = new User(jenkins, "jenkins");
        // Then
        assertThat(userJenkins, not(isMemberOf("ldap1")));
        assertThat(userJenkins, not(isMemberOf("ldap2")));
    }

    /**
     * Scenario: resolve display name of ldap user with default display name attribute
     * Given I have a docker fixture "ldap"
     * And Jenkins is using ldap as security realm
     * When I login with user "jenkins" and password "root"
     * Then the display name of "jenkins" will be "Jenkins displayname"
     * <p/>
     * working since ldap plugin version: 1.8
     */
    @Test
    @Issue("JENKINS-18355")
    public void resolve_display_name_with_defaults() {
        // Given
        useLdapAsSecurityRealm(createDefaults(ldap.get()));
        // When
        Login login = jenkins.login();
        login.doLogin("jenkins", "root");
        User userJenkins = new User(jenkins, "jenkins");
        // Then
        assertThat(userJenkins, fullNameIs("Jenkins displayname"));
    }

    /**
     * Scenario: using custom display name attribute (cn instead of display name)
     * Given I have a docker fixture "ldap"
     * And Jenkins is using ldap as security realm and display name attribute is "cn"
     * When I login with user "jenkins" and password "root"
     * Then the display name of "jenkins" will be "Jenkins the Butler"
     * <p/>
     * working since ldap plugin version: 1.8
     */
    @Test
    @Issue("JENKINS-18355")
    @Category(SmokeTest.class)
    public void custom_display_name() {
        // Given
        useLdapAsSecurityRealm(createDefaults(ldap.get()).displayNameAttributeName("cn"));
        // When
        Login login = jenkins.login();
        login.doLogin("jenkins", "root");
        User userJenkins = new User(jenkins, "jenkins");
        // Then
        assertThat(userJenkins, fullNameIs("Jenkins the Butler"));
    }

    /**
     * Scenario: using "search for groups containing user" strategy with group membership filter which leads to no user belongs to a group
     * Given I have a docker fixture "ldap"
     * And Jenkins is using ldap as security realm with group membership filter "(member={0})"
     * When I login with user "jenkins" and password "root"
     * Then "jenkins" will not be member of groups "ldap1" and "ldap2"
     */
    @Test
    public void custom_invalid_group_membership_filter() {
        // Given
        useLdapAsSecurityRealm(createDefaults(ldap.get()).groupMembershipStrategy(SearchForGroupsLdapGroupMembershipStrategy.class).groupMembershipStrategyParam("(member={0})"));
        // When
        Login login = jenkins.login();
        login.doLogin("jenkins", "root");
        User userJenkins = new User(jenkins, "jenkins");
        // Then
        assertThat(userJenkins, not(isMemberOf("ldap1")));
        assertThat(userJenkins, not(isMemberOf("ldap2")));
    }
    
    /**
     * Scenario: using "search for groups containing user" strategy with group correct membership filter leads to user belonging to right groups
     * Given I have a docker fixture "ldap"
     * And Jenkins is using ldap as security realm with group membership filter "(memberUid={1})"
     * When I login with user "jenkins" and password "root"
     * Then "jenkins" will be member of groups "ldap1" and "ldap2"
     */
    @Test
    public void custom_valid_group_membership_filter() {
        // Given
        useLdapAsSecurityRealm(createDefaults(ldap.get()).groupMembershipStrategy(SearchForGroupsLdapGroupMembershipStrategy.class).groupMembershipStrategyParam("memberUid={1}"));
        // When
        Login login = jenkins.login();
        login.doLogin("jenkins", "root");
        User userJenkins = new User(jenkins, "jenkins");
        // Then
        assertThat(userJenkins, isMemberOf("ldap1"));
        assertThat(userJenkins, isMemberOf("ldap2"));
    }
    
    /**
     * Scenario: use a custom mail filter (gn instead of mail)
     * Given I have a docker fixture "ldap"
     * And Jenkins is using ldap as security realm and mail address attribute is "dn"
     * When I login with user "jenkins" and password "root"
     * Then the mail address of "jenkins" will be "givenname@mailaddress.com"
     * <p/>
     * since ldap plugin version 1.8
     */
    @Test
    public void custom_mail_filter() {
        // Given
        useLdapAsSecurityRealm(createDefaults(ldap.get()).mailAdressAttributeName("givenName"));
        // When
        Login login = jenkins.login();
        login.doLogin("jenkins", "root");
        User userJenkins = new User(jenkins, "jenkins");
        // Then
        assertThat(userJenkins, mailAddressIs("givenname@mailaddress.com"));
    }

}
