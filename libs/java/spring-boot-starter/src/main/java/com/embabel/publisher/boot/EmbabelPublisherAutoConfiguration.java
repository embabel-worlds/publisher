package com.embabel.publisher.boot;

import com.embabel.publisher.json.JsonCodec;
import com.embabel.publisher.realm.OffSwitch;
import com.embabel.publisher.realm.RealmEndpoint;
import com.embabel.publisher.realm.RealmSettings;
import com.embabel.publisher.servlet.RealmServletFilter;
import com.embabel.publisher.spring.SpringRealmPublisher;
import com.embabel.publisher.spring.jpa.JpaOrdering;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Serves the realm at {@code embabel.realm.path} from a servlet filter.
 * <p>
 * When publication is off (§2.2) only a responder is registered: no method is scanned, no realm is
 * built, and every path below the publisher answers {@code PUBLISHER_DISABLED}.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "jakarta.servlet.Filter")
public class EmbabelPublisherAutoConfiguration {

    /*
     * The one behavioural difference between the Boot lines. The shared core is compiled against
     * Spring Data 3.2 and its Specification and Sort run unchanged on Spring Data 4; ordering is not
     * the same. Spring Data JPA 4 places nulls in Criteria queries, so the method receives a real Sort.
     */
    static final JpaOrdering ORDERING = JpaOrdering.SORT;

    @Bean
    @ConfigurationProperties("embabel.realm")
    @ConditionalOnMissingBean
    public RealmSettings embabelRealmSettings(Environment environment, ObjectProvider<BuildProperties> build) {
        /* Defaults the build already knows; anything configured under embabel.realm overrides them. */
        RealmSettings settings = new RealmSettings();
        settings.setName(environment.getProperty("spring.application.name"));
        BuildProperties properties = build.getIfAvailable();
        if (properties != null) {
            settings.setVersion(properties.getVersion());
        }
        return settings;
    }

    @Bean
    @ConditionalOnMissingBean
    public JsonCodec embabelRealmJsonCodec() {
        return new Jackson3JsonCodec();
    }

    @Bean
    @Conditional(Enabled.class)
    public SpringRealmPublisher embabelRealmPublisher(RealmSettings settings, JsonCodec codec) {
        return new SpringRealmPublisher(settings, codec, ORDERING);
    }

    @Bean
    public FilterRegistrationBean<RealmServletFilter> embabelRealmFilter(RealmSettings settings, JsonCodec codec,
            ObjectProvider<SpringRealmPublisher> publisher, Environment environment) {
        RealmServletFilter filter;
        if (Enabled.enabled(environment)) {
            SpringRealmPublisher realm = publisher.getObject();
            filter = new RealmServletFilter(settings.getPath(), realm::get);
        }
        else {
            RealmEndpoint disabled = RealmEndpoint.disabled(codec);
            filter = new RealmServletFilter(settings.getPath(), () -> disabled);
        }
        FilterRegistrationBean<RealmServletFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setName("embabelRealmFilter");
        registration.addUrlPatterns(settings.getPath(), settings.getPath() + "/*");
        /* Before anything else that could answer, Spring Security's chain included. */
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 50);
        return registration;
    }

    /**
     * {@code embabel.realm.enabled}, overruled by the {@code EMBABEL_REALM_ENABLED} environment
     * variable whenever the variable says off, wherever else the property was set.
     */
    static class Enabled implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return enabled(context.getEnvironment());
        }

        static boolean enabled(Environment environment) {
            String variable = null;
            if (environment instanceof ConfigurableEnvironment configurable) {
                PropertySource<?> system = configurable.getPropertySources().get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                Object value = system == null ? null : system.getProperty(OffSwitch.VARIABLE);
                variable = value == null ? null : value.toString();
            }
            return OffSwitch.enabled(variable, environment.getProperty("embabel.realm.enabled", Boolean.class, true));
        }
    }
}
