package com.lyra.idm.keycloak.federation.api.user;


import com.lyra.idm.keycloak.federation.model.UserDto;
import com.lyra.idm.keycloak.federation.provider.RestUserFederationProviderFactory;
import lombok.Getter;
import lombok.extern.jbosslog.JBossLog;
import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.jboss.resteasy.client.jaxrs.ResteasyWebTarget;

import javax.ws.rs.WebApplicationException;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * Remote repository to load remote user data from UserService using REST
 */
@JBossLog
@Getter
public class UserRepository  implements UserMapper {

    private String url;
    private UserService remoteService;
    public UserRepository(String url,Boolean proxyOn) {
        this.url = url;
        this.remoteService = buildClient(url,proxyOn);
    }

    private static UserService buildClient(String uri,Boolean proxyOn) {
        String portTemp=Optional.ofNullable(System.getProperty("http." + RestUserFederationProviderFactory.PROXY_PORT))
                .filter(s -> s != null && !s.isEmpty()).orElse(System.getProperty("https." + RestUserFederationProviderFactory.PROXY_PORT));

        final String host= Optional.ofNullable(System.getProperty("http." + RestUserFederationProviderFactory.PROXY_HOST))
                .filter(s -> s != null && !s.isEmpty()).orElse(System.getProperty("https." + RestUserFederationProviderFactory.PROXY_HOST));
        final int port=portTemp!=null ? Integer.parseInt(portTemp):8080;
        final String scheme=System.getProperty("http." + RestUserFederationProviderFactory.PROXY_HOST) !=null ? "http":"https";

        ResteasyClientBuilder builder=new ResteasyClientBuilder();

        if(proxyOn){
            builder.defaultProxy(host,port,scheme);
        }

        ResteasyClient client = builder.disableTrustManager().build();
        ResteasyWebTarget target =  client.target(uri);

        return target
                .proxyBuilder(UserService.class)
                .classloader(UserService.class.getClassLoader())
                .build();

    }

    public List<UserDto> getUsers(){
        List<UserDto> remoteUsers = null;
        try {
            remoteUsers =remoteService.getUsers();
        } catch (WebApplicationException e) {
            log.warn("Received a non OK answer from upstream migration service", e);
        }

        return remoteUsers;
    }

    public List<UserDto> getUpdatedUsers(String date){
        List<UserDto> remoteUsers = null;
        try {
            remoteUsers =remoteService.getUpdatedUsers(date);
        } catch (WebApplicationException e) {
            log.warn("Received a non OK answer from upstream migration service", e);
        }

        return remoteUsers;
    }
}
