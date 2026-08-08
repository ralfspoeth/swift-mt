import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.swift.mt.MtInputAdapterFactory;
import org.jspecify.annotations.NullMarked;

@NullMarked
module io.github.ralfspoeth.xldr.swift.mt {
    exports io.github.ralfspoeth.xldr.swift.mt;
    requires transitive io.github.ralfspoeth.xldr.ia;
    requires static org.jspecify;
    provides InputAdapterFactory with MtInputAdapterFactory;
}
