package gov.mirror.app;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {
    @Bean
    BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService users(MirrorAccounts accounts) {
        return username -> {
            try {
                var account = accounts.require(username);
                return User.withUsername(account.username()).password(account.passwordHash())
                        .roles(account.roles().toArray(String[]::new)).build();
            } catch (SecurityException failure) {
                throw new org.springframework.security.core.userdetails.UsernameNotFoundException("Unknown account");
            }
        };
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(requests -> requests
                        .requestMatchers("/", "/index.html", "/assets/**", "/api/session", "/api/login", "/error").permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form.loginProcessingUrl("/api/login")
                        .successHandler((request, response, auth) -> response.setStatus(204))
                        .failureHandler((request, response, failure) -> response.sendError(401)))
                .logout(logout -> logout.logoutUrl("/api/logout")
                        .logoutSuccessHandler((request, response, auth) -> response.setStatus(204)))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, failure) -> response.sendError(401))
                        .accessDeniedHandler((request, response, failure) -> response.sendError(403)))
                .requestCache(cache -> cache.disable())
                .build();
    }
}
