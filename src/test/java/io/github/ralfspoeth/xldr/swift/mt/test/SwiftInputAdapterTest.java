package io.github.ralfspoeth.xldr.swift.mt.test;

import io.github.ralfspoeth.xldr.ia.Field;
import io.github.ralfspoeth.xldr.ia.Row;
import io.github.ralfspoeth.xldr.spec.DataType;
import io.github.ralfspoeth.xldr.spec.FieldSelectorSpec;
import io.github.ralfspoeth.xldr.spec.InputSpec;
import io.github.ralfspoeth.xldr.spec.RecordSelectorSpec;
import io.github.ralfspoeth.xldr.swift.mt.SwiftInputAdapterFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The adapter against whole messages, through the public surface only: a spec
 * goes in and rows come out, the way the loader uses it.
 */
class SwiftInputAdapterTest {

    /**
     * The two halves of a booking: {@code :61:} is the line itself and
     * {@code :86:} the information belonging to it, so a record is the pair.
     */
    @Test
    void pairsEachStatementLineWithItsInformation() throws IOException {
        var rows = rows(Messages.MT940, selector("booking", ":61:~:86:",
                        field("line", "~.*~0"),
                        field("info", "~1~.*~0")),
                "booking", Set.of("line", "info"));

        assertEquals(2, rows.size());
        assertAll(
                () -> assertEquals("2608060806D1250,00NTRFBRF-998234//NONREF", rows.getFirst().get("line")),
                () -> assertEquals("INVOICE 998234 FEES FOR JULY\nSUPPLIER XYZ SERVICES",
                        rows.getFirst().get("info"), "the continuation line belongs to the tag"),
                () -> assertEquals("2608060806C8500,00NTRFBRF-998235//NONREF", rows.get(1).get("line")),
                () -> assertEquals("CREDIT RECEIVED FROM CLIENT ABC\nINV-2026-4412", rows.get(1).get("info"))
        );
    }

    /**
     * A selector of one tag repeats over every occurrence of it, which is the
     * degenerate case of the same rule.
     */
    @Test
    void repeatsOverASingleTag() throws IOException {
        var rows = rows(Messages.MT940, selector("lines", ":61:", field("line", "~.*~0")),
                "lines", Set.of("line"));

        assertEquals(2, rows.size());
        assertTrue(String.valueOf(rows.getFirst().get("line")).startsWith("2608060806D1250,00"));
    }

    /**
     * A tag that occurs once yields one record - here the statement reference,
     * which is how a spec loads the statement header rather than its bookings.
     */
    @Test
    void yieldsOneRecordForATagThatOccursOnce() throws IOException {
        var rows = rows(Messages.MT940, selector("statement", ":20:", field("ref", "~.*~0")),
                "statement", Set.of("ref"));

        assertEquals(1, rows.size());
        assertEquals("STMT20260806", rows.getFirst().get("ref"));
    }

    /**
     * Only a complete sequence produces a record. The message ends with a
     * {@code :61:} that has no {@code :86:} after it, and that half-record is
     * dropped rather than delivered with a null.
     */
    @Test
    void dropsATrailingIncompleteSequence() throws IOException {
        var rows = rows(Messages.MT940_DANGLING_LINE, selector("booking", ":61:~:86:",
                        field("line", "~.*~0"),
                        field("info", "~1~.*~0")),
                "booking", Set.of("line", "info"));

        assertEquals(1, rows.size(), "the second :61: has no :86: and must not become a record");
        assertEquals("FIRST INFO", rows.getFirst().get("info"));
    }

    /**
     * The blocks around the text block are addressed by number, and are the same
     * on every record of the message.
     */
    @Test
    void readsTheHeaderAndTrailerBlocks() throws IOException {
        var rows = rows(Messages.MT940, selector("statement", ":20:",
                        field("basic", "1~.*~0"),
                        field("application", "2~.*~0"),
                        field("user", "3~.*~0"),
                        field("trailer", "5~.*~0")),
                "statement", Set.of("basic", "application", "user", "trailer"));

        var row = rows.getFirst();
        assertAll(
                () -> assertEquals("F01BANKDEFFXXXX0000000000", row.get("basic")),
                () -> assertEquals("O9401044260806BICODEMMXXXX00000000002608061044N", row.get("application")),
                () -> assertEquals("{108:20260806001}", row.get("user")),
                () -> assertEquals("{MAC:12345678}{CHK:ABC123XYZ456}", row.get("trailer"))
        );
    }

    /**
     * The capture group is what makes a selector useful: a booking line is a run
     * of fixed-width parts, and a spec wants them one at a time.
     */
    @Test
    void handsOverTheNamedCaptureGroup() throws IOException {
        var rows = rows(Messages.MT940, selector("booking", ":61:~:86:",
                        field("valueDate", "~([0-9]{6}).*~1"),
                        field("side", "~[0-9]{10}([CD]).*~1"),
                        field("whole", "~.*~0")),
                "booking", Set.of("valueDate", "side", "whole"));

        assertAll(
                () -> assertEquals("260806", rows.getFirst().get("valueDate")),
                () -> assertEquals("D", rows.getFirst().get("side")),
                () -> assertEquals("C", rows.get(1).get("side"), "the second booking is a credit"),
                () -> assertTrue(String.valueOf(rows.getFirst().get("whole")).startsWith("260806"),
                        "group 0 is the whole match")
        );
    }

    /**
     * The pattern is applied with {@code matches()}, so it has to describe the
     * whole segment. One that does not leaves the field null - the row still
     * loads, with that column empty.
     */
    @Test
    void yieldsNullWhereThePatternDoesNotMatchTheWholeSegment() throws IOException {
        var rows = rows(Messages.MT940, selector("booking", ":61:",
                        field("impossible", "~XX([0-9]+)~1"),
                        // a prefix without a trailing .* does not match either
                        field("prefixOnly", "~([0-9]{6})~1")),
                "booking", Set.of("impossible", "prefixOnly"));

        assertAll(
                () -> assertNull(rows.getFirst().get("impossible")),
                () -> assertNull(rows.getFirst().get("prefixOnly"), "matches() is not find()")
        );
    }

    /**
     * An MT103 carries neither a user header nor a trailer, and its text block
     * is a different set of tags - the adapter is not MT940-specific.
     */
    @Test
    void readsAnMt103WithoutTheOptionalBlocks() throws IOException {
        var rows = rows(Messages.MT103, selector("transfer", ":20:~:32A:~:71A:",
                        field("reference", "~.*~0"),
                        field("currency", "~1~[0-9]{6}([A-Z]{3}).*~1"),
                        field("amount", "~1~[0-9]{6}[A-Z]{3}(.*)~1"),
                        field("charges", "~2~.*~0")),
                "transfer", Set.of("reference", "currency", "amount", "charges"));

        assertEquals(1, rows.size());
        var row = rows.getFirst();
        assertAll(
                () -> assertEquals("REF20260806001", row.get("reference")),
                () -> assertEquals("EUR", row.get("currency")),
                () -> assertEquals("12500,00", row.get("amount")),
                () -> assertEquals("SHA", row.get("charges"))
        );
    }

    /**
     * A record selector the spec does not declare is not an error: the adapter
     * is asked for one selector at a time and answers for the ones it knows.
     */
    @Test
    void yieldsNothingForAnUnknownRecordSelector() throws IOException {
        var result = new SwiftInputAdapterFactory()
                .createInputAdapter(spec(selector("booking", ":61:", field("line", "~.*~0"))))
                .parse(stream(Messages.MT940), "nosuchselector", Set.of("line"));

        assertAll(
                () -> assertEquals(List.of(), result.fields()),
                () -> assertEquals(0, result.rows().count())
        );
    }

    /**
     * Input that is not a FIN message at all yields no rows rather than an
     * exception, so one wrongly routed file does not fail the load.
     */
    @Test
    void yieldsNothingForInputThatIsNotAFinMessage() throws IOException {
        var result = new SwiftInputAdapterFactory()
                .createInputAdapter(spec(selector("booking", ":61:", field("line", "~.*~0"))))
                .parse(stream("id,name\n1,Alice\n"), "booking", Set.of("line"));

        assertEquals(0, result.rows().count());
    }

    /**
     * The fields are the declared selectors narrowed to the ones asked for -
     * the loader passes only the columns its mapping binds.
     */
    @Test
    void exposesOnlyTheRequestedFields() throws IOException {
        var result = new SwiftInputAdapterFactory()
                .createInputAdapter(spec(selector("booking", ":61:",
                        field("line", "~.*~0"),
                        field("valueDate", "~([0-9]{6}).*~1"))))
                .parse(stream(Messages.MT940), "booking", Set.of("valueDate"));

        assertEquals(Set.of("valueDate"),
                result.fields().stream().map(Field::name).collect(Collectors.toSet()));
        assertEquals(String.class, result.fields().getFirst().type());
    }

    /**
     * A tag runs until the next one, so its content may be several lines, and a
     * real {@code :86:} usually is. Neither the separating line break nor the
     * one before {@code -}} belongs to it.
     */
    @Test
    void capturesEveryLineOfAMultiLineTag() throws IOException {
        var rows = rows(Messages.MT940, selector("booking", ":61:~:86:",
                        field("dotAll", "~1~.*~0"),
                        field("explicit", "~1~[\\s\\S]*~0"),
                        field("secondLine", "~1~[^\\n]*\\n(.*)~1")),
                "booking", Set.of("dotAll", "explicit", "secondLine"));

        var row = rows.getFirst();
        assertAll(
                () -> assertEquals("INVOICE 998234 FEES FOR JULY\nSUPPLIER XYZ SERVICES", row.get("dotAll"),
                        ". crosses a line break, so .* is the whole tag"),
                () -> assertEquals(row.get("dotAll"), row.get("explicit")),
                () -> assertEquals("SUPPLIER XYZ SERVICES", row.get("secondLine"))
        );
    }

    /**
     * The last tag of the block runs up to the closing {@code -}}, and the line
     * break before it is not part of its content.
     */
    @Test
    void doesNotCaptureTheLineBreakBeforeTheEndOfTheBlock() throws IOException {
        var rows = rows(Messages.MT940, selector("closing", ":64:", field("balance", "~.*~0")),
                "closing", Set.of("balance"));

        assertEquals("C260806EUR61570,50", rows.getFirst().get("balance"));
    }

    // ---- optional blocks and absent selectors --------------------------------

    /**
     * Blocks 3 and 5 are optional. Naming one the message does not carry leaves
     * the field null, the way a pattern that does not match does - the row still
     * loads, with that column empty.
     * <p>
     * The same holds for a block that is not part of the message at all: since
     * the identifier is checked for shape rather than against a closed set, an
     * unknown one resolves to nothing instead of failing.
     */
    @Test
    void yieldsNullForABlockTheMessageDoesNotCarry() throws IOException {
        var rows = rows(Messages.MT103, selector("transfer", ":20:",
                        field("userHeader", "3~.*~0"),
                        field("trailer", "5~.*~0"),
                        field("system", "S~.*~0")),
                "transfer", Set.of("userHeader", "trailer", "system"));

        var row = rows.getFirst();
        assertAll(
                () -> assertNull(row.get("userHeader")),
                () -> assertNull(row.get("trailer")),
                () -> assertNull(row.get("system"), "no block S in this message")
        );
    }

    /**
     * xldr lets a record selector leave the selector out, and this adapter
     * refuses it: a SWIFT record is a tag sequence, so an absent one names
     * nothing. It is refused when the adapter is built, not when a file
     * arrives, and the message names the selector at fault.
     */
    @Test
    void refusesARecordSelectorWithoutASelector() {
        var withoutSelector = spec(new RecordSelectorSpec("booking", null,
                List.of(field("line", "~.*~0"))));
        var blank = spec(new RecordSelectorSpec("booking", "  ",
                List.of(field("line", "~.*~0"))));
        var factory = new SwiftInputAdapterFactory();

        var thrown = assertThrows(IllegalArgumentException.class,
                () -> factory.createInputAdapter(withoutSelector));
        assertAll(
                () -> assertTrue(thrown.getMessage().contains("booking"),
                        "the message should name the selector: " + thrown.getMessage()),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> factory.createInputAdapter(blank),
                        "a blank selector would otherwise split into one tag that matches nothing")
        );
    }

    // ---- option letters ------------------------------------------------------

    /**
     * A lower-case {@code a} in a tag is the Message Reference Guide's notation
     * for "whatever option letter": {@code :62a:} is field 62 in either of its
     * forms, {@code :62F:} on an end-of-day statement and {@code :62M:} on an
     * intraday one. One spec then reads both.
     */
    @Test
    void matchesAnyOptionLetterForATagWrittenWithA() {
        var closing = selector("closing", ":62a:", field("balance", "~.*~0"));

        assertAll(
                () -> assertEquals("C260806EUR61570,50",
                        rows(Messages.MT940, closing, "closing", Set.of("balance"))
                                .getFirst().get("balance"), ":62F: on the end-of-day statement"),
                () -> assertEquals("C260806EUR61170,50",
                        rows(Messages.MT940_INTRADAY, closing, "closing", Set.of("balance"))
                                .getFirst().get("balance"), ":62M: on the intraday one")
        );
    }

    /**
     * The option letter of a literal tag is still significant: {@code :62F:}
     * does not match {@code :62M:}. Only the lower-case form is a wildcard.
     */
    @Test
    void treatsAnUpperCaseOptionLetterAsLiteral() throws IOException {
        var rows = rows(Messages.MT940_INTRADAY,
                selector("closing", ":62F:", field("balance", "~.*~0")),
                "closing", Set.of("balance"));

        assertEquals(List.of(), rows, "the intraday statement carries :62M:, not :62F:");
    }

    // ---- MT535: what a delimited message does here ---------------------------

    /**
     * The flat half of an MT535 works. Its tags are ordinary {@code :nnA:} tags,
     * so a selector naming one repeats over it, and the multi-line {@code :35B:}
     * arrives whole.
     * <p>
     * The quantity pattern matches {@code :AGGR//UNIT/1500,} with the slashes
     * written out, which is the point of separating a selector on {@code ~}: the
     * slash is in the SWIFT character set and turns up in most values worth a
     * pattern.
     */
    @Test
    void readsTheFlatTagsOfAnMt535() throws IOException {
        var rows = rows(Messages.MT535, selector("holding", ":35B:~:93B:",
                        field("instrument", "~.*~0"),
                        field("isin", "~ISIN ([A-Z]{2}[A-Z0-9]{10}).*~1"),
                        field("quantity", "~1~.*UNIT/([0-9]+),~1")),
                "holding", Set.of("instrument", "isin", "quantity"));

        assertEquals(2, rows.size(), "one record per instrument");
        assertAll(
                () -> assertEquals("ISIN DE0007236101\nSIEMENS AG\nNAMENS-AKT.",
                        rows.getFirst().get("instrument")),
                () -> assertEquals("DE0007236101", rows.getFirst().get("isin")),
                () -> assertEquals("1500", rows.getFirst().get("quantity")),
                () -> assertEquals("US0378331005", rows.get(1).get("isin")),
                () -> assertEquals("500", rows.get(1).get("quantity"))
        );
    }

    /**
     * The nested half does not, and this test says so rather than pretending
     * otherwise. A record selector is a flat tag sequence; {@code :16R:} and
     * {@code :16S:} delimit blocks that contain other blocks, and nesting is
     * invisible to a rule that only knows "the next tag I am waiting for".
     * <p>
     * The message nests as
     * <pre>
     * GENL, FIN(SUBBAL, SUBBAL), FIN, ADDINFO
     * </pre>
     * and pairing {@code :16R:} with {@code :16S:} yields <em>FIN opened,
     * SUBBAL closed</em> as one record - the outer block's start against the
     * inner block's end - after which the real {@code :16S:FIN} is skipped
     * because the walker is looking for a {@code :16R:} again.
     * <p>
     * Nothing here is a reason to change the adapter: MT5xx and the corporate
     * action messages are out of scope, and the point of the test is that the
     * boundary is where it is thought to be. What it must never do is look like
     * it worked - which, on the two well-formed blocks, it does.
     */
    @Test
    void cannotSeeTheBlockNestingOfAnMt535() throws IOException {
        var rows = rows(Messages.MT535, selector("block", ":16R:~:16S:",
                        field("opened", "~.*~0"),
                        field("closed", "~1~.*~0")),
                "block", Set.of("opened", "closed"));

        var pairs = rows.stream().map(r -> r.get("opened") + "/" + r.get("closed")).toList();
        assertEquals(List.of(
                        "GENL/GENL",
                        // the outer FIN paired with the first SUBBAL's end
                        "FIN/SUBBAL",
                        "SUBBAL/SUBBAL",
                        // and this is the *second* FIN; the first one's :16S: was dropped
                        "FIN/FIN",
                        "ADDINFO/ADDINFO"),
                pairs,
                "block nesting is not understood; two of these five pairs are wrong");
    }

    // ---- helpers ------------------------------------------------------------

    private static List<Row> rows(String message, RecordSelectorSpec rs, String name, Set<String> wanted)
            throws IOException {
        try (var stream = new SwiftInputAdapterFactory()
                .createInputAdapter(spec(rs))
                .parse(stream(message), name, wanted)
                .rows()) {
            return stream.toList();
        }
    }

    private static InputSpec spec(RecordSelectorSpec... selectors) {
        return new InputSpec("text/x-swift", null, null, List.of(selectors), List.of(), Map.of());
    }

    private static RecordSelectorSpec selector(String name, String tags, FieldSelectorSpec... fields) {
        return new RecordSelectorSpec(name, tags, List.of(fields));
    }

    private static FieldSelectorSpec field(String name, String selector) {
        return new FieldSelectorSpec(name, selector, DataType.STRING);
    }

    private static ByteArrayInputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.US_ASCII));
    }
}
