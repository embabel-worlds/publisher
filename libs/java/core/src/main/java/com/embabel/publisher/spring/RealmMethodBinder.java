package com.embabel.publisher.spring;

import com.embabel.publisher.annotation.RealmLookup;
import com.embabel.publisher.annotation.RealmQuery;
import com.embabel.publisher.annotation.RealmVerb;
import com.embabel.publisher.realm.LookupBinding;
import com.embabel.publisher.realm.PropertyModel;
import com.embabel.publisher.realm.QueryBinding;
import com.embabel.publisher.realm.RealmDefinitionException;
import com.embabel.publisher.realm.TypeIntrospector;
import com.embabel.publisher.realm.TypeModel;
import com.embabel.publisher.realm.VerbBinding;
import com.embabel.publisher.realm.VerbSchemas;
import com.embabel.publisher.spring.jpa.JpaOrdering;
import com.embabel.publisher.spring.jpa.SpecificationTranslator;
import com.embabel.publisher.realm.SortKey;
import com.embabel.publisher.where.Where;
import com.embabel.publisher.value.Primitives;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.core.ResolvableType;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns annotated service methods into bindings: reads what each parameter type can carry, checks
 * it against what the view declares, and invokes the method through the bean's proxy, so every
 * transaction, security rule and audit hook applies as it does for a call from the UI.
 */
final class RealmMethodBinder {

    static final String SPECIFICATION = "org.springframework.data.jpa.domain.Specification";
    static final String SORT = "org.springframework.data.domain.Sort";
    static final String PAGEABLE = "org.springframework.data.domain.Pageable";
    static final String LIMIT = "org.springframework.data.domain.Limit";
    static final String PAGE = "org.springframework.data.domain.Page";
    static final String SLICE = "org.springframework.data.domain.Slice";

    private enum Param { KEYS, SPECIFICATION, SORT, PAGEABLE, LIMIT }

    private final BeanFactory beans;
    private final JpaOrdering ordering;
    private final Map<Class<?>, TypeModel> models = new ConcurrentHashMap<>();

    RealmMethodBinder(BeanFactory beans, JpaOrdering ordering) {
        this.beans = beans;
        this.ordering = ordering;
    }

    /**
     * The requested order on the entity's attributes, ended by the identity when {@code tieBreak},
     * so offset pages are stable however many records share a sort value.
     */
    private static List<SpecificationTranslator.OrderKey> order(List<SortKey> keys, TypeModel type, boolean tieBreak) {
        List<SpecificationTranslator.OrderKey> order = new ArrayList<>();
        for (SortKey key : keys) {
            order.add(new SpecificationTranslator.OrderKey(type.property(key.property()).entityPath(), key.descending()));
        }
        String identity = type.identityProperty().entityPath();
        if (tieBreak && order.stream().noneMatch(k -> k.entityPath().equals(identity))) {
            order.add(new SpecificationTranslator.OrderKey(identity, false));
        }
        return order;
    }

    private Object specification(Where where, TypeModel type, List<SpecificationTranslator.OrderKey> order) {
        return SpecificationTranslator.translate(where, type, ordering == JpaOrdering.SPECIFICATION ? order : List.of());
    }

    private Object sort(List<SpecificationTranslator.OrderKey> order) {
        return ordering == JpaOrdering.SORT ? DataSupport.sort(order) : DataSupport.unsorted();
    }

    private void requireOrderable(boolean sorts, boolean pushes, TypeModel type, String source) {
        boolean declaresSort = type.properties().values().stream().anyMatch(p -> p.sortable());
        if (sorts && declaresSort && !pushes && ordering == JpaOrdering.SPECIFICATION) {
            throw new RealmDefinitionException(source + " sorts " + type.name() + ", but on this Spring Data line the order is applied "
                    + "through the Specification (Spring Data JPA 3 cannot place nulls in a Criteria query): add a Specification<"
                    + (type.entity() == null ? "E" : type.entity().getSimpleName()) + "> parameter");
        }
    }

    TypeModel model(Class<?> view) {
        return models.computeIfAbsent(view, TypeIntrospector::introspect);
    }

    LookupBinding lookup(String beanName, Class<?> beanClass, Method method, RealmLookup annotation) {
        String source = source(beanClass, method);
        requirePublic(method, source);
        TypeModel type = model(elementType(method, source, "a collection of a @RealmType view"));
        PropertyModel by = type.property(annotation.by());
        if (by == null) {
            throw new RealmDefinitionException(source + ": " + type.name() + " has no property " + annotation.by());
        }
        Param[] params = classify(method, source, type, List.of(Param.KEYS, Param.SPECIFICATION, Param.SORT));
        int keysAt = indexOf(params, Param.KEYS);
        if (keysAt < 0) {
            throw new RealmDefinitionException(source + ": a lookup takes its keys as a Collection<" + by.javaType().getSimpleName() + ">");
        }
        Class<?> keyType = ResolvableType.forMethodParameter(method, keysAt).asCollection().resolveGeneric(0);
        if (keyType == null || Primitives.box(keyType) != Primitives.box(by.javaType())) {
            throw new RealmDefinitionException(source + ": the keys of a lookup by " + by.name() + " are a Collection<"
                    + Primitives.box(by.javaType()).getSimpleName() + ">");
        }
        boolean pushes = indexOf(params, Param.SPECIFICATION) >= 0;
        boolean sorts = indexOf(params, Param.SORT) >= 0;
        checkPaths(type, pushes, sorts, source);
        requireOrderable(sorts, pushes, type, source);
        LookupBinding.Invoker invoker = (keys, where, sort) -> {
            List<SpecificationTranslator.OrderKey> order = order(sort, type, false);
            Object[] args = new Object[params.length];
            for (int i = 0; i < params.length; i++) {
                args[i] = switch (params[i]) {
                    case KEYS -> keys;
                    case SPECIFICATION -> specification(where, type, order);
                    case SORT -> sort(order);
                    default -> throw new IllegalStateException();
                };
            }
            return (Collection<?>) invoke(beanName, method, args);
        };
        return new LookupBinding(type, by.name(), annotation.maxKeys(), pushes, sorts, invoker, source);
    }

    QueryBinding query(String beanName, Class<?> beanClass, Method method, RealmQuery annotation) {
        String source = source(beanClass, method);
        requirePublic(method, source);
        Class<?> returned = method.getReturnType();
        Param[] params = classify(method, source, null, List.of(Param.SPECIFICATION, Param.SORT, Param.PAGEABLE, Param.LIMIT));
        boolean pageable = indexOf(params, Param.PAGEABLE) >= 0;
        boolean limited = indexOf(params, Param.LIMIT) >= 0;
        if (pageable && (limited || indexOf(params, Param.SORT) >= 0)) {
            throw new RealmDefinitionException(source + ": take a Pageable, or a Sort and a Limit, not both");
        }
        QueryBinding.Paging paging;
        if (returned.getName().equals(PAGE)) {
            paging = QueryBinding.Paging.PAGE;
        }
        else if (returned.getName().equals(SLICE)) {
            paging = QueryBinding.Paging.SLICE;
        }
        else if (Collection.class.isAssignableFrom(returned)) {
            paging = limited ? QueryBinding.Paging.LIMIT : QueryBinding.Paging.NONE;
        }
        else {
            throw new RealmDefinitionException(source + ": a query returns a Page, a Slice or a collection of a @RealmType view");
        }
        if ((paging == QueryBinding.Paging.PAGE || paging == QueryBinding.Paging.SLICE) != pageable) {
            throw new RealmDefinitionException(source + ": a query returns a Page or a Slice exactly when it takes a Pageable");
        }
        Class<?> element = ResolvableType.forMethodReturnType(method).resolveGeneric(0);
        if (!TypeIntrospector.isRealmType(element)) {
            throw new RealmDefinitionException(source + ": a query returns @RealmType views");
        }
        TypeModel type = model(element);
        classify(method, source, type, List.of(Param.SPECIFICATION, Param.SORT, Param.PAGEABLE, Param.LIMIT));
        boolean pushes = indexOf(params, Param.SPECIFICATION) >= 0;
        boolean sorts = pageable || indexOf(params, Param.SORT) >= 0;
        checkPaths(type, pushes, sorts, source);
        requireOrderable(sorts, pushes, type, source);
        boolean tieBreak = pageable && type.entity() != null && EntityPaths.resolves(type, type.identityProperty());
        if (pageable && ordering == JpaOrdering.SPECIFICATION && !pushes && tieBreak) {
            throw new RealmDefinitionException(source + " pages " + type.name() + ", but on this Spring Data line the stable order a "
                    + "walk needs is applied through the Specification: add a Specification<" + type.entity().getSimpleName() + "> parameter");
        }
        QueryBinding.Invoker invoker = (where, sort, page, pageSize) -> {
            List<SpecificationTranslator.OrderKey> order = order(sort, type, tieBreak);
            Object[] args = new Object[params.length];
            for (int i = 0; i < params.length; i++) {
                args[i] = switch (params[i]) {
                    case SPECIFICATION -> specification(where, type, order);
                    case SORT -> sort(order);
                    case PAGEABLE -> DataSupport.pageable(page, pageSize, (org.springframework.data.domain.Sort) sort(order));
                    case LIMIT -> DataSupport.limit(pageSize + 1);
                    default -> throw new IllegalStateException();
                };
            }
            return DataSupport.page(invoke(beanName, method, args), paging, pageSize);
        };
        return new QueryBinding(type, annotation.maxPageSize(), pushes, sorts, paging, invoker, source);
    }

    VerbBinding verb(String beanName, Class<?> beanClass, Method method, RealmVerb annotation) {
        String source = source(beanClass, method);
        requirePublic(method, source);
        if (method.getParameterCount() > 1) {
            throw new RealmDefinitionException(source + ": a verb takes at most one parameter, a record of its input");
        }
        Class<?> input = method.getParameterCount() == 1 ? method.getParameterTypes()[0] : null;
        if (input != null) {
            VerbSchemas.check(input, source);
        }
        Class<?> output = method.getReturnType() == void.class ? null : method.getReturnType();
        if (output != null) {
            VerbSchemas.check(output, source);
        }
        Class<?> subject = annotation.subject() == void.class ? null : annotation.subject();
        if (subject != null) {
            model(subject);
        }
        VerbBinding.Invoker invoker = in -> invoke(beanName, method, input == null ? new Object[0] : new Object[] {in});
        return new VerbBinding(annotation.name().isEmpty() ? method.getName() : annotation.name(), annotation.description(), subject,
                annotation.effect(), annotation.idempotent(), annotation.approval().required(), annotation.approval().reason(),
                input, output, invoker, source);
    }

    private Param[] classify(Method method, String source, TypeModel type, List<Param> allowed) {
        Class<?>[] types = method.getParameterTypes();
        Param[] params = new Param[types.length];
        for (int i = 0; i < types.length; i++) {
            String name = types[i].getName();
            Param param;
            if (name.equals(SPECIFICATION)) {
                param = Param.SPECIFICATION;
                if (type != null) {
                    Class<?> entity = ResolvableType.forMethodParameter(method, i).resolveGeneric(0);
                    if (type.entity() == null || entity != type.entity()) {
                        throw new RealmDefinitionException(source + ": the Specification is of " + (entity == null ? "?" : entity.getSimpleName())
                                + ", but " + type.name() + "'s entity is " + (type.entity() == null ? "not declared; set @RealmType(entity = ...)" : type.entity().getSimpleName()));
                    }
                }
            }
            else if (name.equals(SORT)) {
                param = Param.SORT;
            }
            else if (name.equals(PAGEABLE)) {
                param = Param.PAGEABLE;
            }
            else if (name.equals(LIMIT)) {
                param = Param.LIMIT;
            }
            else if (Collection.class.isAssignableFrom(types[i])) {
                param = Param.KEYS;
            }
            else {
                throw new RealmDefinitionException(source + ": cannot bind parameter " + (i + 1) + " (" + types[i].getSimpleName() + ")");
            }
            if (!allowed.contains(param) || indexOf(params, param) >= 0) {
                throw new RealmDefinitionException(source + ": cannot bind parameter " + (i + 1) + " (" + types[i].getSimpleName() + ") here");
            }
            params[i] = param;
        }
        return params;
    }

    /** Every property a translated filter or sort will name must resolve on the entity. */
    private static void checkPaths(TypeModel type, boolean pushes, boolean sorts, String source) {
        for (PropertyModel property : type.properties().values()) {
            if (pushes && property.filterable()) {
                EntityPaths.requireResolvable(type, property, "filterable and " + source + " takes a Specification");
            }
            if (sorts && property.sortable()) {
                EntityPaths.requireResolvable(type, property, "sortable and " + source + " sorts");
            }
        }
    }

    private static Class<?> elementType(Method method, String source, String expected) {
        Class<?> element = Collection.class.isAssignableFrom(method.getReturnType())
                ? ResolvableType.forMethodReturnType(method).asCollection().resolveGeneric(0) : null;
        if (!TypeIntrospector.isRealmType(element)) {
            throw new RealmDefinitionException(source + ": returns " + method.getGenericReturnType() + "; expected " + expected);
        }
        return element;
    }

    private static void requirePublic(Method method, String source) {
        if (!Modifier.isPublic(method.getModifiers())) {
            throw new RealmDefinitionException(source + " must be public, so it is called through the bean's proxy");
        }
    }

    private static int indexOf(Param[] params, Param param) {
        for (int i = 0; i < params.length; i++) {
            if (params[i] == param) {
                return i;
            }
        }
        return -1;
    }

    private static String source(Class<?> beanClass, Method method) {
        return beanClass.getSimpleName() + "." + method.getName();
    }

    /** Through the proxy: the bean is looked up per call, never the raw target. */
    private Object invoke(String beanName, Method method, Object[] args) {
        Object bean = beans.getBean(beanName);
        Method invocable = AopUtils.selectInvocableMethod(method, bean.getClass());
        try {
            return invocable.invoke(bean, args);
        }
        catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw new UndeclaredThrowableException(e.getCause());
        }
        catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
