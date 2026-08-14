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
 * is being tested. These two once disagreed - the surefire pair green, the
 * failsafe pair red, one build, one module path, identical code - and that
 * disagreement was the finding. It cost four attempts to see, because a passing
 * unit test was twice taken as evidence that the service lookup was sound.
 * <p>
 * What it was hiding: xldr looked its own services up with the one-argument
 * {@link ServiceLoader#load(Class)}, which resolves against the <em>thread
 * context</em> class loader rather than the one that defined the service. What
 * that loader is during a forked test run is the runner's business, and the two
 * runners answered differently. {@code MappingSpecReader.of} then found nothing,
 * {@code readSpec} refused every spec with "unsupported mapping spec format",
 * and {@code SwiftFeedIT} sat in a twenty-second timeout while these same
 * assertions passed next door.
 * <p>
 * xldr 0.24 names the defining loader, here and in the server. This class stays
 * because the failure was invisible from anywhere else, and because the next
 * thing to break the module graph will not announce itself either.
 */
class ModuleGraphIT {

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
