package dev.bhavya.anvesh;

import dev.bhavya.anvesh.config.AnveshProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
@EnableConfigurationProperties(AnveshProperties.class)
public class AnveshApplication {
    public static void main(String[] args) {
        SpringApplication.run(AnveshApplication.class, args);
    }
}
