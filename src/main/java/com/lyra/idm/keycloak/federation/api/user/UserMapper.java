package com.lyra.idm.keycloak.federation.api.user;

import com.lyra.idm.keycloak.federation.model.UserDto;

import java.util.Set;

/**
 * Methods for users synchronization
 */
public interface UserMapper {

    /**
     * Full users
     *
     * @return Users
     */
    Set<UserDto> getUsers();

    /**
     * Updated users
     *
     * @return Users
     */
    Set<UserDto> getUpdatedUsers(String date);
}
