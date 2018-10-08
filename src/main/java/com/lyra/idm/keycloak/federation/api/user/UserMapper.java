package com.lyra.idm.keycloak.federation.api.user;

import com.lyra.idm.keycloak.federation.model.UserDto;

import java.util.Date;
import java.util.List;

public interface UserMapper {

    List<UserDto> getUsers();

    List<UserDto> getUpdatedUsers(String date);
}
