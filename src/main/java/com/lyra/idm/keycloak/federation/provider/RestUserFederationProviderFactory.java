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

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Remote user federation provider factory.
 * <p>
 * http://www.keycloak.org/docs/3.4/server_development/
 */
@JBossLog
public class RestUserFederationProviderFactory implements UserStorageProviderFactory<RestUserFederationProvider>, ImportSynchronization {


    public static final String PROVIDER_NAME = "Rest User Federation";

    public static final String PROPERTY_URL = "url";
    public static final String ATTR_SYNC = "attr_sync";
    public static final String ROLE_SYNC = "role_sync";
    public static final String PREFIX = "prefix";
    public static final String UPPERCASE = "uppercase";
    public static final String PROXY_ENABLED = "proxy_enabled";
    public static final String PROXY_HOST = "proxyHost";
    public static final String PROXY_PORT = "proxyPort";
    public static final String UNCHECK_FEDERATION = "uncheck_federation";
    public static final String RESET_ACTIONS = "reset_action";
    public static final String NOT_CREATE_USERS = "not_create_users";


    protected static final List<ProviderConfigProperty> configMetadata;

    static {
        configMetadata = ProviderConfigurationBuilder.create()
                .property().name(PROPERTY_URL)
                .type(ProviderConfigProperty.STRING_TYPE)
                .label("Remote User Information Url")
                .defaultValue("https://")
                .helpText("Remote repository url")
                .add()
                .property().name(PREFIX)
                .type(ProviderConfigProperty.STRING_TYPE)
                .label("Define prefix for roles and attributes")
                .helpText("size >=3.")
                .add()
                .property().name(UPPERCASE)
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .defaultValue("true")
                .label("Uppercase role/attribute name")
                .helpText("Convert remote roles/attributes to uppercase")
                .add()
                .property().name(ROLE_SYNC)
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .defaultValue("true")
                .label("Enable roles synchronization")
                .helpText("Apply and create remote roles")
                .add()
                .property().name(ATTR_SYNC)
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .defaultValue("true")
                .label("Enable attributes synchronization")
                .helpText("Apply remote attributes")
                .add()
                .property().name(UNCHECK_FEDERATION)
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .defaultValue("false")
                .label("Uncheck federation origin")
                .helpText("Change attributes or roles without controlling federation origin.")
                .add()
                .property().name(NOT_CREATE_USERS)
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .defaultValue("false")
                .label("Not create new users")
                .helpText("Only update existed users")
                .add()
                .property().name(RESET_ACTIONS)
                .type(ProviderConfigProperty.STRING_TYPE)
                .label("Actions to apply after user creation")
                .helpText("ex: UPDATE_PASSWORD,VERIFY_EMAIL")
                .defaultValue("")
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
        final String prefix = config.getConfig().getFirst(PREFIX);
        final Boolean roleIsSync = Boolean.valueOf(config.getConfig().getFirst(ROLE_SYNC));
        final Boolean attributeIsSync = Boolean.valueOf(config.getConfig().getFirst(ATTR_SYNC));
        final Boolean proxyOn = Boolean.valueOf(config.getConfig().getFirst(PROXY_ENABLED));
        final List<String> resetActions = formatResetActions(config.getConfig().getFirst(RESET_ACTIONS));

        boolean valid = true;
        String comment = "";

        final List<String> RESET_ACTIONS_LIST = Stream.of(UserModel.RequiredAction.values())
                .map(Enum::name)
                .collect(Collectors.toList());


        List<String> notFound = resetActions.stream().filter(a -> !"".equals(a) && !RESET_ACTIONS_LIST.contains(a)).collect(Collectors.toList());
        if (notFound.size() > 0) {
            valid = false;
            comment = "Please check actions: " + String.join("", notFound);
        }


        if (url != null && url.length() < 10) {
            valid = false;
            comment = "Please check the url.";
        }

        if ((roleIsSync || attributeIsSync) && prefix != null && prefix.length() < 3) {
            valid = false;
            comment = "Please check prefix size.";
        }

        if ((roleIsSync || attributeIsSync) && prefix == null) {
            valid = false;
            comment = "Please define prefix.";
        }

        if (proxyOn && System.getProperty("http." + PROXY_HOST) == null && System.getProperty("https." + PROXY_HOST) == null) {
            valid = false;
            comment = "Please check 'http(s).proxyHost' property.";
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
        final String rolePrefix = model.getConfig().getFirst(PREFIX);
        final Boolean roleIsSync = Boolean.valueOf(model.getConfig().getFirst(ROLE_SYNC));
        final Boolean upperCase = Boolean.valueOf(model.getConfig().getFirst(UPPERCASE));
        final Boolean proxyOn = Boolean.valueOf(model.getConfig().getFirst(PROXY_ENABLED));
        final Boolean uncheckFederation = Boolean.valueOf(model.getConfig().getFirst(UNCHECK_FEDERATION));
        final Boolean notCreateUsers = Boolean.valueOf(model.getConfig().getFirst(NOT_CREATE_USERS));
        final List<String> resetActions = formatResetActions(model.getConfig().getFirst(RESET_ACTIONS));

        UserRepository repository = new UserRepository(url, proxyOn);
        return new RestUserFederationProvider(session, model, repository, roleIsSync,
                rolePrefix, upperCase, attributesIsSync,
                proxyOn, uncheckFederation, resetActions,
                notCreateUsers
        );
    }

    @Override
    public SynchronizationResult sync(KeycloakSessionFactory sessionFactory, String realmId, UserStorageProviderModel model) {
        return syncImpl(Optional.empty(), sessionFactory, realmId, model);
    }

    @Override
    public SynchronizationResult syncSince(Date date, KeycloakSessionFactory sessionFactory, String realmId, UserStorageProviderModel model) {
        return syncImpl(Optional.of(date), sessionFactory, realmId, model);
    }

    private List<String> formatResetActions(String resetActions) {
        final String SEP = ",";
        String value = resetActions == null ? "" : resetActions;
        return Arrays.asList(value.split(SEP)).stream().map(String::trim).collect(Collectors.toList());
    }


    protected SynchronizationResult syncImpl(Optional<Date> date, KeycloakSessionFactory sessionFactory, final String realmId, final ComponentModel fedModel) {
        final String url = fedModel.getConfig().getFirst(PROPERTY_URL);
        final Boolean proxyOn = Boolean.valueOf(fedModel.getConfig().getFirst(PROXY_ENABLED));
        final Boolean uncheck = Boolean.valueOf(fedModel.getConfig().getFirst(UNCHECK_FEDERATION));
        final Boolean notCreateUsers = Boolean.valueOf(fedModel.getConfig().getFirst(NOT_CREATE_USERS));
        UserRepository repository = new UserRepository(url, proxyOn);

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
                        RestUserFederationProvider restFedProvider = (RestUserFederationProvider) session.getProvider(UserStorageProvider.class, fedModel);
                        RealmModel currentRealm = session.realms().getRealm(realmId);

                        String username = restUser.getUserName();
                        exists.value = true;
                        UserModel currentUser = session.userLocalStorage().getUserByUsername(username, currentRealm);

                        if (currentUser == null) {

                            if(!notCreateUsers){
                                // Add new user to Keycloak
                                exists.value = false;
                                restFedProvider.importUserFromRest(session, currentRealm, restUser, uncheck);
                                syncResult.increaseAdded();
                            }else{
                                log.debug("notCreateUsers mode: Skip this users " + username);
                            }
                        } else {
                            //Uncheck mode ignore federation origin
                            if ((fedModel.getId().equals(currentUser.getFederationLink()) || uncheck) && restUser.getUserName().equals(currentUser.getUsername())) {

                                // Update keycloak user
                                restFedProvider.updateUserFromRest(currentRealm, restUser, currentUser, uncheck);

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
                            //RestUserFederationProvider restFedProvider = (RestUserFederationProvider) session.getProvider(UserStorageProvider.class, fedModel);
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
