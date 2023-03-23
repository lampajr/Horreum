<div align="center">

![hyperfoil-logo](./webapp/public/hyperfoil-logo.png)

<a href="https://github.com/Hyperfoil/Horreum/issues"><img alt="GitHub issues" src="https://img.shields.io/github/issues/Hyperfoil/Horreum"></a>
<a href="https://github.com/Hyperfoil/Horreum/fork"><img alt="GitHub forks" src="https://img.shields.io/github/forks/Hyperfoil/Horreum"></a>
<a href="https://github.com/Hyperfoil/Horreum/stargazers"><img alt="GitHub stars" src="https://img.shields.io/github/stars/Hyperfoil/Horreum"></a>
<a href="https://github.com/Hyperfoil/Horreum//blob/main/LICENSE"><img alt="GitHub license" src="https://img.shields.io/github/license/Hyperfoil/Horreum"></a> 
</div>

---
## What is Horreum?

Horreum is a service for storing performance data and regression analysis.

Please, visit our project website: 

[https://horreum.hyperfoil.io](https://horreum.hyperfoil.io)

for more information.

## Prerequisites

* [Java 11](https://adoptium.net/temurin/releases/?version=11)
* [Apache Maven 3.8](https://maven.apache.org/)
* [Keycloak](https://www.keycloak.org/)
* [Grafana](https://grafana.com/)
* [PostgreSQL 12+](https://www.postgresql.org/)

> **_NOTE:_**  On Windows 10, currently it is only possible to develop Horreum using a WSL environment

### Local development with Docker Compose

We have prepared a `docker-compose` script to setup Keycloak, PostgreSQL and Grafana using following command.

```bash
docker-compose -p horreum -f infra/docker-compose.yml up -d
```
and after a few moments everything should be up and ready. The script will create some example users.

> **_NOTE:_**  On Windows 10, please ensure you are running [Docker Desktop](https://www.docker.com/products/docker-desktop/) with "Use WSL 2 based engine" enabled

### Local development with Podman

We have prepared a `podman-compose` script to setup Keycloak, PostgreSQL and Grafana using following command.

```bash
./infra/podman-compose.sh
```

and after a few moments everything should be up and ready. The script will create some example users.

Install:

``` bash
dnf install -y podman podman-plugins podman-compose podman-docker
```

Please, enable the socket environment in order to run the test suite:

``` bash
systemctl --user enable --now podman.socket
export DOCKER_HOST=unix:///run/user/${UID}/podman/podman.sock
export TESTCONTAINERS_RYUK_DISABLED=true
```

Shutdown:

``` bash
podman-compose -p horreum -f infra/docker-compose.yml down
```

### Example configuration

You can preload Horreum with some example data with

```bash
./infra/example-configuration.sh
```

once Horreum is running.

## Credentials

### Horreum

Horreum is running on [localhost:8080](http://localhost:8080)

| Role | Name | Password |
| ---- | ---- | -------- |
| User | `user` | `secret` |


### Keycloak

Keycloak is running on [localhost:8180](http://localhost:8180)

| Role | Name | Password | Realm |
| ---- | ---- | -------- | ----- |
| Admin | `admin` | `secret` | |
| User | `user` | `secret` | `horreum` |

## Getting Started with development server

To run with test cases do

```bash
mvn package
mvn quarkus:dev
```

To run without test cases do

```bash
mvn -DskipTests=true package
mvn -Dquarkus.test.continuous-testing=disabled quarkus:dev
```

> **_NOTE:_**  On Windows 10 the first build is very slow. running in dev mode may require the quinoa timeout increasing

```bash
mvn quarkus:dev -Dquarkus.quinoa.dev-server.check-timeout=120000
```

## Get Access

* For the create-react-app live code server [localhost:3000](http://localhost:3000)
* For the Quarkus development code server   [localhost:8080](http://localhost:8080)

### Troubleshooting development infrastructure

If PostgreSQL container fails to start try removing the volume using:

```bash
podman volume rm horreum_horreum_pg12
```

If you are having problems with Grafana login after restarting the infrastructure wipe out old environment files using:

```bash
rm horreum-backend/.env .grafana
```

## Operator

The [Horreum operator](https://github.com/Hyperfoil/horreum-operator) can help to setup a production environment.

## 🧑‍💻 Contributing

Contributions to `Horreum` Please check our [CONTRIBUTING.md](./CONTRIBUTING.md)

### If you have any idea or doubt 👇

* [Ask a question](https://github.com/Hyperfoil/Horreum/discussions)
* [Raise an issue](https://github.com/Hyperfoil/Horreum/issues)
* [Feature request](https://github.com/Hyperfoil/Horreum/issues)
* [Code submission](https://github.com/Hyperfoil/Horreum/pulls)

Contribution is the best way to support and get involved in community !

Please, consult our [Code of Conduct](./CODE_OF_CONDUCT.md) policies for interacting in our
community.

Consider giving the project a [star](https://github.com/Hyperfoil/Horreum/stargazers) on
[GitHub](https://github.com/Hyperfoil/Horreum/) if you find it useful.

## License

[Apache-2.0 license](https://opensource.org/licenses/Apache-2.0)

## Thanks to all the Contributors ❤️

<img src="https://contrib.rocks/image?repo=Hyperfoil/Horreum" />
