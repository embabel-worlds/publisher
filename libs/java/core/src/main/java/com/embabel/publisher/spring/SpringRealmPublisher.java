package com.embabel.publisher.spring;

import com.embabel.publisher.annotation.RealmLookup;
import com.embabel.publisher.annotation.RealmQuery;
import com.embabel.publisher.annotation.RealmVerb;
import com.embabel.publisher.json.JsonCodec;
import com.embabel.publisher.realm.LookupBinding;
import com.embabel.publisher.realm.QueryBinding;
import com.embabel.publisher.realm.Realm;
import com.embabel.publisher.realm.RealmEndpoint;
import com.embabel.publisher.realm.RealmPublisher;
import com.embabel.publisher.realm.RealmSettings;
import com.embabel.publisher.realm.VerbBinding;
import com.embabel.publisher.spring.jpa.JpaOrdering;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Finds the realm's methods on the application's beans, the way Spring finds {@code @Scheduled}
 * and {@code @EventListener} methods, and builds the publisher once every singleton exists. Any
 * declaration that cannot be served as declared fails the application's startup.
 */
public class SpringRealmPublisher implements SmartInitializingSingleton, BeanFactoryAware, Supplier<RealmEndpoint> {

    private static final List<Class<? extends java.lang.annotation.Annotation>> ANNOTATIONS =
            List.of(RealmLookup.class, RealmQuery.class, RealmVerb.class);

    private final RealmSettings settings;
    private final JsonCodec codec;
    private final JpaOrdering ordering;
    private ConfigurableListableBeanFactory beanFactory;
    private volatile RealmPublisher publisher;

    /**
     * @param ordering how a requested order reaches a JPA query on the application's Spring Data line
     */
    public SpringRealmPublisher(RealmSettings settings, JsonCodec codec, JpaOrdering ordering) {
        this.settings = settings;
        this.codec = codec;
        this.ordering = ordering;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = (ConfigurableListableBeanFactory) beanFactory;
    }

    @Override
    public void afterSingletonsInstantiated() {
        RealmMethodBinder binder = new RealmMethodBinder(beanFactory, ordering);
        List<LookupBinding> lookups = new ArrayList<>();
        List<QueryBinding> queries = new ArrayList<>();
        List<VerbBinding> verbs = new ArrayList<>();
        for (String name : beanFactory.getBeanDefinitionNames()) {
            BeanDefinition definition = beanFactory.getBeanDefinition(name);
            if (!definition.isSingleton() || definition.isAbstract() || definition.getRole() == BeanDefinition.ROLE_INFRASTRUCTURE) {
                continue;
            }
            Class<?> type = beanFactory.getType(name, false);
            if (type == null) {
                continue;
            }
            Class<?> userClass = ClassUtils.getUserClass(type);
            if (!AnnotationUtils.isCandidateClass(userClass, ANNOTATIONS)) {
                continue;
            }
            select(userClass, RealmLookup.class).forEach((m, a) -> lookups.add(binder.lookup(name, userClass, m, a)));
            select(userClass, RealmQuery.class).forEach((m, a) -> queries.add(binder.query(name, userClass, m, a)));
            select(userClass, RealmVerb.class).forEach((m, a) -> verbs.add(binder.verb(name, userClass, m, a)));
        }
        this.publisher = new RealmPublisher(Realm.build(settings, lookups, queries, verbs), codec);
    }

    private static <A extends java.lang.annotation.Annotation> Map<Method, A> select(Class<?> type, Class<A> annotation) {
        return MethodIntrospector.selectMethods(type,
                (MethodIntrospector.MetadataLookup<A>) method -> AnnotatedElementUtils.findMergedAnnotation(method, annotation));
    }

    /** The realm as served. */
    public Realm realm() {
        return get().realm();
    }

    @Override
    public RealmPublisher get() {
        RealmPublisher current = publisher;
        if (current == null) {
            throw new IllegalStateException("The realm is built once every singleton exists; it was asked for before that");
        }
        return current;
    }
}
