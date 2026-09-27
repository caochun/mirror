package gov.objectlibrary.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MirrorApplication {
    @org.springframework.context.annotation.Bean
    java.time.Clock businessClock() {
        return java.time.Clock.systemUTC();
    }

    public static void main(String[] args) { SpringApplication.run(MirrorApplication.class, args); }
}
