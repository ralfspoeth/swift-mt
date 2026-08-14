package io.github.ralfspoeth.xldr.swift.mt.test;

import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.io.MappingSpecReader;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether this module graph can find the services the server depends on.
 * <p>
 * Both would pass trivially if xldr shipped {@code META-INF/services} files. It
 * does not - every service is declared in {@code module-info} only - so these
 * pass exactly when the xldr modules are on the module path, and fail when they
 * are on the classpath, where a {@code provides} clause counts for nothing.
 * <p>
 * They exist because that difference is invisible from the integration test.
 * Every way of failing to bring a feed up looks the same from there, a
 * stopwatch running out, and a missing service provider is one of them. These
 * ask the question directly, in milliseconds - so the answer arrives as a test
 * name rather than as a line in a log somebody has to find.
 * <p>
 * The companion {@code ModuleGraphIT} asks the same two things under failsafe,
 * which is not redundant: what a runner forks is part of what is under test,
 * and these two once disagreed. See there.
 */
class ModuleGraphTest {

    /**
     * The server reads a feed's spec through {@code MappingSpecReader.of}, which
     * is a {@code ServiceLoader} lookup. With no provider it throws
     * "unsupported mapping spec format", {@code reconcile} catches it, the feed
     * is never registered and {@code in/} is never created - which is precisely
     * the integration test's symptom.
     */
    @Test
    void theSpecReadersAreOnTheModulePath() {
        assertTrue(MappingSpecReader.of(Path.of("spec.json")).isPresent(),
                "ServiceLoader found no MappingSpecReader for spec.json. xldr declares its readers"
                        + " in module-info only, with no META-INF/services fallback, so this fails"
                        + " when the xldr modules are on the classpath instead of the module path.");
    }

    /**
     * And the adapter this module provides has to be findable the same way: the
     * server never names it, it discovers it.
     */
    @Test
    void thisAdapterIsFoundByServiceLoader() {
        var found = ServiceLoader.load(InputAdapterFactory.class, InputAdapterFactory.class.getClassLoader())
                .stream()
                .map(ServiceLoader.Provider::get)
                .anyMatch(f -> f.reads("text/x-swift"));
        assertTrue(found, "ServiceLoader found no InputAdapterFactory reading text/x-swift,"
                + " though this module provides one - the same module-path question as above,"
                + " asked of this module rather than of xldr's.");
    }
}
