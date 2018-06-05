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
import org.keycloak.component.ComponentModel;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.storage.UserStorageProvider;
import org.keycloak.storage.UserStorageProviderModel;

import java.util.List;
import java.util.Map;


/**
 * Rest User federation to import users from remote user store
 */
@JBossLog
public class RestUserFederationProvider implements UserStorageProvider {

    protected KeycloakSession session;
    protected UserStorageProviderModel model;
    protected UserRepository repository;
    protected Boolean upperCaseRoleName;
    protected Boolean proxyOn;
    protected String rolePrefix;
    protected Boolean roleIsSync;
    protected Boolean attributesIsSync;


    public RestUserFederationProvider(KeycloakSession session, ComponentModel model, UserRepository repository, Boolean roleIsSync, String rolePrefix, Boolean upperCaseRoleName, Boolean attributesIsSync, Boolean proxyOn) {
        this.session = session;
        this.model = new UserStorageProviderModel(model);
        this.repository = repository;
        this.rolePrefix = rolePrefix;
        this.roleIsSync = roleIsSync;
        this.proxyOn = proxyOn;
        this.upperCaseRoleName = upperCaseRoleName;
        this.attributesIsSync = attributesIsSync;
    }


    private String convertRemoteRoleName(String remoteRoleName) {
        String roleName = remoteRoleName;
        if (this.rolePrefix != null && this.rolePrefix.length() > 0) {
            roleName = this.rolePrefix + "_"+ remoteRoleName.replaceFirst("^" + this.rolePrefix + "_", "");
        }
        if (this.upperCaseRoleName) {
            roleName = roleName.toUpperCase();
        }
        return roleName;
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

    protected UserModel importUserFromRest(KeycloakSession session, RealmModel realm, UserDto restUser) {
        String ldapUsername = restUser.getUserName();

        UserModel imported = session.userLocalStorage().addUser(realm, ldapUsername);
        log.debugf("Imported new user from Rest to Keycloak DB. Username: [%s], Email: [%s] for Realm: [%s] ",
                imported.getUsername(), imported.getEmail(), realm.getName());

        return proxy(realm, imported, restUser,true);
    }

    protected UserModel updateUserFromRest(RealmModel realm, UserDto restUser,UserModel imported) {
         return proxy(realm, imported, restUser,false);
    }


    /**
     * Bind remote attributes with local attributes
     *
     * @param realm
     * @param local
     * @param restUser
     * @return UserModel
     */
    protected UserModel proxy(RealmModel realm, UserModel local, final UserDto restUser,final Boolean over) {
        UserModel result=null;
        if (restUser != null) {

            if (!restUser.getEmail().equals(local.getEmail()) && !over) {
                throw new IllegalStateException(String.format("Local and remote users are not the same email : [%s != %s]", restUser.getEmail(), local.getEmail()));
            }

            //create restUser locally and set up relationship to this SPI
            if(over) local.setFederationLink(model.getId());

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
                    for (String role : restUser.getRoles()) {

                        String roleNorm = convertRemoteRoleName(role);
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
                    Map<String,List<String>> map=restUser.getAttributes();
                    for (String attKey : map.keySet()) {
                        local.setAttribute(attKey,map.get(attKey));
                        log.debugf("Remote attribute %s affected to %s", attKey, restUser.getUserName());
                    }
                }
            }

            result=local;
        }
        return result;
    }

}
