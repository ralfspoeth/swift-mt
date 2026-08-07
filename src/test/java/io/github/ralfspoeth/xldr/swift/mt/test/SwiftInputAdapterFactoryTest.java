package io.github.ralfspoeth.xldr.swift.mt.test;

import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.DataType;
import io.github.ralfspoeth.xldr.spec.FieldSelectorSpec;
import io.github.ralfspoeth.xldr.spec.InputSpec;
import io.github.ralfspoeth.xldr.spec.RecordSelectorSpec;
import io.github.ralfspoeth.xldr.swift.mt.SwiftInputAdapterFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwiftInputAdapterFactoryTest {

    private final SwiftInputAdapterFactory factory = new SwiftInputAdapterFactory();

    @Test
    void readsTheSwiftMimeTypes() {
        assertAll(
                () -> assertTrue(factory.reads("text/x-swift")),
                () -> assertTrue(factory.reads("application/x-swift")),
                () -> assertTrue(factory.reads("text/plain")),
                () -> assertTrue(factory.reads("application/octet-stream")),
                () -> assertFalse(factory.reads("text/csv")),
                () -> assertFalse(factory.reads("application/xml")),
                () -> assertFalse(factory.reads("application/json"))
        );
    }

    /**
     * The default {@code reads(InputSpec)} has to agree with
     * {@code reads(String)} - the server asks the first, the tests the second.
     */
    @Test
    void readsAspecByItsMimeType() {
        assertAll(
                () -> assertTrue(factory.reads(spec("text/x-swift"))),
                () -> assertFalse(factory.reads(spec("text/csv")))
        );
    }

    /**
     * The adapter is found through {@code ServiceLoader}, which is the whole of
     * the installation: no configuration names it, so a broken
     * {@code provides} clause would only show up at run time.
     */
    @Test
    void isDiscoveredAsAservice() {
        var found = ServiceLoader.load(InputAdapterFactory.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .filter(f -> f instanceof SwiftInputAdapterFactory)
                .count();

        assertEquals(1L, found, "the module should provide exactly one SWIFT factory");
    }

    /**
     * Every selector is compiled when the adapter is built rather than when a
     * file arrives, so a spec with a bad selector fails at reconcile time - the
     * feed does not come up, instead of hospitalising its first input.
     */
    @Test
    void rejectsAmalformedFieldSelectorWhenTheAdapterIsBuilt() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> adapterWith("no-separator-at-all"), "no separator"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> adapterWith("4~.*"), "no group delimiter"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> adapterWith("4~~.*"), "empty tag number"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> adapterWith("abcd~.*~0"), "block identifiers are at most three characters"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> adapterWith("1-2~.*~0"), "block identifiers are alphanumeric")
        );
    }

    /**
     * A block identifier is checked for shape, not against a list of the five
     * numbered blocks. Which blocks exist is a property of the message in hand -
     * SWIFT appends a system block {@code {S:...}} on the way out of the network
     * - so a selector naming one the message does not carry yields null at parse
     * time rather than being refused here.
     */
    @Test
    void acceptsAnyWellFormedBlockIdentifier() {
        assertAll(
                () -> assertNotNull(adapterWith("S~.*~0"), "the system block"),
                () -> assertNotNull(adapterWith("9~.*~0"), "no such block, but a legal identifier")
        );
    }

    @Test
    void acceptsTheSelectorFormsTheReadmeDocuments() {
        assertAll(
                () -> assertNotNull(adapterWith("1~.*~0"), "an explicit block"),
                () -> assertNotNull(adapterWith("~.*~0"), "block 4 by default"),
                () -> assertNotNull(adapterWith("~1~.*~0"), "an explicit tag"),
                () -> assertNotNull(adapterWith("~([0-9]{6}).*~1"), "a capture group"),
                () -> assertNotNull(adapterWith("~.*~"), "the group number omitted"),
                () -> assertNotNull(adapterWith("5~.*~0"), "the trailer")
        );
    }

    /**
     * A record selector's tags are checked for shape too. A selector of
     * {@code 61} matches no tag in any message, so without this it would load
     * nothing and say nothing.
     */
    @Test
    void rejectsAmalformedTagInArecordSelector() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> adapterFor("61"), "no colons"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> adapterFor(":610:"), "three digits"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> adapterFor(":61:~86"), "the second tag of a sequence"),
                () -> assertNotNull(adapterFor(":62a:"), "the option-letter wildcard"),
                () -> assertNotNull(adapterFor(":25a:"), "matches :25: and :25P: alike")
        );
    }

    private InputAdapter adapterFor(String recordSelector) {
        return factory.createInputAdapter(new InputSpec(
                "text/x-swift", null, null,
                List.of(new RecordSelectorSpec("r", recordSelector,
                        List.of(new FieldSelectorSpec("f", "~.*~0", DataType.STRING)))),
                List.of(), Map.of()));
    }

    private InputAdapter adapterWith(String fieldSelector) {
        return factory.createInputAdapter(new InputSpec(
                "text/x-swift", null, null,
                List.of(new RecordSelectorSpec("r", ":20:",
                        List.of(new FieldSelectorSpec("f", fieldSelector, DataType.STRING)))),
                List.of(), Map.of()));
    }

    private static InputSpec spec(String mimeType) {
        return new InputSpec(mimeType, null, null, List.of(), List.of(), Map.of());
    }
}
