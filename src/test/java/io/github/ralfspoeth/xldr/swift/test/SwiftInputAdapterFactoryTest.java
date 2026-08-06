package io.github.ralfspoeth.xldr.swift.test;

import io.github.ralfspoeth.xldr.spec.DataType;
import io.github.ralfspoeth.xldr.spec.FieldSelectorSpec;
import io.github.ralfspoeth.xldr.spec.InputSpec;
import io.github.ralfspoeth.xldr.spec.RecordSelectorSpec;
import io.github.ralfspoeth.xldr.swift.SwiftInputAdapterFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SwiftInputAdapterFactoryTest {

    @Test
    void createBasicInputAdapter() {
        // given
        var allSelector = new FieldSelectorSpec("text", "$0", DataType.STRING);
        var spec = new InputSpec(
                "text/plain",
                null, null, // we don't need the file patterns
                List.of(
                        new RecordSelectorSpec("h1", "1//.*", List.of(allSelector)),
                        new RecordSelectorSpec("h2", "2//.*", List.of(allSelector)),
                        new RecordSelectorSpec("h3", "3//.*", List.of(allSelector)),
                        new RecordSelectorSpec("msg", "4//.*", List.of(allSelector)),
                        new RecordSelectorSpec("lines", "4//{:61:,:86:}", List.of()),
                        new RecordSelectorSpec("trailer", "5//.*", List.of(allSelector))
                ),
                List.of(), Map.of() // no vars, no properties
        );
        // when
        var ia = new SwiftInputAdapterFactory().createInputAdapter(spec);
        // then
        assertAll(
                () -> assertNotNull(ia)
        );
    }
}