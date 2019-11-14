package com.lyra.idm.keycloak.federation.api.user;


import com.lyra.idm.keycloak.federation.model.UserDto;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.util.Set;

/**
 * Stub Service class to be used with RestEasy to access user rest api
 */
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface UserService {
    String FORMAT = "yyyy-MM-dd'T'HH:mm'Z'";

    /**
     * Full users
     *
     * @return Users
     */
    @GET
    @Path("/full")
    Set<UserDto> getUsers(@HeaderParam("X-Page") int page, @HeaderParam("X-Per-Page") int perPage);

    /**
     * Update users
     *
     * @param date see FORMAT in UTC
     */
    @GET
    @Path("/updated/{from}")
    Set<UserDto> getUpdatedUsers(@PathParam("from") String date,@HeaderParam("X-Page") int page,@HeaderParam("X-Per-Page") int perPage);
}
