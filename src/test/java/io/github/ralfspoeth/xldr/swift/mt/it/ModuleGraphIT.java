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
 * is being tested. These two disagree - the surefire set green, this one red,
 * one build, one dependency list, identical code - and that disagreement is the
 * finding: {@code SwiftFeedIT} sat in a twenty-second timeout while the same
 * assertions passed next door under surefire.
 * <p>
 * The mechanism is what {@link #theXldrJarsAreOnTheModulePath} asks, and it is
 * asked first because it decides what a failing service lookup means. xldr
 * declares every service in {@code module-info} and ships no
 * {@code META-INF/services} fallback, so its providers exist only while its jars
 * are on the module path. On the classpath the types land in the unnamed module
 * and every lookup comes back empty - which is what {@code readSpec} reports as
 * "unsupported mapping spec format", and what stops a feed coming up for a
 * reason that has nothing to do with the feed.
 * <p>
 * A previous reading blamed the thread context class loader. That was wrong:
 * naming the defining loader, in xldr 0.24 and in these tests, changed nothing
 * here. Worth keeping as a note, since the two explanations look alike from the
 * outside and only one of them is testable in a line.
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
                        + " runner rather than the code, and the lookup has gone back to"
                        + " resolving against the thread context class loader.");
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
