package fm.sazonov.keysetrepro;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
public abstract class AbstractPostgresTest {

    @Configuration
    static class PostgresConfig {

        @Bean
        @ServiceConnection
        @SuppressWarnings("resource")
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:17-alpine")
                    .withDatabaseName("keyset")
                    .withUsername("keyset")
                    .withPassword("keyset")
                    .withReuse(true);
        }
    }
}
