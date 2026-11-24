package com.sdv.platform.web.baseline;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/** Task 0060: walks every type in a request or an answer of Vero's controllers, down through fields and generics. */
final class HttpTypes {
    private HttpTypes() { }

    static List<Method> handlers() throws Exception {
        List<Method> out = new ArrayList<>();
        for (Class<?> c : VeroClasses.all()) out.addAll(handlersOf(c));
        return out;
    }

    static List<Method> handlersOf(Class<?> c) {
        List<Method> out = new ArrayList<>();
        if (!AnnotatedElementUtils.hasAnnotation(c, ControllerAdvice.class) && !AnnotatedElementUtils.hasAnnotation(c, Controller.class)) return out;
        for (Method m : c.getDeclaredMethods()) {
            if (AnnotatedElementUtils.hasAnnotation(m, RequestMapping.class) || AnnotatedElementUtils.hasAnnotation(m, ExceptionHandler.class)) out.add(m);
        }
        return out;
    }

    /** Vero's types in the bodies it reads. */
    static Set<Class<?>> requestTypes() throws Exception {
        Set<Class<?>> out = new LinkedHashSet<>();
        for (Method m : handlers()) {
            for (Parameter p : m.getParameters()) {
                if (!p.isAnnotationPresent(RequestBody.class)) continue;
                walk(p.getParameterizedType(), "", (where, c) -> {
                    if (c.getName().startsWith("com.sdv.")) out.add(c);
                }, new LinkedHashSet<>());
            }
        }
        return out;
    }

    /** Every place where a type that {@code wanted} accepts appears: "Type.field: class", sorted. */
    static Set<String> places(List<Method> methods, Predicate<Class<?>> wanted) {
        Set<String> places = new TreeSet<>();
        BiConsumer<String, Class<?>> look = (where, c) -> {
            if (wanted.test(c)) places.add(where + ": " + c.getName());
        };
        for (Method m : methods) {
            String at = m.getDeclaringClass().getSimpleName() + "." + m.getName() + "()";
            walk(m.getGenericReturnType(), at, look, new LinkedHashSet<>());
            for (Type p : m.getGenericParameterTypes()) walk(p, at, look, new LinkedHashSet<>());
        }
        return places;
    }

    private static void walk(Type type, String where, BiConsumer<String, Class<?>> look, Set<Type> seen) {
        if (type instanceof ParameterizedType p) {
            walk(p.getRawType(), where, look, seen);
            for (Type a : p.getActualTypeArguments()) walk(a, where, look, seen);
        } else if (type instanceof GenericArrayType a) {
            walk(a.getGenericComponentType(), where, look, seen);
        } else if (type instanceof WildcardType w) {
            for (Type b : w.getUpperBounds()) walk(b, where, look, seen);
            for (Type b : w.getLowerBounds()) walk(b, where, look, seen);
        } else if (type instanceof Class<?> c) {
            if (c.isArray()) {
                walk(c.getComponentType(), where, look, seen);
                return;
            }
            look.accept(where, c);
            if (!c.getName().startsWith("com.sdv.") || !seen.add(c)) return;
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) walk(f.getGenericType(), c.getSimpleName() + "." + f.getName(), look, seen);
            }
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && Modifier.isPublic(m.getModifiers()) && !Modifier.isStatic(m.getModifiers())
                        && m.getName().matches("(get|is)[A-Z].*")) {
                    walk(m.getGenericReturnType(), c.getSimpleName() + "." + m.getName() + "()", look, seen);
                }
            }
            if (c.getSuperclass() != null) walk(c.getGenericSuperclass(), where, look, seen);
            if (c.isSealed()) for (Class<?> sub : c.getPermittedSubclasses()) walk(sub, where, look, seen);
        }
    }
}
