package gov.mirror.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

@SpringBootApplication
@EnableConfigurationProperties({FoundryProperties.class, AccountProperties.class})
public class MirrorApplication {
    public static void main(String[] args) {
        SpringApplication.run(MirrorApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ready(ApplicationReadyEvent event) {
        var environment = event.getApplicationContext().getEnvironment();
        String port = environment.getProperty("local.server.port", environment.getProperty("server.port", "8090"));
        System.out.println("Mirror: http://127.0.0.1:" + port + " (Spring Boot / Foundry API)");
    }
}
