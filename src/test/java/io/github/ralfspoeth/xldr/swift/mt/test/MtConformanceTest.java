package io.github.ralfspoeth.xldr.swift.mt.test;

import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.DataType;
import io.github.ralfspoeth.xldr.spec.FieldSelectorSpec;
import io.github.ralfspoeth.xldr.spec.InputSpec;
import io.github.ralfspoeth.xldr.spec.Locator;
import io.github.ralfspoeth.xldr.spec.RecordSelectorSpec;
import io.github.ralfspoeth.xldr.swift.mt.MtInputAdapterFactory;
import io.github.ralfspoeth.xldr.tck.InputAdapterContract;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.US_ASCII;

/**
 * This adapter against the obligations of the SPI it implements.
 * <p>
 * The kit exists because of this adapter. It is the one written outside xldr's
 * own repository, against the published interface and nothing else, and it kept
 * nine of the ten obligations and quietly dropped the tenth - returning text
 * whatever the spec declared - on an argument the interface did nothing to
 * contradict. Six of the ten can be checked without knowing the format, so from
 * here on they are checked rather than argued about.
 * <p>
 * The spec below declares a {@code DATE} and a {@code DECIMAL} on purpose. A spec
 * that is all {@code TEXT} passes the typing obligations without having been
 * asked anything, which for this adapter would test precisely nothing.
 */
class MtConformanceTest extends InputAdapterContract {

    /**
     * Constructed rather than discovered, which is the easier case the kit
     * describes and nothing else exercises: this module exports the package its
     * factory sits in, so a test of its own can say {@code new}. None of the five
     * adapters shipped with xldr exports anything, and their conformance tests
     * have to go through {@code InputAdapterFactory.of}.
     */
    @Override
    protected @NonNull InputAdapterFactory factory() {
        return new MtInputAdapterFactory();
    }

    @Override
    protected @NonNull String mimeType() {
        return "text/x-swift";
    }

    /**
     * The statement lines of an MT940, typed. A value date is {@code YYMMDD} and
     * an amount marks its decimal with a comma, neither of which is what
     * {@code LocalDateTime} or {@code BigDecimal} read by default - so the spec
     * says {@code dateFormat} and a {@code locale}, exactly as it would for a
     * European CSV.
     */
    @Override
    protected @NonNull InputSpec spec() {
        return new InputSpec("text/x-swift",
                List.of(new RecordSelectorSpec("bookings", new Locator.At(":61:"), List.of(
                        new FieldSelectorSpec("valueDate", "~([0-9]{6}).*~1", DataType.DATE),
                        new FieldSelectorSpec("amount", "~[0-9]{10}[CD]([0-9,]+)N.*~1", DataType.DECIMAL),
                        new FieldSelectorSpec("side", "~[0-9]{10}([CD]).*~1", DataType.TEXT)))),
                List.of(),
                Map.of("dateFormat", "yyMMdd", "numberFormat", "#0.00", "locale", "de-DE"));
    }

    /** FIN is an ASCII wire format, so the sample is what actually arrives. */
    @Override
    protected byte @NonNull [] sample() {
        return Messages.MT940.getBytes(US_ASCII);
    }
}
