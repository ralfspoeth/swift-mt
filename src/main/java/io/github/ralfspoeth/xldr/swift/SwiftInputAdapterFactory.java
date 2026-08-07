package io.github.ralfspoeth.xldr.swift;

import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.InputSpec;

import java.util.Set;

public class SwiftInputAdapterFactory implements InputAdapterFactory {

    private static final Set<String> SWIFT_TYPES = Set.of(
            "text/plain",
            "text/x-swift",
            "application/octet-stream",
            "application/x-swift"
    );

    @Override
    public boolean reads(String mimeType) {
        return SWIFT_TYPES.contains(mimeType);
    }

    @Override
    public InputAdapter createInputAdapter(InputSpec spec) {
        return new  SwiftInputAdapter(spec);
    }
}
