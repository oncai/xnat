package org.nrg.xnat.initialization;

import com.fasterxml.jackson.core.type.TypeReference;
import org.nrg.config.exceptions.SiteConfigurationException;
import org.nrg.framework.services.SerializerService;
import org.nrg.xdat.preferences.InitializerSiteConfiguration;
import org.nrg.xnat.security.*;
import org.nrg.xnat.security.alias.AliasTokenAuthenticationProvider;
import org.nrg.xnat.security.config.AuthenticationProviderAggregator;
import org.nrg.xnat.security.config.AuthenticationProviderConfigurator;
import org.nrg.xnat.security.config.DatabaseAuthenticationProviderConfigurator;
import org.nrg.xnat.security.config.LdapAuthenticationProviderConfigurator;
import org.nrg.xnat.security.userdetailsservices.XnatDatabaseUserDetailsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportResource;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.access.AccessDecisionVoter;
import org.springframework.security.access.vote.AuthenticatedVoter;
import org.springframework.security.access.vote.RoleVoter;
import org.springframework.security.access.vote.UnanimousBased;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.channel.ChannelDecisionManagerImpl;
import org.springframework.security.web.access.channel.InsecureChannelProcessor;
import org.springframework.security.web.access.channel.SecureChannelProcessor;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.ConcurrentSessionControlAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.session.ConcurrentSessionFilter;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

@Configuration
@ImportResource("WEB-INF/conf/xnat-security.xml")
public class SecurityConfig {
    @Bean
    public UnanimousBased unanimousBased() {
        final RoleVoter voter = new RoleVoter();
        voter.setRolePrefix("ROLE_");
        final List<AccessDecisionVoter<?>> voters = new ArrayList<>();
        voters.add(voter);
        voters.add(new AuthenticatedVoter());
        return new UnanimousBased(voters);
    }

    @Bean
    public OnXnatLogin logUserLogin() {
        return new OnXnatLogin();
    }

    @Bean
    public AuthenticationFailureHandler authFailure() {
        return new XnatUrlAuthenticationFailureHandler("/app/template/Login.vm?failed=true", "/app/template/PostRegister.vm");
    }

    @Bean
    public XnatAuthenticationEntryPoint loginUrlAuthenticationEntryPoint() {
        final XnatAuthenticationEntryPoint entryPoint = new XnatAuthenticationEntryPoint("/app/template/Login.vm");
        entryPoint.setDataPaths(Arrays.asList("/data/**", "/REST/**", "/fs/**"));
        entryPoint.setInteractiveAgents(Arrays.asList(".*MSIE.*", ".*Mozilla.*", ".*AppleWebKit.*", ".*Opera.*"));
        return entryPoint;
    }

    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    @Bean
    public ConcurrentSessionFilter concurrencyFilter(final SessionRegistry sessionRegistry) {
        return new ConcurrentSessionFilter(sessionRegistry, "/app/template/Login.vm");
    }

    @Bean
    public ConcurrentSessionControlAuthenticationStrategy sas(final SessionRegistry sessionRegistry) throws SiteConfigurationException {
        final ConcurrentSessionControlAuthenticationStrategy strategy = new ConcurrentSessionControlAuthenticationStrategy(sessionRegistry);
        strategy.setMaximumSessions(_configuration.getConcurrentMaxSessions());
        strategy.setExceptionIfMaximumExceeded(true);
        return strategy;
    }

    @Bean
    public LogoutFilter logoutFilter() {
        final XnatLogoutSuccessHandler logoutSuccessHandler = new XnatLogoutSuccessHandler();
        logoutSuccessHandler.setOpenXnatLogoutSuccessUrl("/");
        logoutSuccessHandler.setSecuredXnatLogoutSuccessUrl("/app/template/Login.vm");
        final SecurityContextLogoutHandler securityContextLogoutHandler = new SecurityContextLogoutHandler();
        securityContextLogoutHandler.setInvalidateHttpSession(true);
        final XnatLogoutHandler xnatLogoutHandler = new XnatLogoutHandler();
        final LogoutFilter      filter            = new LogoutFilter(logoutSuccessHandler, securityContextLogoutHandler, xnatLogoutHandler);
        filter.setFilterProcessesUrl("/app/action/LogoutUser");
        return filter;
    }

    @Bean
    public FilterSecurityInterceptorBeanPostProcessor filterSecurityInterceptorBeanPostProcessor() throws IOException {
        final Resource resource = RESOURCE_LOADER.getResource("classpath:META-INF/xnat/security/configured-urls.yaml");
        try (final InputStream inputStream = resource.getInputStream()) {
            final HashMap<String, ArrayList<String>>         urlMap        = _serializer.deserializeYaml(inputStream, TYPE_REFERENCE);
            final FilterSecurityInterceptorBeanPostProcessor postProcessor = new FilterSecurityInterceptorBeanPostProcessor();
            postProcessor.setOpenUrls(urlMap.get("openUrls"));
            postProcessor.setAdminUrls(urlMap.get("adminUrls"));
            return postProcessor;
        }
    }

    @Bean
    public TranslatingChannelProcessingFilter channelProcessingFilter() throws SiteConfigurationException {
        final ChannelDecisionManagerImpl decisionManager = new ChannelDecisionManagerImpl();
        decisionManager.setChannelProcessors(Arrays.asList(new SecureChannelProcessor(), new InsecureChannelProcessor()));
        final TranslatingChannelProcessingFilter filter = new TranslatingChannelProcessingFilter();
        filter.setChannelDecisionManager(decisionManager);
        filter.setRequiredChannel(_configuration.getSecurityChannel());
        return filter;
    }

    @Bean
    public AliasTokenAuthenticationProvider aliasTokenAuthenticationProvider() {
        return new AliasTokenAuthenticationProvider();
    }

    @Bean
    public DatabaseAuthenticationProviderConfigurator dbConfigurator() {
        return new DatabaseAuthenticationProviderConfigurator();
    }

    @Bean
    public LdapAuthenticationProviderConfigurator ldapConfigurator() {
        return new LdapAuthenticationProviderConfigurator();
    }

    @Bean
    public AuthenticationProviderAggregator providerAggregator(final List<AuthenticationProvider> providers, final List<AuthenticationProviderConfigurator> configurators) {
        final Map<String, AuthenticationProviderConfigurator> configuratorMap = new HashMap<>();
        for (final AuthenticationProviderConfigurator configurator : configurators) {
            configuratorMap.put(configurator.getConfiguratorId(), configurator);
        }

        return new AuthenticationProviderAggregator(providers, configuratorMap);
    }

    @Bean(name = {"org.springframework.security.authenticationManager", "customAuthenticationManager"})
    public XnatProviderManager customAuthenticationManager(final AuthenticationProviderAggregator aggregator) {
        return new XnatProviderManager(aggregator);
    }

    @Bean
    public XnatAuthenticationFilter customAuthenticationFilter(final XnatProviderManager providerManager,
                                                               final AuthenticationSuccessHandler successHandler,
                                                               final AuthenticationFailureHandler failureHandler,
                                                               final SessionAuthenticationStrategy sas) {
        final XnatAuthenticationFilter filter = new XnatAuthenticationFilter();
        filter.setAuthenticationManager(providerManager);
        filter.setAuthenticationSuccessHandler(successHandler);
        filter.setAuthenticationFailureHandler(failureHandler);
        filter.setSessionAuthenticationStrategy(sas);
        return filter;
    }

    @Bean
    public XnatBasicAuthenticationFilter customBasicAuthenticationFilter(final XnatProviderManager providerManager,
                                                                         final AuthenticationEntryPoint entryPoint,
                                                                         final SessionAuthenticationStrategy sas) {
        final XnatBasicAuthenticationFilter filter = new XnatBasicAuthenticationFilter(providerManager, entryPoint);
        filter.setSessionAuthenticationStrategy(sas);
        return filter;
    }

    @Bean
    public XnatExpiredPasswordFilter expiredPasswordFilter() {
        final XnatExpiredPasswordFilter filter = new XnatExpiredPasswordFilter();
        filter.setChangePasswordPath("/app/template/XDATScreen_UpdateUser.vm");
        filter.setChangePasswordDestination("/app/action/ModifyPassword");
        filter.setLogoutDestination("/app/action/LogoutUser");
        filter.setLoginPath("/app/template/Login.vm");
        filter.setLoginDestination("/app/action/XDATLoginUser");
        filter.setInactiveAccountPath("/app/template/InactiveAccount.vm");
        filter.setInactiveAccountDestination("/app/action/XnatInactiveAccount");
        filter.setEmailVerificationPath("/app/template/VerifyEmail.vm");
        filter.setEmailVerificationDestination("/data/services/sendEmailVerification");
        return filter;
    }

    @Bean
    public XnatInitCheckFilter xnatInitCheckFilter() {
        final XnatInitCheckFilter filter = new XnatInitCheckFilter();
        filter.setInitializationPaths(Arrays.asList("/xapi/siteConfig/batch", "/xapi/notifications/smtp"));
        filter.setConfigurationPath("/setup");
        filter.setNonAdminErrorPath("/app/template/Unconfigured.vm");
        filter.setExemptedPaths(Arrays.asList("/app/template/XDATScreen_UpdateUser.vm", "/app/action/ModifyPassword", "/app/template/Login.vm", "/style/app.css", "/login"));
        return filter;
    }

    @Bean
    public XnatDatabaseUserDetailsService customDatabaseService(final DataSource dataSource) {
        final XnatDatabaseUserDetailsService service = new XnatDatabaseUserDetailsService();
        service.setDataSource(dataSource);
        return service;
    }

    private static final ResourceLoader                                    RESOURCE_LOADER = new DefaultResourceLoader();
    private static final TypeReference<HashMap<String, ArrayList<String>>> TYPE_REFERENCE  = new TypeReference<HashMap<String, ArrayList<String>>>() {
    };

    @Autowired
    @Lazy
    private InitializerSiteConfiguration _configuration;

    @Autowired
    @Lazy
    private SerializerService _serializer;
}