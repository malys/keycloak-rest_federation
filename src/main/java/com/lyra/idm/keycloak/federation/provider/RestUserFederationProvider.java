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
import org.keycloak.authentication.actiontoken.execactions.ExecuteActionsActionToken;
import org.keycloak.common.util.Time;
import org.keycloak.component.ComponentModel;
import org.keycloak.email.EmailException;
import org.keycloak.email.EmailTemplateProvider;
import org.keycloak.models.Constants;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.services.ServicesLogger;
import org.keycloak.services.resources.LoginActionsService;
import org.keycloak.storage.UserStorageProvider;
import org.keycloak.storage.UserStorageProviderModel;

import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;


/**
 * Rest User federation to import users from remote user store
 */
@JBossLog
public class RestUserFederationProvider implements UserStorageProvider {

    protected KeycloakSession session;
    protected UserStorageProviderModel model;
    protected UserRepository repository;
    protected Boolean upperCaseName;
    protected Boolean proxyOn;
    protected String prefix;
    protected Boolean roleIsSync;
    protected Boolean attributesIsSync;
    protected Boolean uncheckFederation;
    protected Boolean notCreateUsers;
    protected List<String> resetActions;


    public RestUserFederationProvider(KeycloakSession session, ComponentModel model, UserRepository repository,
                                      Boolean roleIsSync, String prefix, Boolean upperCaseName,
                                      Boolean attributesIsSync, Boolean proxyOn, Boolean uncheckFederation,
                                      List<String> resetActions,Boolean notCreateUsers) {
        this.session = session;
        this.model = new UserStorageProviderModel(model);
        this.repository = repository;
        this.prefix = prefix;
        this.roleIsSync = roleIsSync;
        this.proxyOn = proxyOn;
        this.upperCaseName = upperCaseName;
        this.attributesIsSync = attributesIsSync;
        this.uncheckFederation = uncheckFederation;
        this.resetActions = resetActions;
        this.notCreateUsers=notCreateUsers;
    }


    private String convertRemoteName(String remoteName) {
        String name = remoteName;
        if (this.prefix != null && this.prefix.length() > 0) {
            name = this.prefix + "_" + remoteName.replaceFirst("^" + this.prefix + "_", "");
        }
        if (this.upperCaseName) {
            name = name.toUpperCase();
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
        if (resetActions != null && resetActions.size() > 0) resetActionExecute(realm, result);
        return result;
    }

    protected UserModel updateUserFromRest(RealmModel realm, UserDto restUser, UserModel imported, final Boolean uncheck) {
        return proxy(realm, imported, restUser, false, uncheck);
    }

    protected void resetActionExecute(RealmModel realm, UserModel local) {
        if (local.getEmail() != null) {
            try {
                UriInfo uriInfo = session.getContext().getUri();
                String clientId = Constants.ACCOUNT_MANAGEMENT_CLIENT_ID;
                int lifespan = realm.getActionTokenGeneratedByAdminLifespan();
                int expiration = Time.currentTime() + lifespan;
                ExecuteActionsActionToken token = new ExecuteActionsActionToken(local.getId(), expiration, resetActions, null, clientId);
                UriBuilder builder = LoginActionsService.actionTokenProcessor(uriInfo);
                builder.queryParam("key", token.serialize(session, realm, uriInfo));

                String link = builder.build(realm.getName()).toString();
                session.getContext().setRealm(realm);
                session.getProvider(EmailTemplateProvider.class)
                        .setAttribute(Constants.TEMPLATE_ATTR_REQUIRED_ACTIONS, token.getRequiredActions())
                        .setRealm(realm)
                        .setUser(local)
                        .sendExecuteActions(link, TimeUnit.SECONDS.toMinutes(lifespan));
            } catch (EmailException e) {
                ServicesLogger.LOGGER.failedToSendActionsEmail(e);
            }
        }
    }


    /**
     * Bind remote attributes with local attributes
     *
     * @param realm
     * @param local
     * @param restUser
     * @return UserModel
     */
    protected UserModel proxy(RealmModel realm, UserModel local, final UserDto restUser, final Boolean over, final Boolean uncheck) {
        UserModel result = null;
        if (restUser != null) {

            if (!restUser.getEmail().equals(local.getEmail()) && !over) {
                throw new IllegalStateException(String.format("Local and remote users are not the same email : [%s != %s]", restUser.getEmail(), local.getEmail()));
            }

            //create restUser locally and set up relationship to this SPI
            if (over) local.setFederationLink(model.getId());

            //merge data from remote to local

            local.setFirstName(restUser.getFirstName());
            local.setLastName(restUser.getLastName());
            local.setUsername(restUser.getUserName());
            local.setEmail(restUser.getEmail());
            local.setEmailVerified(restUser.isEnabled());
            local.setEnabled(restUser.isEnabled());


            //pass roles along
            if (this.roleIsSync) {
                if (restUser.getRoles() != null) {
                    //clean roles in local
                    local.getRealmRoleMappings().removeIf(item -> item.getName().startsWith(this.prefix));

                    for (String role : restUser.getRoles()) {

                        String roleNorm = convertRemoteName(role);
                        RoleModel roleModel = realm.getRole(roleNorm);
                        if (roleModel == null) {
                            //Create role
                            roleModel = realm.addRole(roleNorm);
                            log.infof("Remote role %s granted created", role);
                        }

                        //Apply role
                        local.grantRole(roleModel);
                        log.debugf("Remote role %s granted to %s", role, restUser.getUserName());
                    }
                }
            }

            //pass attributes along
            if (this.attributesIsSync) {
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

            result = local;
        }
        return result;
    }

}