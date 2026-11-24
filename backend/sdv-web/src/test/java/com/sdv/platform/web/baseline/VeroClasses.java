package com.sdv.platform.web.baseline;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.List;
import java.util.TreeMap;

/** Task 0060: every class Vero ships (its own packages, not its tests). */
final class VeroClasses {
    private VeroClasses() { }

    static List<Class<?>> all() throws Exception {
        var found = new TreeMap<String, Class<?>>();
        for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath*:com/sdv/platform/**/*.class")) {
            String url = r.getURL().toString();
            if (url.contains("/test-classes/")) continue;
            String name = url.substring(url.lastIndexOf("com/sdv/platform/")).replace('/', '.').replaceAll("\\.class$", "");
            if (name.endsWith("package-info")) continue;
            found.put(name, Class.forName(name, false, VeroClasses.class.getClassLoader()));
        }
        if (found.size() < 300) throw new IllegalStateException("only " + found.size() + " Vero classes found");
        return List.copyOf(found.values());
    }
}
