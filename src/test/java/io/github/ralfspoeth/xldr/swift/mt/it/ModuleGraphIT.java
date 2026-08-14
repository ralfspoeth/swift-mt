package io.github.ralfspoeth.xldr.swift.mt.it;

import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.io.MappingSpecReader;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ModuleGraphTest}, asked again under failsafe.
 * <p>
 * Duplicated rather than shared on purpose, because the runner is part of what
 * is being tested, and these two once disagreed: the surefire set green, this
 * one red, one build, one dependency list, identical code. That disagreement was
 * the finding, and it took four rounds to see because it looks from the outside
 * like a fault in the code.
 * <p>
 * It was not. This module pinned maven-failsafe-plugin to 3.5.2 while the parent
 * pins 3.5.5 for both runners. 3.5.2 forks with {@code
 * JarManifestForkConfiguration} and builds no module path at all, where 3.5.5
 * uses {@code ModularClasspathForkConfiguration} and reads the test module
 * descriptor. So every xldr jar arrived in the unnamed module - which is what
 * {@link #theXldrJarsAreOnTheModulePath} prints - and since xldr declares its
 * services in {@code module-info} with no {@code META-INF/services} fallback,
 * every lookup came back empty. {@code readSpec} then refused every spec with
 * "unsupported mapping spec format" and {@code SwiftFeedIT} sat in a
 * twenty-second timeout, for a reason having nothing to do with the feed.
 * <p>
 * Two earlier readings blamed the field type names and then the thread context
 * class loader. Both were wrong, and naming the defining loader - in xldr 0.24
 * and in these tests - changed nothing here. Kept as a note because a service
 * lookup that finds nothing looks the same whatever the cause, and only the
 * module question distinguishes them in a line.
 */
class ModuleGraphIT {

    /**
     * Whether the xldr modules are modules here at all.
     * <p>
     * This is asked first because it decides what the other two mean. A
     * {@code provides} clause lives in {@code module-info} and is read only when
     * the jar is on the module path; on the classpath the type is in the unnamed
     * module and the clause may as well not exist, since xldr ships no
     * {@code META-INF/services} fallback. So a service lookup that finds nothing
     * is not evidence about loaders or about xldr - it is evidence about how this
     * runner assembled the path.
     */
    @Test
    void theXldrJarsAreOnTheModulePath() {
        var spec = MappingSpecReader.class.getModule();
        var ia = InputAdapterFactory.class.getModule();
        assertTrue(spec.isNamed() && ia.isNamed(),
                "xldr is on the classpath rather than the module path here: "
                        + MappingSpecReader.class.getName() + " is in module '" + spec.getName()
                        + "', " + InputAdapterFactory.class.getName() + " in '" + ia.getName()
                        + "'. An unnamed module has no provides clauses, so every ServiceLoader"
                        + " lookup below it will come back empty however it is written.");
    }

    @Test
    void theSpecReadersAreOnTheModulePathUnderFailsafeToo() {
        assertTrue(MappingSpecReader.of(Path.of("spec.json")).isPresent(),
                "ServiceLoader found no MappingSpecReader for spec.json under failsafe."
                        + " If ModuleGraphTest passes and this does not, the difference is the"
                        + " runner rather than the code - check that failsafe and surefire are"
                        + " on the same version, and that the fork built a module path at all.");
    }

    @Test
    void thisAdapterIsFoundByServiceLoaderUnderFailsafeToo() {
        var found = ServiceLoader.load(InputAdapterFactory.class, InputAdapterFactory.class.getClassLoader())
                .stream()
                .map(ServiceLoader.Provider::get)
                .anyMatch(f -> f.reads("text/x-swift"));
        assertTrue(found, "ServiceLoader found no InputAdapterFactory reading text/x-swift"
                + " under failsafe, though this module provides one.");
    }
}
