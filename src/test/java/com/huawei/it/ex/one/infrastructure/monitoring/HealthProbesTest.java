/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;

import reactor.core.publisher.Mono;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.actuate.autoconfigure.availability.AvailabilityProbesAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.data.redis.RedisHealthContributorAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.data.redis.RedisReactiveHealthContributorAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.jackson.JacksonEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.health.HealthContributorAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.jdbc.DataSourceHealthContributorAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementContextAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.web.servlet.ServletManagementContextAutoConfiguration;
import org.springframework.boot.actuate.data.redis.RedisHealthIndicator;
import org.springframework.boot.actuate.data.redis.RedisReactiveHealthIndicator;
import org.springframework.boot.actuate.jdbc.DataSourceHealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.availability.ApplicationAvailabilityAutoConfiguration;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.metadata.DataSourcePoolMetadataProvidersConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.ClusterInfo;
import org.springframework.data.redis.connection.ReactiveRedisClusterConnection;
import org.springframework.data.redis.connection.ReactiveRedisConnection;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.ReactiveServerCommands;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisServerCommands;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

import javax.sql.DataSource;

class HealthProbesTest {
    private static final Path DOCUMENT = Path.of("docs/health-probes.md");
    private static final String READINESS = "/actuator/health/readiness";
    private static final String DEPENDENCIES = "/actuator/health/dependencies";
    private final ObjectMapper json = new ObjectMapper();
    private final HikariDataSource dataSource = mock(HikariDataSource.class);
    private final Connection connection = mock(Connection.class);
    private final TestRedisConnectionFactory redis = mock(TestRedisConnectionFactory.class);
    private final ReactiveRedisConnection redisConnection = mock(ReactiveRedisConnection.class);
    private final ReactiveServerCommands redisCommands = mock(ReactiveServerCommands.class);
    private final RedisConnection syncConnection = mock(RedisConnection.class);
    private final RedisServerCommands syncCommands = mock(RedisServerCommands.class);

    @BeforeEach
    void healthyConnections() throws SQLException {
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("private-database-product");
        when(connection.isValid(0)).thenReturn(true);
        when(redis.getReactiveConnection()).thenReturn(redisConnection);
        when(redisConnection.serverCommands()).thenReturn(redisCommands);
        when(redisConnection.closeLater()).thenReturn(Mono.empty());
        Properties info = new Properties();
        info.setProperty("redis_version", "private-redis-version");
        when(redisCommands.info("server")).thenReturn(Mono.just(info));
        when(redis.getConnection()).thenReturn(syncConnection);
        when(syncConnection.serverCommands()).thenReturn(syncCommands);
        when(syncCommands.info()).thenReturn(info);
    }

    @Test
    void repositoryDefaultsDoNotProbeDependenciesOrExposeExtraEndpoints() {
        withConnections(baseRunner()).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getProperty("management.health.db.enabled")).isEqualTo("false");
            assertThat(context.getEnvironment().getProperty("management.health.redis.enabled")).isEqualTo("false");
            assertThat(context).doesNotHaveBean("dbHealthContributor").doesNotHaveBean("redisHealthContributor");
            assertThat(response(context, "/actuator/health", 200).path("status").asText()).isEqualTo("UP");
            status(context, DEPENDENCIES, 404);
            status(context, "/actuator/env", 404);
            verifyNoInteractions(connection, redisConnection, redisCommands);
            verify(dataSource, never()).getConnection();
            verify(redis, never()).getReactiveConnection();
            verify(redis, never()).getConnection();
        });
    }

    @Test
    void documentedConfigurationUsesRealIndicatorsAndReleasesConnections() throws IOException {
        configuredRunner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean("dbHealthContributor")).isInstanceOf(DataSourceHealthIndicator.class);
            assertThat(context.getBean("redisHealthContributor")).isInstanceOf(RedisReactiveHealthIndicator.class);
            assertThat(response(context, DEPENDENCIES, 200)).isEqualTo(documentedJson().get(1));
            verify(connection).isValid(0);
            verify(connection, times(2)).close();
            verify(connection, never()).createStatement();
            verify(redisCommands).info("server");
            verify(redisConnection).closeLater();
            verify(redis, never()).getConnection();
            status(context, "/actuator/env", 404);
        });
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void repositoryFactoryDeclarationUsesSynchronousHealthCheck(boolean dbDown, boolean redisDown)
            throws IOException, SQLException {
        when(connection.isValid(0)).thenReturn(!dbDown);
        if (redisDown) {
            when(syncCommands.info()).thenThrow(new IllegalStateException("NOPERM private-acl-detail"));
        }
        synchronousRunner().run(context -> {
            assertThat(context.getBean("redisHealthContributor")).isInstanceOf(RedisHealthIndicator.class);
            AvailabilityChangeEvent.publish(context, LivenessState.CORRECT);
            AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
            clearInvocations(dataSource, connection, redis, syncConnection, syncCommands);
            assertThat(response(context, READINESS, 200)).isEqualTo(documentedJson().getFirst());
            verifyNoInteractions(dataSource, connection, redis, syncConnection, syncCommands);
            int httpStatus = dbDown || redisDown ? 503 : 200;
            assertThat(response(context, DEPENDENCIES, httpStatus))
                    .isEqualTo(dependencyResult(dbDown ? "DOWN" : "UP", redisDown ? "DOWN" : "UP"));
            verify(syncCommands).info();
            verify(syncConnection).close();
            verify(connection, times(2)).close();
            verify(redis, never()).getReactiveConnection();
            assertThat(response(context, "/actuator/health", httpStatus).path("status").asText())
                    .isEqualTo(dbDown || redisDown ? "DOWN" : "UP");
        });
    }

    @Test
    void synchronousRedisAcquisitionFailureReturnsDown() throws IOException {
        when(redis.getConnection()).thenThrow(new RedisConnectionFailureException("private-redis-host"));
        synchronousRunner().run(context -> {
            assertThat(response(context, DEPENDENCIES, 503)).isEqualTo(documentedJson().get(2));
            verifyNoInteractions(syncConnection, syncCommands, redisConnection, redisCommands);
        });
    }

    @ParameterizedTest
    @CsvSource({"ok,200,UP", "fail,503,DOWN"})
    void synchronousClusterHealthAndRelease(String state, int httpStatus, String redisStatus) throws IOException {
        RedisClusterConnection cluster = mock(RedisClusterConnection.class);
        when(redis.getConnection()).thenReturn(cluster);
        when(cluster.clusterGetClusterInfo()).thenReturn(clusterInfo(state));
        synchronousRunner().run(context -> {
            assertThat(response(context, DEPENDENCIES, httpStatus)).isEqualTo(dependencyResult("UP", redisStatus));
            verify(cluster).clusterGetClusterInfo();
            verify(cluster).close();
            verifyNoInteractions(syncConnection, syncCommands, redisConnection, redisCommands);
        });
    }

    @ParameterizedTest
    @CsvSource({"true,false", "false,true", "true,true"})
    void dependencyFailuresDoNotAffectReadinessButAffectRootHealth(boolean dbDown, boolean redisDown)
            throws IOException, SQLException {
        when(connection.isValid(0)).thenReturn(!dbDown);
        if (redisDown) {
            when(redisCommands.info("server"))
                    .thenReturn(Mono.error(new IllegalStateException("private-redis-error")));
        }
        configuredRunner().run(context -> {
            AvailabilityChangeEvent.publish(context, LivenessState.CORRECT);
            AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
            clearInvocations(dataSource, connection, redis, redisConnection, redisCommands);
            assertThat(response(context, READINESS, 200)).isEqualTo(documentedJson().getFirst());
            verifyNoInteractions(dataSource, connection, redis, redisConnection, redisCommands);
            JsonNode result = response(context, DEPENDENCIES, 503);
            assertThat(result).isEqualTo(dependencyResult(dbDown ? "DOWN" : "UP", redisDown ? "DOWN" : "UP"));
            verify(connection, times(2)).close();
            verify(redisConnection).closeLater();
            assertThat(response(context, "/actuator/health", 503).path("status").asText()).isEqualTo("DOWN");
        });
    }

    @Test
    void readinessFollowsApplicationLifecycleWithoutDependencyCalls() throws IOException {
        configuredRunner().run(context -> {
            clearInvocations(dataSource, connection, redis, redisConnection, redisCommands);
            AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
            assertThat(response(context, READINESS, 503)).isEqualTo(json.readTree("{\"status\":\"OUT_OF_SERVICE\"}"));
            AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
            assertThat(response(context, READINESS, 200)).isEqualTo(documentedJson().getFirst());
            AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
            status(context, READINESS, 503);
            verifyNoInteractions(dataSource, connection, redis, redisConnection, redisCommands);
        });
    }

    @Test
    void databaseAcquisitionFailureReturnsDownWithoutLeakingDetails() throws IOException, SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLException("private-jdbc-url-and-credentials"));
        configuredRunner().run(context -> {
            assertThat(response(context, DEPENDENCIES, 503)).isEqualTo(dependencyResult("DOWN", "UP"));
            verify(dataSource).getConnection();
            verifyNoInteractions(connection);
            verify(redisConnection).closeLater();
        });
    }

    @Test
    void databaseValidationExceptionStillReleasesConnections() throws IOException, SQLException {
        when(connection.isValid(0)).thenThrow(new SQLException("private-validation-error"));
        configuredRunner().run(context -> {
            assertThat(response(context, DEPENDENCIES, 503)).isEqualTo(dependencyResult("DOWN", "UP"));
            verify(connection, times(2)).close();
        });
    }

    @Test
    void redisAcquisitionFailureReturnsDownWithoutLeakingDetails() throws IOException {
        when(redis.getReactiveConnection()).thenThrow(new RedisConnectionFailureException("private-redis-host"));
        configuredRunner().run(context -> {
            assertThat(response(context, DEPENDENCIES, 503)).isEqualTo(documentedJson().get(2));
            verifyNoInteractions(redisConnection, redisCommands);
        });
    }

    @Test
    void redisAclFailureStillReleasesConnection() throws IOException {
        when(redisCommands.info("server"))
                .thenReturn(Mono.error(new IllegalStateException("NOPERM private-acl-detail")));
        configuredRunner().run(context -> {
            assertThat(response(context, DEPENDENCIES, 503)).isEqualTo(documentedJson().get(2));
            verify(redisConnection).closeLater();
        });
    }

    @ParameterizedTest
    @CsvSource({"ok,200,UP", "fail,503,DOWN"})
    void clusterUsesClusterInfoInsteadOfStandaloneInfo(String clusterState, int httpStatus, String redisStatus)
            throws IOException {
        ReactiveRedisClusterConnection cluster = mock(ReactiveRedisClusterConnection.class);
        when(cluster.clusterGetClusterInfo()).thenReturn(Mono.just(clusterInfo(clusterState)));
        when(cluster.closeLater()).thenReturn(Mono.empty());
        when(redis.getReactiveConnection()).thenReturn(cluster);
        configuredRunner().run(context -> {
            assertThat(response(context, DEPENDENCIES, httpStatus)).isEqualTo(dependencyResult("UP", redisStatus));
            verify(cluster).clusterGetClusterInfo();
            verify(cluster).closeLater();
            verifyNoInteractions(redisConnection, redisCommands);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"db", "redis"})
    void disabledRequiredIndicatorFailsStartup(String indicator) throws IOException {
        configuredRunner().withPropertyValues("management.health." + indicator + ".enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining(
                            "Included health contributor '" + indicator + "' in group 'dependencies' does not exist");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"db", "redis"})
    void missingRequiredConnectionBeanFailsStartup(String missing) throws IOException {
        WebApplicationContextRunner runner = baseRunner().withPropertyValues(documentedProperties());
        runner = "db".equals(missing)
                ? runner.withBean("redisConnectionFactory", TestRedisConnectionFactory.class, () -> redis)
                : runner.withBean("dataSource", DataSource.class, () -> dataSource);
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining(
                    "Included health contributor '" + missing + "' in group 'dependencies' does not exist");
        });
    }

    @Test
    void documentationExamplesAndLocalLinksAreValid() throws IOException {
        assertThat(documentedProperties()).hasSize(9);
        assertThat(documentedJson()).hasSize(3);
        var links = Pattern.compile("\\]\\(([^)]+)\\)").matcher(Files.readString(DOCUMENT));
        while (links.find()) {
            String target = links.group(1).split("#", 2)[0];
            if (!target.isEmpty() && !target.contains(":")) {
                assertThat(DOCUMENT.getParent().resolve(target).normalize()).exists();
            }
        }
        for (Path entry : List.of(Path.of("README.md"), Path.of("docs/performance-testing-guide.md"))) {
            assertThat(Files.readString(entry)).contains("health-probes.md");
        }
    }

    private WebApplicationContextRunner configuredRunner() throws IOException {
        return withConnections(baseRunner()).withPropertyValues(documentedProperties());
    }

    private WebApplicationContextRunner synchronousRunner() throws IOException {
        return baseRunner().withPropertyValues(documentedProperties())
                .withBean("dataSource", DataSource.class, () -> dataSource)
                .withBean("redisConnectionFactory", RedisConnectionFactory.class, () -> redis);
    }

    private WebApplicationContextRunner withConnections(WebApplicationContextRunner runner) {
        return runner.withBean("dataSource", DataSource.class, () -> dataSource)
                .withBean("redisConnectionFactory", TestRedisConnectionFactory.class, () -> redis);
    }

    private WebApplicationContextRunner baseRunner() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class,
                        ApplicationAvailabilityAutoConfiguration.class, JacksonAutoConfiguration.class,
                        HttpMessageConvertersAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
                        WebMvcAutoConfiguration.class, ManagementContextAutoConfiguration.class,
                        ServletManagementContextAutoConfiguration.class,
                        EndpointAutoConfiguration.class, JacksonEndpointAutoConfiguration.class,
                        WebEndpointAutoConfiguration.class, HealthEndpointAutoConfiguration.class,
                        HealthContributorAutoConfiguration.class, AvailabilityProbesAutoConfiguration.class,
                        DataSourceHealthContributorAutoConfiguration.class, RedisHealthContributorAutoConfiguration.class,
                        RedisReactiveHealthContributorAutoConfiguration.class))
                .withUserConfiguration(DataSourcePoolMetadataProvidersConfiguration.class)
                .withInitializer(context -> {
                    try {
                        new YamlPropertySourceLoader().load("repository-defaults",
                                new FileSystemResource("src/main/resources/application.yml"))
                                .forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
                    } catch (IOException failure) {
                        throw new UncheckedIOException(failure);
                    }
                });
    }

    private JsonNode response(WebApplicationContext context, String path, int expectedStatus) throws Exception {
        var response = MockMvcBuilders.webAppContextSetup(context).build().perform(get(path)).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(expectedStatus);
        return json.readTree(response.getContentAsByteArray());
    }

    private void status(WebApplicationContext context, String path, int expectedStatus) throws Exception {
        var response = MockMvcBuilders.webAppContextSetup(context).build().perform(get(path)).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(expectedStatus);
    }

    private JsonNode dependencyResult(String dbStatus, String redisStatus) {
        var result = json.createObjectNode().put("status", "UP".equals(dbStatus) && "UP".equals(redisStatus) ? "UP" : "DOWN");
        var components = result.putObject("components");
        components.putObject("db").put("status", dbStatus);
        components.putObject("redis").put("status", redisStatus);
        return result;
    }

    private ClusterInfo clusterInfo(String state) {
        Properties info = new Properties();
        info.setProperty("cluster_state", state);
        info.setProperty("cluster_size", "3");
        info.setProperty("cluster_slots_ok", "16384");
        info.setProperty("cluster_slots_fail", "0");
        return new ClusterInfo(info);
    }

    private String[] documentedProperties() throws IOException {
        var blocks = Pattern.compile("(?s)```properties\\s+(.*?)```").matcher(Files.readString(DOCUMENT));
        assertThat(blocks.find()).isTrue();
        return blocks.group(1).lines().map(String::trim).filter(line -> !line.isEmpty()).toArray(String[]::new);
    }

    private List<JsonNode> documentedJson() throws IOException {
        var blocks = Pattern.compile("(?s)```json\\s+(.*?)```").matcher(Files.readString(DOCUMENT));
        List<JsonNode> result = new ArrayList<>();
        while (blocks.find()) {
            result.add(json.readTree(blocks.group(1)));
        }
        return List.copyOf(result);
    }

    private interface TestRedisConnectionFactory extends RedisConnectionFactory, ReactiveRedisConnectionFactory {
    }
}
