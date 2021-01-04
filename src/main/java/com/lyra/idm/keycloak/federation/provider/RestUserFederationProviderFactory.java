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
import com.lyra.idm.keycloak.federation.api.user.UserService;
import com.lyra.idm.keycloak.federation.model.UserDto;
import lombok.extern.jbosslog.JBossLog;
import org.apache.commons.lang.StringUtils;
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
import org.keycloak.representations.IDToken;
import org.keycloak.storage.UserStorageProvider;
import org.keycloak.storage.UserStorageProviderFactory;
import org.keycloak.storage.UserStorageProviderModel;
import org.keycloak.storage.user.ImportSynchronization;
import org.keycloak.storage.user.SynchronizationResult;

import java.lang.reflect.Field;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
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
    public static final String PASSWORD_SYNC = "password_sync";
    public static final String PASSWORD_HASH_ALGORITHM = "password_hash_algorithm";
    public static final String PASSWORD_HASH_ITERATION = "password_hash_iteration";
    protected static final String[] SUPPORTED_HASH_ALGORITHM = {"SHA256", "PBKDF2-SHA256"};

    public static final String ROLE_CLIENT_SYNC = "role_client_sync";
    public static final String PREFIX = "prefix";
    public static final String UPPERCASE = "uppercase";
    public static final String PROXY_ENABLED = "proxy_enabled";
    public static final String PROXY_HOST = "proxyHost";
    public static final String PROXY_PORT = "proxyPort";
    public static final String UNCHECK_FEDERATION = "uncheck_federation";
    public static final String RESET_ACTIONS = "reset_action";
    public static final String NOT_CREATE_USERS = "not_create_users";
    public static final String BY_PASS = "by_pass";
    public static final String PUBLIC_URL = "public_url";
    public static final int URL_MIN_LENGHT = 10;
    public static final int PREFIX_MIN_LENGTH = 2;
    protected static final Set<String> OIDC_ATTRIBUTES;
    protected static final List<ProviderConfigProperty> configMetadata;
    private static final TimeZone TZ = TimeZone.getTimeZone("UTC");

    static {
        // Get OIDC standard attributes
        Set<String> tmp = new HashSet<>();
        Field[] fs = IDToken.class.getDeclaredFields();
        for (Field f : fs) {
            try {
                tmp.add((String) f.get(null));
            } catch (IllegalAccessException | NullPointerException e) {
                log.debug("Error processing IDToken attribute: " + f.getName());
            }
        }
        OIDC_ATTRIBUTES = tmp;


        configMetadata = ProviderConfigurationBuilder.create()
                .property().name(BY_PASS)
                .type(ProviderConfigProperty.STRING_TYPE)
                .label("By-pass")
                .helpText("Disabling federation based on context. ex: ${COLLECT_DISABLE_FEDERATION} or 'true'")
                .add()
                .property().name(PROPERTY_URL)
                .type(ProviderConfigProperty.STRING_TYPE)
                .label("Remote User Information Url")
                .defaultValue("https://")
                .helpText("Remote repository url")
                .add()
                .property().name(PREFIX)
                .type(ProviderConfigProperty.STRING_TYPE)
                .label("Define prefix for roles and attributes")
                .helpText("size >=2.HTTP Header will gain weight.")
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
                .property().name(ROLE_CLIENT_SYNC)
                .type(ProviderConfigProperty.STRING_TYPE)
                .label("Client name to affect roles")
                .helpText("`Empty` to affect roles to realm, `Client name` to affect them to specific client.")
                .add()
                .property().name(ATTR_SYNC)
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .defaultValue("true")
                .label("Enable attributes synchronization")
                .helpText("Apply remote attributes")
                .add()
                //Password synchronization
                .property().name(PASSWORD_SYNC)
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .defaultValue("false")
                .label("Enable password synchronization")
                .helpText("Apply remote password password")
                .add()
                .property().name(PASSWORD_HASH_ALGORITHM)
                .type(ProviderConfigProperty.STRING_TYPE)
                .defaultValue("SHA256")
                .label("Algorithm for hashing password")
                .helpText("SHA256, PBKDF2-SHA256")

                .add()
                .property().name(PASSWORD_HASH_ITERATION)
                .type(ProviderConfigProperty.STRING_TYPE)
                .defaultValue("500000")
                .label("Number of iteration for hashing password")
                .add()
                //
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
                .helpText("ex: VERIFY_EMAIL,welcome.ftl(UPDATE_PASSWORD)")
                .defaultValue("")
                .add()
                .property().name(PROXY_ENABLED)
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .label("Use Proxy")
                .helpText("Add Java Properties: http(s).proxyHost,http(s).proxyPort")
                .add()
                .property().name(PUBLIC_URL)
                .type(ProviderConfigProperty.STRING_TYPE)
                .label("Public URL of IDM")
                .helpText("Send email with public reset link.")
                .defaultValue("${RHSSO_PUBLIC_URL}")
                .add()
                .build();
    }

    /***
     * Format date to ISO
     * @param date
     * @return
     */
    public static String formatDate(Date date) {
        DateFormat DF = new SimpleDateFormat(UserService.FORMAT);// Quoted "Z" to indicate UTC, no timezone offset
        DF.setTimeZone(TZ);
        return DF.format(date);
    }

    private static List<String> formatResetActions(String resetActions) {
        final String SEP = ",";
        List<String> result;

        if (!StringUtils.isBlank(resetActions)) {
            result = Arrays.asList(resetActions.split(SEP)).stream().map(String::trim).collect(Collectors.toList());
        } else {
            result = new ArrayList<>();
        }
        return result;
    }

    public static <T> Predicate<T> distinctByKey(Function<? super T, ?> keyExtractor) {
        Map<Object, Boolean> seen = new ConcurrentHashMap<>();
        return t -> seen.putIfAbsent(keyExtractor.apply(t), Boolean.TRUE) == null;
    }

    public static <R> Predicate<R> not(Predicate<R> predicate) {
        return predicate.negate();
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return configMetadata;
    }

    @Override
    public void validateConfiguration(KeycloakSession session, RealmModel realm, ComponentModel config) throws ComponentValidationException {
        final String url = EnvSubstitutor.envStrSubstitutor.replace(config.getConfig().getFirst(PROPERTY_URL));
        final String prefix = EnvSubstitutor.envStrSubstitutor.replace(config.getConfig().getFirst(PREFIX));
        final Boolean roleIsSync = Boolean.valueOf(EnvSubstitutor.envStrSubstitutor.replace(config.getConfig().getFirst(ROLE_SYNC)));
        final Boolean attributeIsSync = Boolean.valueOf(EnvSubstitutor.envStrSubstitutor.replace(config.getConfig().getFirst(ATTR_SYNC)));
        final Boolean proxyOn = Boolean.valueOf(EnvSubstitutor.envStrSubstitutor.replace(config.getConfig().getFirst(PROXY_ENABLED)));
        final List<String> resetActions = formatResetActions(config.getConfig().getFirst(RESET_ACTIONS));
        final String publicURL = EnvSubstitutor.envStrSubstitutor.replace(config.getConfig().getFirst(PUBLIC_URL));

        final Boolean passwordIsSync = Boolean.valueOf(EnvSubstitutor.envStrSubstitutor.replace(config.getConfig().getFirst(PASSWORD_SYNC)));
        final String passwordAlgorithm = EnvSubstitutor.envStrSubstitutor.replace(config.getConfig().getFirst(PASSWORD_HASH_ALGORITHM));
        final String passwordIteration = EnvSubstitutor.envStrSubstitutor.replace(config.getConfig().getFirst(PASSWORD_HASH_ITERATION));

        boolean valid = true;
        String comment = "";

        final List<String> RESET_ACTIONS_LIST = Stream.of(UserModel.RequiredAction.values())
                .map(Enum::name)
                .collect(Collectors.toList());


        if (config.getConfig().getFirst(BY_PASS) != null) {
            try {
                EnvSubstitutor.envStrSubstitutor.replace(config.getConfig().getFirst(BY_PASS));
            } catch (IllegalArgumentException e) {
                valid = false;
                comment = "By pass parameter '" + config.getConfig().getFirst(BY_PASS).replaceAll("[${}]", "") + "' not exists.";
                log.error(e);

            } catch (Exception e) {
                valid = false;
                comment = "Please check by pass parameter.";
            }
        }

        List<String> notFound = resetActions.stream().filter(a -> !"".equals(a) && !RESET_ACTIONS_LIST.contains(a) && a.indexOf(".ftl") == -1).collect(Collectors.toList());
        if (!notFound.isEmpty()) {
            valid = false;
            comment = "Please check actions: " + String.join("", notFound);
        }


        if (url != null && url.length() < URL_MIN_LENGHT) {
            valid = false;
            comment = "Please check the url.";
        }

        if (publicURL != null && publicURL.length() < URL_MIN_LENGHT) {
            valid = false;
            comment = "Please check the public RH-SSO url. ex: https://xxx/auth";
        }

        if ((roleIsSync || attributeIsSync) && prefix != null && prefix.length() < PREFIX_MIN_LENGTH) {
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

        if (passwordIsSync == true) {
            final List<String> ALGOS = Arrays.asList(SUPPORTED_HASH_ALGORITHM);
            if (passwordAlgorithm == null) {
                valid = false;
                comment = "Please select algorithm for hashing password: " + String.join(",", ALGOS) + " .";
            } else if (!ALGOS.contains(passwordAlgorithm.toUpperCase())) {
                valid = false;
                comment = comment + "Not supported algorithm or syntax error (" + passwordAlgorithm + "). ";
            }
            try {
                Integer.parseInt(passwordIteration);
            } catch (NumberFormatException e) {
                valid = false;
                comment = comment + "Please insert an integer. ";
            }
        }

        log.debugf("validating module config %s", valid);

        if (Boolean.valueOf(EnvSubstitutor.envStrSubstitutor.replace(config.getConfig().getFirst(BY_PASS)))) {
            valid = false;
            comment = "BY_PASS is enabled. It's not possible to update configuration on this platform";
        }

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
        final String url = EnvSubstitutor.envStrSubstitutor.replace(model.getConfig().getFirst(PROPERTY_URL));
        final Boolean attributesIsSync = Boolean.valueOf(EnvSubstitutor.envStrSubstitutor.replace(model.getConfig().getFirst(ATTR_SYNC)));
        final String rolePrefix = EnvSubstitutor.envStrSubstitutor.replace(model.getConfig().getFirst(PREFIX));
        final Boolean roleIsSync = Boolean.valueOf(EnvSubstitutor.envStrSubstitutor.replace(model.getConfig().getFirst(ROLE_SYNC)));
        final String roleClient = EnvSubstitutor.envStrSubstitutor.replace(model.getConfig().getFirst(ROLE_CLIENT_SYNC));
        final Boolean upperCase = Boolean.valueOf(EnvSubstitutor.envStrSubstitutor.replace(model.getConfig().getFirst(UPPERCASE)));
        final Boolean proxyOn = Boolean.valueOf(EnvSubstitutor.envStrSubstitutor.replace(model.getConfig().getFirst(PROXY_ENABLED)));
        final Boolean uncheckFederation = Boolean.valueOf(EnvSubstitutor.envStrSubstitutor.replace(model.getConfig().getFirst(UNCHECK_FEDERATION)));
        final Boolean notCreateUsers = Boolean.valueOf(EnvSubstitutor.envStrSubstitutor.replace(model.getConfig().getFirst(NOT_CREATE_USERS)));
        final List<String> resetActions = formatResetActions(model.getConfig().getFirst(RESET_ACTIONS));
        final String publicUrl = EnvSubstitutor.envStrSubstitutor.replace(model.getConfig().getFirst(PUBLIC_URL));

        final Boolean passwordIsSync = Boolean.valueOf(EnvSubstitutor.envStrSubstitutor.replace(model.getConfig().getFirst(PASSWORD_SYNC)));
        String passwordAlgorithm = "";
        Integer passwordIteration = 0;
        String passwordIterationStr = EnvSubstitutor.envStrSubstitutor.replace(model.getConfig().getFirst(PASSWORD_HASH_ITERATION));
        if (passwordIsSync == true && passwordAlgorithm != null && passwordIterationStr != null) {
            passwordAlgorithm = EnvSubstitutor.envStrSubstitutor.replace(model.getConfig().getFirst(PASSWORD_HASH_ALGORITHM)).toLowerCase();
            passwordIteration = Integer.parseInt(passwordIterationStr);
        }


        UserRepository repository = new UserRepository(url, proxyOn);
        return new RestUserFederationProvider(session, model, repository,
                roleIsSync, roleClient,
                rolePrefix, upperCase, attributesIsSync,
                passwordIsSync, passwordAlgorithm, passwordIteration,
                proxyOn, uncheckFederation,
                resetActions,
                notCreateUsers,
                publicUrl
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

    private Set<UserDto> protector(Set<UserDto> list, final SynchronizationResult syncResult) {
        Set<UserDto> result = list.stream()
                .filter(Objects::nonNull)
                .filter(distinctByKey(u -> u.getEmail()))
                .filter(distinctByKey(u -> u.getUserName()))
                .collect(Collectors.toCollection(()
                        -> new TreeSet<>(Comparator.comparing(UserDto::getUserName))));

        list.stream()
                .distinct()
                .filter(not(result::contains))
                .collect(Collectors.toList())
                .forEach(u -> {
                    log.warn("Ignored user: name->" + u.getUserName() + " email->" + u.getEmail());
                    syncResult.increaseFailed();
                });

        return result;

    }

    protected SynchronizationResult syncImpl(Optional<Date> date, KeycloakSessionFactory sessionFactory, final String realmId, final ComponentModel fedModel) {
        final String url = EnvSubstitutor.envStrSubstitutor.replace(fedModel.getConfig().getFirst(PROPERTY_URL));
        final Boolean proxyOn = Boolean.valueOf(EnvSubstitutor.envStrSubstitutor.replace(fedModel.getConfig().getFirst(PROXY_ENABLED)));
        final Boolean uncheck = Boolean.valueOf(EnvSubstitutor.envStrSubstitutor.replace(fedModel.getConfig().getFirst(UNCHECK_FEDERATION)));
        final Boolean notCreateUsers = Boolean.valueOf(EnvSubstitutor.envStrSubstitutor.replace(fedModel.getConfig().getFirst(NOT_CREATE_USERS)));

        UserRepository repository = new UserRepository(url, proxyOn);
        final SynchronizationResult syncResult = new SynchronizationResult();
        Set<UserDto> users;

        Boolean byPass = false;
        try {
            byPass = Boolean.valueOf(EnvSubstitutor.envStrSubstitutor.replace(fedModel.getConfig().getFirst(BY_PASS)));
        } catch (IllegalArgumentException e) {
            log.warn("By pass parameter '" + fedModel.getConfig().getFirst(BY_PASS).replaceAll("[${}]", "") + "' not exists.");
        }

        if (!byPass) {
            //Federation enabled
            if (date.isPresent()) {
                users = repository.getUpdatedUsers(formatDate(date.get()));
            } else {
                //Every
                users = repository.getUsers();
            }

            class BooleanHolder {
                private boolean value = true;
            }
            final BooleanHolder exists = new BooleanHolder();

            if (users != null) {
                log.infof("[%s] Federation starting for '%s' users", fedModel.getName(), users.size());
                Set<UserDto> usersClean = protector(users, syncResult);
                for (final UserDto restUser : usersClean) {
                    if (restUser.getUserName() != null && restUser.getEmail() != null) {
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

                                        if (!notCreateUsers) {

                                            UserModel storageCurrentUser = session.userStorageManager().getUserByUsername(username, currentRealm);

                                            if (storageCurrentUser != null) {
                                                //He's in DB
                                                UserCache userCache = session.userCache();
                                                if (userCache != null) {
                                                    userCache.evict(currentRealm, storageCurrentUser);
                                                }
                                                log.debugf("User %s exists. Evict him", username);

                                            } else {

                                                // Add new user to Keycloak
                                                exists.value = false;

                                                restFedProvider.importUserFromRest(session, currentRealm, restUser, uncheck);
                                                syncResult.increaseAdded();
                                            }

                                        } else {
                                            log.debugf("notCreateUsers mode: Skip this users " + username);
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
                            log.warn("Failed during import user from REST", me);
                            syncResult.increaseFailed();

                            // Remove user if we already added him during this transaction
                            if (!exists.value) {
                                KeycloakModelUtils.runJobInTransaction(sessionFactory, new KeycloakSessionTask() {

                                    @Override
                                    public void run(KeycloakSession session) {
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
                        } catch (IllegalStateException ie) {
                            log.error("Failed during import user from REST", ie);
                            syncResult.increaseFailed();
                        }
                    } else {
                        syncResult.increaseFailed();
                        log.warnf("Missing attributes (user,email,password ?) for %s (%s)", restUser.getUserName() != null ? restUser.getUserName() : "", restUser.getEmail() != null ? restUser.getEmail() : "");
                    }
                }
            } else {
                log.errorf("Users is null. Check networking issue (see logs).");
            }


        } else {
            //Federation by passed
            log.warnf("By Pass Federation '%s'", PROVIDER_NAME);
            //throw new RuntimeException("By pass Federation");
            return SynchronizationResult.empty();
        }

        log.infof("[%s] Federation ended: '%s'", fedModel.getName(), syncResult.toString());

        return syncResult;
    }

    public void onCreate(KeycloakSession session, RealmModel realm, ComponentModel model) {
        byPass(session, model);
    }

    public void onUpdate(KeycloakSession session, RealmModel realm, ComponentModel oldModel, ComponentModel newModel) {
        byPass(session, newModel);
    }

    private void byPass(KeycloakSession session, ComponentModel model) {
        if (Boolean.valueOf(EnvSubstitutor.envStrSubstitutor.replace(model.getConfig().getFirst(BY_PASS)))) {
            ((UserStorageProviderModel) model).setEnabled(false);
            log.warnf("BY_PASS is enabled. Federation is locally disabled.");
        }
    }
}
