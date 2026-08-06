import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.swift.SwiftInputAdapterFactory;
import org.jspecify.annotations.NullMarked;

@NullMarked
module io.github.ralfspoeth.xldr.swift {
    exports io.github.ralfspoeth.xldr.swift;
    requires transitive io.github.ralfspoeth.xldr.ia;
    requires static org.jspecify;
    provides InputAdapterFactory with SwiftInputAdapterFactory;
}