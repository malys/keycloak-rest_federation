package com.lyra.idm.keycloak.federation.api.user;


import com.lyra.idm.keycloak.federation.model.UserDto;
import com.lyra.idm.keycloak.federation.provider.RestUserFederationProviderFactory;
import lombok.Getter;
import lombok.extern.jbosslog.JBossLog;
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;
import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.jboss.resteasy.client.jaxrs.ResteasyWebTarget;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.GenericType;
import java.io.IOException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Remote repository to load remote user data from UserService using REST
 */
@JBossLog
@Getter
public class UserRepository implements UserMapper {
    public static int PER_PAGE = 400;
    private String url;
    private Boolean proxyOn;

    public UserRepository(String url, Boolean proxyOn) {
        this.url = url;
        this.proxyOn = proxyOn;
    }

    private static UserServiceObject buildClient(String uri, Boolean proxyOn) {
        String portTemp = Optional.ofNullable(System.getProperty("http." + RestUserFederationProviderFactory.PROXY_PORT))
                .filter(s -> s != null && !s.isEmpty()).orElse(System.getProperty("https." + RestUserFederationProviderFactory.PROXY_PORT));

        final String host = Optional.ofNullable(System.getProperty("http." + RestUserFederationProviderFactory.PROXY_HOST))
                .filter(s -> s != null && !s.isEmpty()).orElse(System.getProperty("https." + RestUserFederationProviderFactory.PROXY_HOST));
        final int port = portTemp != null ? Integer.parseInt(portTemp) : 8080;

        ResteasyClientBuilder builder = new ResteasyClientBuilder();

        if (proxyOn) {
            builder.defaultProxy(host, port);
        }

        ResteasyClient client = builder.disableTrustManager().build();
        ResteasyWebTarget target = client.target(uri);

        return target
                .proxyBuilder(UserServiceObject.class)
                .classloader(UserServiceObject.class.getClassLoader())
                .build();

    }

    /**
     * Full users
     *
     * @return Users
     */
    public Set<UserDto> getUsers() {
        Set<UserDto> result = new HashSet<>();
        try {
            UserResponseObject remoteUsers = buildClient(url, proxyOn).getUsers(1, PER_PAGE);
            result = new ObjectMapper().readValue(remoteUsers.body(), new TypeReference<Set<UserDto>>() {
            });
            int totalPages = getTotalPage(remoteUsers);
            if (totalPages > 1) {
                for (int i = 2; i <= totalPages; i++) {
                    Set<UserDto> added=new ObjectMapper().readValue(
                            buildClient(url, proxyOn).getUsers(i, PER_PAGE).body(),
                            new TypeReference<Set<UserDto>>() {
                            });
                    log.debug("Process page:" + i + " and adding " + added.size() + " elements.");
                    result.addAll(added);
                }
            }
        } catch (WebApplicationException | IOException e) {
            log.warn("Received a non OK answer from upstream migration service", e);
        }

        return result;
    }

    /**
     * Updated users
     *
     * @param date
     * @return Users
     */
    public Set<UserDto> getUpdatedUsers(String date) {
        Set<UserDto> result = new HashSet<>();
        try {
            UserResponseObject remoteUsers = buildClient(url, proxyOn).getUpdatedUsers(date, 1, PER_PAGE);
            result = new ObjectMapper().readValue(remoteUsers.body(), new TypeReference<Set<UserDto>>() {
            });
            int totalPages = getTotalPage(remoteUsers);
            if (getTotalPage(remoteUsers) > 1) {
                for (int i = 2; i <= totalPages; i++) {
                    result.addAll(new ObjectMapper().readValue(buildClient(url, proxyOn).getUpdatedUsers(date, i, PER_PAGE).body(), new TypeReference<Set<UserDto>>() {
                    }));
                }
            }
        } catch (WebApplicationException | IOException e) {
            log.warn("Received a non OK answer from upstream migration service", e);
        }

        return result;
    }

    private int getTotalPage(UserResponseObject response) {
        int result = 0;
        if (response != null && response.totalPages() != null && response.page() != null) {
            try {
                int totalPages = Integer.parseInt(response.totalPages());
                if (totalPages > Integer.parseInt(response.page())) {
                    result = totalPages;
                }
            } catch (NumberFormatException e) {
                log.warn("Paging header not well formed", e);
            }
        }
        return result;
    }
}
