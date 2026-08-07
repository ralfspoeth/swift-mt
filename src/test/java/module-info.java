import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;

open module io.github.ralfspoeth.xldr.swift.mt.test {
    requires org.junit.jupiter.api;
    requires io.github.ralfspoeth.xldr.swift.mt;
    uses InputAdapterFactory;
}
