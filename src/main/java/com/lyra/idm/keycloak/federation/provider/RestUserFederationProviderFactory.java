/*
 * Copyright 2015 Smartling, Inc.
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
import org.keycloak.component.ComponentValidationException;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.KeycloakSessionTask;
import org.keycloak.models.ModelException;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.cache.UserCache;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;
import org.keycloak.storage.UserStorageProvider;
import org.keycloak.storage.UserStorageProviderFactory;
import org.keycloak.storage.UserStorageProviderModel;
import org.keycloak.storage.user.ImportSynchronization;
import org.keycloak.storage.user.SynchronizationResult;

import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * Remote user federation provider factory.
 *
 * @see http://www.keycloak.org/docs/3.4/server_development/
 */
@JBossLog
public class RestUserFederationProviderFactory implements ImportSynchronization, UserStorageProviderFactory<RestUserFederationProvider> {


    public static final String PROVIDER_NAME = "Rest User Federation";

    public static final String PROPERTY_URL = "url";
    public static final String ATTR_SYNC = "attr-sync";
    public static final String ROLE_SYNC = "role-sync";
    public static final String ROLE_PREFIX = "role-prefix";
    public static final String UPPERCASE_ROLE = "uppercase-role";
    public static final String PROXY_ENABLED = "proxy_enabled";
    public static final String PROXY_HOST= "proxyHost";
    public static final String PROXY_PORT= "proxyPort";



    protected static final List<ProviderConfigProperty> configMetadata;

    static {
        configMetadata = ProviderConfigurationBuilder.create()
                .property().name(PROPERTY_URL)
                .type(ProviderConfigProperty.STRING_TYPE)
                .label("Remote User Information Url")
                .defaultValue("https://")
                .helpText("Remote repository url")
                .add()
                .property().name(ROLE_SYNC)
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .defaultValue("true")
                .label("Enable roles synchronization")
                .helpText("Apply and create remote roles")
                .add()
                .property().name(ROLE_PREFIX)
                .type(ProviderConfigProperty.STRING_TYPE)
                .label("Include prefix for roles")
                .helpText("size >=3.Prefix that needs to be removed from roles received from remote API")
                .add()
                .property().name(UPPERCASE_ROLE)
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .defaultValue("true")
                .label("Uppercase role name")
                .helpText("Convert remote roles to uppercase")
                .add()
                .property().name(ATTR_SYNC)
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .defaultValue("true")
                .label("Enable attributes synchronization")
                .helpText("Apply remote attributes")
                .add()
                .property().name(PROXY_ENABLED)
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .label("Use Proxy")
                .helpText("Add Java Properties: http(s).proxyHost,http(s).proxyPort")
                .add()
                .build();
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return configMetadata;
    }

    @Override
    public void validateConfiguration(KeycloakSession session, RealmModel realm, ComponentModel config) throws ComponentValidationException {
        final String url = config.getConfig().getFirst(PROPERTY_URL);
        final String rolePrefix = config.getConfig().getFirst(ROLE_PREFIX);
        final Boolean roleIsSync = Boolean.valueOf(config.getConfig().getFirst(ROLE_SYNC));
        final Boolean proxyOn = Boolean.valueOf(config.getConfig().getFirst(PROXY_ENABLED));
        boolean valid = true;
        String comment="";

        if (url != null && url.length() < 10) {
            valid = false;
            comment="Please check the url.";
        }

        if (roleIsSync && rolePrefix != null && rolePrefix.length() < 3) {
            valid = false;
            comment="Please check role prefix size.";
        }

        if (proxyOn && System.getProperty("http." +PROXY_HOST) == null && System.getProperty("https." + PROXY_HOST) == null) {
            valid = false;
            comment="Please check 'http(s).proxyHost' property.";
        }

        log.debugf("validating module config %s", valid);

        if (!valid) {
            throw new ComponentValidationException("Invalid configuration. " + comment);
        }
    }

    @Override
    public String getId() {
        return PROVIDER_NAME;
    }

    @Override
    public String getHelpText() {
        return "This is a read-only user federation provider that can be used to sync user data one way from a remote REST API";
    }

    @Override
    public RestUserFederationProvider create(KeycloakSession session, ComponentModel model) {
        final String url = model.getConfig().getFirst(PROPERTY_URL);
        final Boolean attributesIsSync = Boolean.valueOf(model.getConfig().getFirst(ATTR_SYNC));
        final String rolePrefix = model.getConfig().getFirst(ROLE_PREFIX);
        final Boolean roleIsSync = Boolean.valueOf(model.getConfig().getFirst(ROLE_SYNC));
        final Boolean upperCase = Boolean.valueOf(model.getConfig().getFirst(UPPERCASE_ROLE));
        final Boolean proxyOn = Boolean.valueOf(model.getConfig().getFirst(PROXY_ENABLED));
        UserRepository repository = new UserRepository(url,proxyOn);
        return new RestUserFederationProvider(session, model, repository, roleIsSync, rolePrefix, upperCase, attributesIsSync, proxyOn);
    }

    @Override
    public SynchronizationResult sync(KeycloakSessionFactory sessionFactory, String realmId, UserStorageProviderModel model) {
        return syncImpl(Optional.empty(), sessionFactory, realmId, model);
    }

    @Override
    public SynchronizationResult syncSince(Date date, KeycloakSessionFactory sessionFactory, String realmId, UserStorageProviderModel model) {
        return syncImpl(Optional.of(date), sessionFactory, realmId, model);
    }

    protected SynchronizationResult syncImpl(Optional<Date> date, KeycloakSessionFactory sessionFactory, final String realmId, final ComponentModel fedModel) {
        final String url = fedModel.getConfig().getFirst(PROPERTY_URL);
        final Boolean proxyOn = Boolean.valueOf(fedModel.getConfig().getFirst(PROXY_ENABLED));
        UserRepository repository = new UserRepository(url,proxyOn);

        List<UserDto> users;

        if (date.isPresent()) {
            //Since
            users = repository.getUpdatedUsers(date.get());
        } else {
            //Every
            users = repository.getUsers();
        }

        final SynchronizationResult syncResult = new SynchronizationResult();

        class BooleanHolder {
            private boolean value = true;
        }
        final BooleanHolder exists = new BooleanHolder();



        for (final UserDto restUser : users) {

            try {
                // Process each user in it's own transaction to avoid global fail
                KeycloakModelUtils.runJobInTransaction(sessionFactory, new KeycloakSessionTask() {
                    @Override
                    public void run(KeycloakSession session) {
                        RestUserFederationProvider ldapFedProvider =(RestUserFederationProvider) session.getProvider(UserStorageProvider.class, fedModel);
                        RealmModel currentRealm = session.realms().getRealm(realmId);

                        String username = restUser.getUserName();
                        exists.value = true;
                        UserModel currentUser = session.userLocalStorage().getUserByUsername(username, currentRealm);

                        if (currentUser == null) {
                            // Add new user to Keycloak
                            exists.value = false;
                            ldapFedProvider.importUserFromRest(session, currentRealm, restUser);
                            syncResult.increaseAdded();

                        } else {
                            if ((fedModel.getId().equals(currentUser.getFederationLink())) && (restUser.getUserName().equals(currentUser.getUsername()))) {

                                // Update keycloak user
                                ldapFedProvider.updateUserFromRest(currentRealm, restUser, currentUser);

                                session.userCache().evict(currentRealm, currentUser);
                                log.debugf("Updated user from REST: %s", currentUser.getUsername());
                                syncResult.increaseUpdated();
                            } else {
                                log.warnf("User '%s' is not updated during sync as he already exists in Keycloak database but is not linked to federation provider '%s'", username, fedModel.getName());
                                syncResult.increaseFailed();
                            }
                        }
                    }

                });
            } catch (ModelException me) {
                log.error("Failed during import user from REST", me);
                syncResult.increaseFailed();

                // Remove user if we already added him during this transaction
                if (!exists.value) {
                    KeycloakModelUtils.runJobInTransaction(sessionFactory, new KeycloakSessionTask() {

                        @Override
                        public void run(KeycloakSession session) {
                            RestUserFederationProvider ldapFedProvider =(RestUserFederationProvider) session.getProvider(UserStorageProvider.class, fedModel);
                            RealmModel currentRealm = session.realms().getRealm(realmId);

                            if (restUser.getUserName() != null) {
                                UserModel existing = session.userLocalStorage().getUserByUsername(restUser.getUserName(), currentRealm);
                                if (existing != null) {
                                    UserCache userCache = session.userCache();
                                    if (userCache != null) {
                                        userCache.evict(currentRealm, existing);
                                    }
                                    session.userLocalStorage().removeUser(currentRealm, existing);
                                }
                            }
                        }
                    });
                }
            }
        }

        return syncResult;
    }
}
