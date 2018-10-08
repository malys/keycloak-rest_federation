# RH-SSO federation

RH-SSO federation to synchronize users from **business REST API** to RH-SSO. 

## Versioning

Refer to [Semantic Versioning 2.0.0](http://semver.org/).

## Deployment on Maven repository

 ```bash
 mvn clean deploy -Pnexus
 ```
**Nexus** maven profile defines:

    <nexus.url.release>${nexus.url}/content/repositories/releases</nexus.url.release>
    <nexus.url.snapshot>${nexus.url}/content/repositories/snapshots</nexus.url.snapshot>

## Testing

### Platform

```bash
cd docker
docker-compose up
```

2 containers will be up:

* Keycloak (see in ./docker/servers/Dockerfile, *APP_VERSION* variable for Keycloak version)
    * Realm: myRealm, Client: testRegister & testPublic  and Rest API federation (see *functions.sh*) have been created and configured
    * The first synchronization is automatic  
* Rest api mock  (see /docker/servers/json-server/db.json/routes.json to define mocked data)

### Manual tests

Open [Admin console](http://127.0.0.1:5080/auth/admin/) (user/pw: nicko/...), 

* in *myRealm*, users1 and users2 have to be created.
* "Synchronized changed users" have to disable *user1*


## Architecture

## Model

See [UserDto](./src/main/java/com/lyra/idm/keycloak/federation/model/UserDto.java)


## Class diagram

### Generation

```bash
 jar D:\prog\plantuml-dependency-cli-1.4.0-jar-with-dependencies.jar  -o h:\plantuml.txt -b D:\Developpement\archi\idm\rh-sso-federation\src -dp (?=.*\b(keycloak)\b)(?!.*\b(models)\b)(.+)
```

### Diagram 

![](./classDiagram.png)

## Dependencies

### RH-SSO

Change RH-SSO version in *pom.xml* and maven will update inherited dependencies.

```xml
    <parent>
        <groupId>com.lyra.idm.keycloak</groupId>
        <artifactId>parent</artifactId>
        <version>7.2.0.GA</version>
    </parent>
```

## Features

### Configuration

![](./federation.png)

| Name                                      | Description                                               |
|---                                        |---                                                        |   
| By-pass                                   |Disabling federation internal process                      |
| Remote User Information Url               |Rest API endpoint providing users                          |
| Define prefix for roles and attributes    |Add prefix to synchronized attributes or roles             |
| Uppercase role/attribute name             |Force upper case for synchronized attributes or roles      |
| Enable roles synchronization              |Import roles during synchronization                        |
| Enable attributes synchronization         |Import attributes during synchronization                   |
| Uncheck federation origin                 |Not verify federation user source to synchronize elements  |
| Not create new users                      |Update only existed users                                  |
| Actions to apply after user creation      |Send link corresponding to reset action by email           |
| Use Proxy                                 |Enable proxy use                                           |

## Best practices

* Implements [UserService](./src/main/java/com/lyra/idm/keycloak/federation/api/user/UserService.java)
* Don't remove users, disable them (synchronization contraints).
* Use prefix for roles and attributes
* Enable *Periodic Changed Users Sync*
* Use *Uncheck federation origin* caustiously
* Custom email template *executeActions.ftl*

## Release Notes

### 1.1.0

* Fire reset actions after user creation
* Uncheck mode to update user from different federation
* Force prefix
* Prefix use for roles and attributes
* Format roles and attributes
* Add "not create new users" mode

### 1.0.0

* users and  updated users synchronization
* Proxy management
* Role and attributes synchronization
* Prefix and upper case for roles

## Author

Sylvain M. for [Lyra](https://lyra.com).

