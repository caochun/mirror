package gov.mirror.app;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Properties;

/** Explicit local acceptance environment. Generated secrets stay in the ignored runtime directory. */
public final class LocalSetup {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(directory);
        Path configuration = directory.resolve("application.properties");
        Path credentials = directory.resolve("credentials.txt");
        if (Files.exists(configuration) || Files.exists(credentials)) {
            throw new IllegalStateException("Local configuration already exists; it will not be overwritten");
        }
        var properties = new Properties();
        properties.setProperty("mirror.foundry.jdbc-url", "jdbc:h2:file:" + directory.resolve("foundry"));
        properties.setProperty("mirror.foundry.seed-demo", "true");
        var encoder = new BCryptPasswordEncoder();
        var secrets = new StringBuilder("Local synthetic catalog environment. Keep these credentials private.\n");
        String[] names = {"admin", "editor", "reader", "other"};
        String[] roles = {"ADMIN", "DIRECTORY,TAG_EDITOR", "READER", "DIRECTORY,TAG_EDITOR"};
        var random = new SecureRandom();
        for (int i = 0; i < names.length; i++) {
            byte[] bytes = new byte[18];
            random.nextBytes(bytes);
            String password = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            String prefix = "mirror.security.users[" + i + "].";
            properties.setProperty(prefix + "username", names[i]);
            properties.setProperty(prefix + "password-hash", encoder.encode(password));
            properties.setProperty(prefix + "roles", roles[i]);
            properties.setProperty(prefix + "organization", i == 3 ? "org2" : "org");
            properties.setProperty(prefix + "organizations", i == 3 ? "org2" : "org");
            secrets.append(names[i]).append("=").append(password).append('\n');
        }
        var permissions = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
        Files.createFile(configuration, permissions);
        Files.createFile(credentials, permissions);
        try (var output = Files.newOutputStream(configuration)) { properties.store(output, "Mirror local environment - never commit"); }
        Files.writeString(credentials, secrets.toString());
        System.out.println("Created local configuration and credentials in " + directory);
    }
}
