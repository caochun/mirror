package gov.objectlibrary.server;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

@Configuration
class SecurityConfiguration {
    @Bean PasswordEncoder passwords() { return new BCryptPasswordEncoder(12); }
    @Bean AuthenticationManager authenticationManager(Accounts accounts, PasswordEncoder passwords) {
        var provider = new DaoAuthenticationProvider(accounts);
        provider.setPasswordEncoder(passwords);
        return new ProviderManager(provider);
    }
    @Bean HttpSessionSecurityContextRepository contexts() { return new HttpSessionSecurityContextRepository(); }
    @Bean SecurityFilterChain security(HttpSecurity http, HttpSessionSecurityContextRepository contexts) throws Exception {
        return http.securityContext(c -> c.securityContextRepository(contexts))
                .authorizeHttpRequests(a -> a.requestMatchers("/", "/index.html", "/dashboard.html", "/receiver.html", "/assets/**", "/favicon.ico",
                                "/dashboard", "/workbench", "/people", "/people/**", "/tags", "/content-examples", "/reminders", "/reminders/**", "/r/**",
                                "/reading", "/api/receiver/**", "/api/health", "/api/auth/csrf", "/api/auth/login").permitAll()
                        .requestMatchers("/api/**").authenticated().anyRequest().denyAll())
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> {
                    res.setStatus(401); res.setContentType("application/json;charset=UTF-8");
                    res.getWriter().write("{\"message\":\"请先登录\"}");
                }).accessDeniedHandler((req, res, ex) -> {
                    res.setStatus(403); res.setContentType("application/json;charset=UTF-8");
                    res.getWriter().write("{\"message\":\"无权限或会话校验失败，请刷新后重试\"}");
                }))
                .logout(l -> l.logoutUrl("/api/auth/logout").logoutSuccessHandler((req, res, auth) -> res.setStatus(204)))
                .build();
    }
}
