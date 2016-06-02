package org.nrg.xnat.configuration;

import org.apache.commons.lang3.StringUtils;
import org.nrg.config.exceptions.SiteConfigurationException;
import org.nrg.xdat.preferences.InitializerSiteConfiguration;
import org.nrg.xdat.security.services.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.inject.Inject;

import static org.nrg.xdat.security.services.FeatureRepositoryServiceI.DEFAULT_FEATURE_REPO_SERVICE;
import static org.nrg.xdat.security.services.FeatureServiceI.DEFAULT_FEATURE_SERVICE;
import static org.nrg.xdat.security.services.RoleRepositoryServiceI.DEFAULT_ROLE_REPO_SERVICE;
import static org.nrg.xdat.security.services.RoleServiceI.DEFAULT_ROLE_SERVICE;

@Configuration
public class FeaturesConfig {

    @Bean
    public FeatureServiceI featureService() throws ClassNotFoundException, IllegalAccessException, InstantiationException, SiteConfigurationException {
        final String serviceImpl = StringUtils.defaultIfBlank(_preferences.getFeatureService(), DEFAULT_FEATURE_SERVICE);
        if (_log.isDebugEnabled()) {
            _log.debug("Creating feature service with implementing class " + serviceImpl);
        }
        return Class.forName(serviceImpl).asSubclass(FeatureServiceI.class).newInstance();
    }

    @Bean
    public FeatureRepositoryServiceI featureRepositoryService() throws ClassNotFoundException, IllegalAccessException, InstantiationException, SiteConfigurationException {
        final String serviceImpl = StringUtils.defaultIfBlank(_preferences.getFeatureRepositoryService(), DEFAULT_FEATURE_REPO_SERVICE);
        if (_log.isDebugEnabled()) {
            _log.debug("Creating feature repository service with implementing class " + serviceImpl);
        }
        return Class.forName(serviceImpl).asSubclass(FeatureRepositoryServiceI.class).newInstance();
    }

    @Bean
    public RoleHolder roleService() throws ClassNotFoundException, IllegalAccessException, InstantiationException, SiteConfigurationException {
        final String serviceImpl = StringUtils.defaultIfBlank(_preferences.getRoleService(), DEFAULT_ROLE_SERVICE);
        if (_log.isDebugEnabled()) {
            _log.debug("Creating role service with implementing class " + serviceImpl);
        }
        return new RoleHolder(Class.forName(serviceImpl).asSubclass(RoleServiceI.class).newInstance());
    }

    @Bean
    public RoleRepositoryHolder roleRepositoryService() throws ClassNotFoundException, IllegalAccessException, InstantiationException, SiteConfigurationException {
        final String serviceImpl = StringUtils.defaultIfBlank(_preferences.getRoleRepositoryService(), DEFAULT_ROLE_REPO_SERVICE);
        if (_log.isDebugEnabled()) {
            _log.debug("Creating role repository service with implementing class " + serviceImpl);
        }
        return new RoleRepositoryHolder(Class.forName(serviceImpl).asSubclass(RoleRepositoryServiceI.class).newInstance());
    }

    private static final Logger _log = LoggerFactory.getLogger(FeaturesConfig.class);

    @Inject
    private InitializerSiteConfiguration _preferences;
}
