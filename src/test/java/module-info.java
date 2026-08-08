import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;

open module io.github.ralfspoeth.xldr.swift.mt.test {
    requires org.junit.jupiter.api;
    requires io.github.ralfspoeth.xldr.swift.mt;
    // the integration test drives xldr's server directly, with no pool and no
    // command line - which is the point of it
    requires io.github.ralfspoeth.xldr.server;
    requires java.sql;

    uses InputAdapterFactory;
}
