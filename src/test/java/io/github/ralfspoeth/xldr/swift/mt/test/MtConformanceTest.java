package io.github.ralfspoeth.xldr.swift.mt.test;

import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.DataType;
import io.github.ralfspoeth.xldr.spec.Discriminator;
import io.github.ralfspoeth.xldr.spec.FieldSelectorSpec;
import io.github.ralfspoeth.xldr.spec.InputSpec;
import io.github.ralfspoeth.xldr.spec.Locator;
import io.github.ralfspoeth.xldr.spec.RecordSelectorSpec;
import io.github.ralfspoeth.xldr.spec.Selector;
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
 * contradict. Since xldr 0.51 all ten are checked rather than argued about:
 * seven from the four things below, and three from evidence only this module can
 * produce, of which {@link #refusals()} is abstract so that the question cannot
 * be passed over in silence.
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
                List.of(new RecordSelectorSpec("bookings", Locator.at(":61:"), List.of(
                        new FieldSelectorSpec("valueDate", "~([0-9]{6}).*~1", DataType.TEMPORAL),
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

    /**
     * What this adapter will not be built from.
     *
     * <p>Every one of these describes a spec that would otherwise have loaded
     * nothing and said nothing - which is the failure this adapter has spent the
     * most effort not having, and the reason it checks the shape of a selector
     * instead of compiling whatever it is handed. A tag selector of {@code 61}
     * rather than {@code :61:} matches no tag in any message ever sent, and a
     * load reporting zero rows over a file full of statements looks exactly like
     * a quiet Tuesday.
     */
    @Override
    protected @NonNull List<Refusal> refusals() {
        var valueDate = new FieldSelectorSpec("valueDate", "~([0-9]{6}).*~1", DataType.TEMPORAL);
        return List.of(
                new Refusal("a discriminator, where an MT record is located rather than filtered",
                        spec(Locator.where(new Discriminator.Equals(Selector.text(":61:"), "C")), valueDate)),
                new Refusal("no locator at all, which would have to mean every record - and a"
                        + " message is not a run of candidates",
                        spec(Locator.every(), valueDate)),
                new Refusal("'61' where a tag is wanted, which would match nothing in any message",
                        spec(Locator.at("61"), valueDate)),
                new Refusal("a delimited sequence handed two opening tags",
                        spec(Locator.at("seq~:15B:~:16A:"), valueDate)),
                new Refusal("a field selector carrying no pattern",
                        spec(Locator.at(":61:"),
                                new FieldSelectorSpec("amount", "amount", DataType.DECIMAL))),
                new Refusal("a field selector that counts, where a record is tags and has no"
                        + " components to count",
                        spec(Locator.at(":61:"),
                                new FieldSelectorSpec("amount", 1, DataType.DECIMAL))),
                new Refusal("two record selectors of one name",
                        new InputSpec(mimeType(),
                                List.of(new RecordSelectorSpec("bookings", Locator.at(":61:"),
                                                List.of(valueDate)),
                                        new RecordSelectorSpec("bookings", Locator.at(":61:"),
                                                List.of(valueDate))),
                                List.of(), Map.of())));
    }

    /** one record selector, named as {@link #spec()} names it, with no properties */
    private InputSpec spec(Locator locator, FieldSelectorSpec... fields) {
        return new InputSpec(mimeType(),
                List.of(new RecordSelectorSpec("bookings", locator, List.of(fields))),
                List.of(), Map.of());
    }
}
