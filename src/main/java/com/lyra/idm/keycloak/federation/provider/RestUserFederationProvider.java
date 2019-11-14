/*
 * Copyright 2015 Changefirst Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lyra.idm.keycloak.federation.provider;

import com.lyra.idm.keycloak.federation.api.user.UserRepository;
import com.lyra.idm.keycloak.federation.model.UserDto;
import lombok.extern.jbosslog.JBossLog;
import org.jboss.resteasy.spi.ResteasyUriInfo;
import org.keycloak.authentication.actiontoken.execactions.ExecuteActionsActionToken;
import org.keycloak.common.util.Time;
import org.keycloak.component.ComponentModel;
import org.keycloak.credential.CredentialModel;
import org.keycloak.credential.UserCredentialStore;
import org.keycloak.email.EmailException;
import org.keycloak.email.EmailTemplateProvider;
import org.keycloak.models.ClientModel;
import org.keycloak.models.Constants;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserCredentialModel;
import org.keycloak.models.UserModel;
import org.keycloak.services.ServicesLogger;
import org.keycloak.services.resources.LoginActionsService;
import org.keycloak.storage.UserStorageProvider;
import org.keycloak.storage.UserStorageProviderModel;

import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Rest User federation to import users from remote user store
 */
@JBossLog
public class RestUserFederationProvider implements UserStorageProvider {

    public static final int ROLE_MIN_LENGTH = 3;
    public static final String ACTION = "action";
    private static final String TEMPLATE = "template";
    private final Pattern p3 = Pattern.compile("\\((.*?)\\)");
    protected KeycloakSession session;
    protected UserStorageProviderModel model;
    protected UserRepository repository;
    protected Boolean upperCaseName;
    protected Boolean proxyOn;
    protected String prefix;
    protected Boolean roleIsSync;
    protected String roleClient;
    protected Boolean attributesIsSync;
    protected Boolean uncheckFederation;
    protected Boolean notCreateUsers;
    protected List<String> resetActions;
    protected String publicUrl;

    protected Boolean passwordIsSync;
    protected String passwordAlgorithm;
    protected Integer passwordIteration;

    public RestUserFederationProvider(KeycloakSession session, ComponentModel model, UserRepository repository,
                                      Boolean roleIsSync, String roleClient,
                                      String prefix, Boolean upperCaseName,
                                      Boolean attributesIsSync,
                                      Boolean passwordIsSync, String passwordAlgorithm, Integer passwordIteration,
                                      Boolean proxyOn, Boolean uncheckFederation,
                                      List<String> resetActions,
                                      Boolean notCreateUsers,
                                      String publicUrl
    ) {
        this.session = session;
        this.model = new UserStorageProviderModel(model);
        this.repository = repository;
        this.prefix = prefix;
        this.roleIsSync = roleIsSync;
        this.roleClient = roleClient;
        this.proxyOn = proxyOn;
        this.upperCaseName = upperCaseName;
        this.attributesIsSync = attributesIsSync;
        this.uncheckFederation = uncheckFederation;
        this.resetActions = resetActions;
        this.notCreateUsers = notCreateUsers;
        this.publicUrl = publicUrl;
        this.passwordIsSync = passwordIsSync;
        this.passwordAlgorithm = passwordAlgorithm;
        this.passwordIteration = passwordIteration;
    }

    /**
     * Convert only custom attributes (exclude standard claims)
     *
     * @param remoteName
     * @return converted name
     */
    private String convertRemoteName(String remoteName) {
        //see standard https://openid.net/specs/openid-connect-core-1_0.html
        String name = remoteName;

        if (!RestUserFederationProviderFactory.OIDC_ATTRIBUTES.contains(name)) {
            if (this.prefix != null && this.prefix.length() > 0) {
                name = this.prefix + "_" + remoteName.replaceFirst("^" + this.prefix + "_", "");
            }
            if (this.upperCaseName) {
                name = name.toUpperCase(Locale.US);
            }
        }
        return name;
    }


    @Override
    public void close() {
        //n/a
    }

    @Override
    public void preRemove(RealmModel realm) {
        //n/a
    }

    @Override
    public void preRemove(RealmModel realm, GroupModel group) {
        //n/a
    }

    @Override
    public void preRemove(RealmModel realm, RoleModel role) {
        //n/a
    }

    protected UserModel importUserFromRest(KeycloakSession session, RealmModel realm, UserDto restUser, final Boolean uncheck) {
        String restUsername = restUser.getUserName();

        UserModel local = session.userLocalStorage().addUser(realm, restUsername);
        log.debugf("Imported new user from Rest to Keycloak DB. Username: [%s], Email: [%s] for Realm: [%s] ",
                local.getUsername(), local.getEmail(), realm.getName());
        UserModel result = proxy(realm, local, restUser, true, uncheck);
        if (resetActions != null && !resetActions.isEmpty()) {
            resetActionExecute(realm, result);
        }
        return result;
    }

    protected UserModel updateUserFromRest(RealmModel realm, UserDto restUser, UserModel imported, final Boolean uncheck) {
        return proxy(realm, imported, restUser, false, uncheck);
    }

    private Map<String, String> extractAction(String actions) {

        Matcher m3 = p3.matcher(actions);
        Map<String, String> result = new HashMap<>();

        String action = null;
        String template = null;

        if (m3.find()) {
            action = m3.group(1);
            template = actions.replace(m3.group(0), "");
        }
        result.put(ACTION, action);
        result.put(TEMPLATE, template);
        return result;
    }

    private void knownAction(RealmModel realm, UserModel local, EmailTemplateProvider emailTemp, String resetAction, UriInfo uriInfo, String clientId, int lifespan, int expiration) throws EmailException {
        List<String> resetActionsTmp = new ArrayList<>();
        resetActionsTmp.add(resetAction);
        ExecuteActionsActionToken token = new ExecuteActionsActionToken(local.getId(), expiration, resetActionsTmp, null, clientId);
        UriBuilder builder = LoginActionsService.actionTokenProcessor(uriInfo);
        builder.queryParam("key", token.serialize(session, realm, uriInfo));

        String link = builder.build(realm.getName()).toString();
        emailTemp
                .setAttribute(Constants.TEMPLATE_ATTR_REQUIRED_ACTIONS, token.getRequiredActions())
                .sendExecuteActions(link, TimeUnit.SECONDS.toMinutes(lifespan));
    }

    private void customAction(RealmModel realm, UserModel local, EmailTemplateProvider emailTemp, String resetAction, UriInfo uriInfo, String clientId, int lifespan, int expiration) throws EmailException {
        Map<String, String> config = new HashMap<>();
        List<String> resetActionsTmp = new ArrayList<>();
        resetActionsTmp.add(resetAction);
        Map<String, String> data = extractAction(resetAction);
        if (data.get(ACTION) != null && data.get(TEMPLATE) != null) {
            resetActionsTmp.add(data.get(ACTION).trim());
            config.put(TEMPLATE, data.get(TEMPLATE));

        } else {
            config.put(TEMPLATE, resetAction);
        }

        ExecuteActionsActionToken token = new ExecuteActionsActionToken(local.getId(), expiration, resetActionsTmp, null, clientId);
        UriBuilder builder = LoginActionsService.actionTokenProcessor(uriInfo);
        builder.queryParam("key", token.serialize(session, realm, uriInfo));

        String link = builder.build(realm.getName()).toString();
        config.put("link", link);
        config.put("linkExpiration", String.valueOf(TimeUnit.SECONDS.toMinutes(lifespan)));


        emailTemp
                .setAttribute(Constants.TEMPLATE_ATTR_REQUIRED_ACTIONS, token.getRequiredActions())
                .sendSmtpTestEmail(config, local);
    }

    protected void resetActionExecute(RealmModel realm, UserModel local) {
        if (local.getEmail() != null) {

            UriInfo uriInfo = new ResteasyUriInfo(URI.create(this.publicUrl));
            ((ResteasyUriInfo) uriInfo).setUri(URI.create(this.publicUrl), URI.create("/auth"));

            String clientId = Constants.ACCOUNT_MANAGEMENT_CLIENT_ID;
            int lifespan = realm.getActionTokenGeneratedByAdminLifespan();
            int expiration = Time.currentTime() + lifespan;
            session.getContext().setRealm(realm);


            for (String resetAction : resetActions) {
                EmailTemplateProvider emailTemp = session.getProvider(EmailTemplateProvider.class);
                if (emailTemp != null) {
                    emailTemp.setRealm(realm);
                    emailTemp.setUser(local);
                    try {
                        if ("UPDATE_PASSWORD".equals(resetAction) || "VERIFY_EMAIL".equals(resetAction)) {
                            knownAction(realm, local, emailTemp, resetAction, uriInfo, clientId, lifespan, expiration);
                        } else {
                            customAction(realm, local, emailTemp, resetAction, uriInfo, clientId, lifespan, expiration);
                        }

                    } catch (EmailException e) {
                        ServicesLogger.LOGGER.failedToSendActionsEmail(e);
                    }
                } else {
                    log.errorf("Missing FreeMarkerEmailTemplateCustomProvider module");
                }
            }

        }
    }

    private void attributeSynchronization(UserModel local, final UserDto restUser) {
        if (restUser.getAttributes() != null) {
            //clean attributes in local
            local.getAttributes().keySet().removeIf(item -> item.startsWith(this.prefix));

            Map<String, List<String>> map = restUser.getAttributes();
            for (Map.Entry<String, List<String>> entry : map.entrySet()) {
                local.setAttribute(convertRemoteName(entry.getKey()), entry.getValue());
                log.debugf("Remote attribute %s affected to %s", entry.getKey(), restUser.getUserName());
            }
        }
    }

    private void roleSynchronization(RealmModel realm, UserModel local, final UserDto restUser) {
        //Realm roles
        boolean isClientRoles = false;
        ClientModel client = null;

        if (this.roleClient != null && roleClient.length() > ROLE_MIN_LENGTH) {
            //Client roles
            client = realm.getClientByClientId(this.roleClient);
            if (client != null) {
                isClientRoles = true;
            } else {
                isClientRoles = false;
                log.warnf("Client %s doesn't exist. Roles will be created as realm roles.", this.roleClient);
            }
        }


        if (restUser.getRoles() != null) {
            //clean roles in local
            if (isClientRoles) {
                local.getClientRoleMappings(client).removeIf(item -> item.getName().startsWith(this.prefix));
            } else {
                local.getRealmRoleMappings().removeIf(item -> item.getName().startsWith(this.prefix));
            }

            for (String role : restUser.getRoles()) {
                String roleNorm = convertRemoteName(role);
                RoleModel roleModel;
                if (isClientRoles) {
                    roleModel = client.getRole(roleNorm);
                    if (roleModel == null) {
                        //Create role
                        roleModel = client.addRole(roleNorm);
                        log.infof("Remote role %s granted created", role);
                    }

                } else {
                    roleModel = realm.getRole(roleNorm);
                    if (roleModel == null) {
                        //Create role
                        roleModel = realm.addRole(roleNorm);
                        log.infof("Remote role %s granted created", role);
                    }
                }


                //Apply role
                local.grantRole(roleModel);
                log.debugf("Remote role %s granted to %s", role, restUser.getUserName());
            }
        }

    }

    private void mapper(UserModel local, final UserDto restUser) {
        //merge data from remote to local
        local.setFirstName(restUser.getFirstName());
        local.setLastName(restUser.getLastName());
        local.setUsername(restUser.getUserName().toLowerCase(Locale.US));
        local.setEmail(restUser.getEmail().toLowerCase(Locale.US));
        local.setEmailVerified(restUser.isEnabled());
        local.setEnabled(restUser.isEnabled());
    }

    /**
     * Bind remote attributes with local attributes
     *
     * @param realm
     * @param local
     * @param restUser
     * @return UserModel
     */
    protected UserModel proxy(RealmModel realm, UserModel local, final UserDto restUser, final Boolean over,
                              final Boolean uncheck) {
        UserModel result = null;
        if (restUser != null) {
            if (!restUser.getEmail().equalsIgnoreCase(local.getEmail()) && !over) {
                throw new IllegalStateException(String.format("Local and remote users are not the same email : [%s != %s]", restUser.getEmail(), local.getEmail()));
            }

            //create restUser locally and set up relationship to this SPI
            if (over) {
                local.setFederationLink(model.getId());
            }

            mapper(local, restUser);

            //pass roles along
            if (this.roleIsSync) {
                roleSynchronization(realm, local, restUser);
            }

            //pass attributes along
            if (this.attributesIsSync) {
                attributeSynchronization(local, restUser);
            }

            if (this.passwordIsSync) {
                passwordSynchronization(realm, local, restUser);
            }

            result = local;
        }
        return result;
    }

    private CredentialModel fillCredential(CredentialModel mo, UserDto restUser) {
        mo.setHashIterations(this.passwordIteration);
        mo.setAlgorithm(this.passwordAlgorithm);
        mo.setType(UserCredentialModel.PASSWORD);
        mo.setValue(restUser.getPassword());
        mo.setCreatedDate(Time.currentTimeMillis());
        return mo;
    }

    private void passwordSynchronization(RealmModel realm, UserModel local, UserDto restUser) {
        if (restUser.getPassword() != null) {

            List<CredentialModel> cModels = getCredentialStore().getStoredCredentials(realm, local);
            Optional<CredentialModel> moOpt = cModels.stream()
                    .filter(f -> f.getType().equals(UserCredentialModel.PASSWORD))
                    .findFirst();

            if (moOpt.isPresent()) {
                // Update credential
                getCredentialStore().updateCredential(realm, local, fillCredential(moOpt.get(), restUser));
            } else {
                // Create Credential
                getCredentialStore().createCredential(realm, local, fillCredential(new CredentialModel(), restUser));
            }
        } else {
            log.warnf("Missing password for: %s", restUser.getUserName());
        }
    }

    private UserCredentialStore getCredentialStore() {
        return session.userCredentialManager();
    }
}
