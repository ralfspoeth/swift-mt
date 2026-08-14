import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;

open module io.github.ralfspoeth.xldr.swift.mt.test {
    requires org.junit.jupiter.api;
    requires io.github.ralfspoeth.xldr.swift.mt;
    requires io.github.ralfspoeth.xldr.server;
    // the integration test turns the server's own logging up to find out why a
    // feed did not come up; see SwiftFeedIT#showWhatTheServerIsThinking
    requires java.logging;
    // the integration test reads the server's own MXBean when it times out
    requires java.management;

    uses InputAdapterFactory;
}
