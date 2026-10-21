import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;

open module io.github.ralfspoeth.xldr.swift.mt.test {
    requires org.junit.jupiter.api;
    requires io.github.ralfspoeth.xldr.swift.mt;
    requires io.github.ralfspoeth.xldr.server;
    // the SPI's obligations as tests; MtConformanceTest is this adapter run
    // against them, which is what the kit was written for
    requires io.github.ralfspoeth.xldr.tck;
    // the integration test sets the level of the server's own logging, so that
    // a green run is quiet; see SwiftFeedIT#quietenTheServer
    requires java.logging;
    // the integration test reads the server's own MXBean when it times out
    requires java.management;
    uses InputAdapterFactory;
}
