package io.github.ralfspoeth.xldr.swift.mt;

import io.github.ralfspoeth.xldr.ia.Field;
import io.github.ralfspoeth.xldr.ia.Formats;
import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.Result;
import io.github.ralfspoeth.xldr.ia.Row;
import io.github.ralfspoeth.xldr.spec.DataType;
import io.github.ralfspoeth.xldr.spec.FieldSelectorSpec;
import io.github.ralfspoeth.xldr.spec.InputSpec;
import io.github.ralfspoeth.xldr.spec.Locator;
import io.github.ralfspoeth.xldr.spec.RecordSelectorSpec;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.lang.Integer.parseInt;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toMap;

class MtInputAdapter implements InputAdapter {

    private static final Pattern MT_PATTERN = Pattern.compile(
            "^\\{1:(.+?)}\\s*\\{2:([IO].+?)}\\s*(?:\\{3:(.+?)}\\s*)?\\{4:(.*?)-}\\s*(?:\\{5:(.+?)})?$",
            Pattern.DOTALL
    );

    /**
     * One tag of the text block: the tag itself, then everything up to the next
     * one.
     * <p>
     * The content is whatever stands between this tag and the next, newlines
     * included - {@code :86:} on a statement and {@code :50K:} on a transfer run
     * to several lines by design, and a continuation line is part of the tag
     * rather than a thing of its own.
     * <p>
     * Neither the separating line break nor the one before {@code -}} is
     * captured: the lookahead ends the content at {@code \R} before the next
     * tag, or at the trailing whitespace before the end of the block. Because
     * {@code \R} takes {@code \r\n} as one unit, a message with DOS line endings
     * parses the same as one without - though a line break *inside* a tag is
     * handed over as it was found.
     */
    private static final Pattern TEXT_BLOCK_PATTERN = Pattern.compile(
            "(?m)^(:[0-9]{2}[A-Z]?:)([\\s\\S]*?)(?=\\R:[0-9]{2}[A-Z]?:|\\s*\\z)");

    /**
     * The block whose content is a sequence of tags, and the one a field
     * selector addresses when it names none.
     */
    private static final String TEXT_BLOCK = "4";

    /**
     * A block identifier: one to three alphanumeric characters.
     * <p>
     * Not a digit from 1 to 5. The five numbered blocks are the ones a FIN
     * user-to-user message carries, but the envelope allows others - SWIFT and
     * Alliance append a system block {@code {S:...}} to messages coming out of
     * the network - and the standard defines the identifier by shape rather than
     * by a closed set.
     */
    private static final Pattern BLOCK_ID = Pattern.compile("[0-9A-Za-z]{1,3}");

    /**
     * One tag of the text block, as read.
     */
    record Tag(String name, String value) {}

    /**
     * How a record is cut out of the tags of the text block.
     * <p>
     * Sealed because the MT family groups its repetitions in more than one way
     * and there is no reading of the text that covers them all. Which one a
     * message uses is a property of its type, so the spec says which.
     */
    sealed interface RecordSelector permits TagGroup, DelimitedSequence {
        /**
         * The records this selector finds, each an ordered run of tags.
         */
        List<List<Tag>> records(List<Tag> tags);
    }

    /**
     * An opener followed by a fixed run of further tags - the form the
     * statement messages need, and the one this adapter started with.
     */
    record TagGroup(List<Pattern> tags) implements RecordSelector {
        @Override
        public List<List<Tag>> records(List<Tag> all) {
            var records = new ArrayList<List<Tag>>();
            var current = new ArrayList<Tag>();
            int index = 0;
            for (var tag : all) {
                if (tags.get(index).matcher(tag.name()).matches()) {
                    current.add(tag);
                    index++;
                }
                if (index == tags.size()) {
                    records.add(List.copyOf(current));
                    index = 0;
                    current.clear();
                }
            }
            return records;
        }
    }

    /**
     * A sequence introduced by a delimiter field, as the treasury messages of
     * category 3 use: {@code :15A:}, {@code :15B:}, {@code :15C:} and so on
     * open Sequence A, B, C of an MT300 or MT320.
     * <p>
     * Unlike {@code :16R:} / {@code :16S:} these delimiters are
     * <strong>start-only</strong> - there is no closing field. A sequence
     * therefore runs until the next delimiter of the same field number, or until
     * the block ends. They do not nest.
     * <p>
     * The delimiter family is derived from the opener rather than configured: an
     * opener of {@code :15B:} makes the family {@code :15a:}, every option of
     * field 15. Nothing about the number 15 is built in, so a message type that
     * delimits with some other field works the same way.
     * <p>
     * The delimiter is itself the first tag of the record. Its value is normally
     * empty - {@code :15B:} stands alone on its line - which is why the fields
     * of such a record are addressed by tag rather than by position.
     */
    record DelimitedSequence(Pattern opener, Pattern family) implements RecordSelector {
        @Override
        public List<List<Tag>> records(List<Tag> all) {
            var records = new ArrayList<List<Tag>>();
            List<Tag> current = null;
            for (var tag : all) {
                if (opener.matcher(tag.name()).matches()) {
                    current = new ArrayList<>();
                    current.add(tag);
                    records.add(current);
                } else if (family.matcher(tag.name()).matches()) {
                    // a sibling sequence: this one is over
                    current = null;
                } else if (current != null) {
                    current.add(tag);
                }
            }
            return records.stream().map(List::copyOf).toList();
        }
    }

    /**
     * Which tag of a record a field selector reads.
     */
    sealed interface TagRef permits ByPosition, ByName {
        Optional<String> in(List<Tag> record);
    }

    /**
     * The nth tag of the record, counting from zero - the form that suits a
     * {@link TagGroup}, whose tags are a fixed run declared by the spec.
     */
    record ByPosition(int index) implements TagRef {
        @Override
        public Optional<String> in(List<Tag> record) {
            // a position beyond the record names nothing, which is a null field
            // rather than a failed load
            return index < record.size() ? Optional.of(record.get(index).value()) : Optional.empty();
        }
    }

    /**
     * The first tag of the record with this name - the form a
     * {@link DelimitedSequence} needs, whose members are many and mostly
     * optional, so that counting them is not possible.
     * <p>
     * The <em>first</em>: a sequence may carry the same tag twice, as an MT300
     * Sequence B carries {@code :53A:} for each side of the trade. Reaching the
     * second is not expressible, and a spec that needs it should select a
     * narrower record.
     */
    record ByName(Pattern tag) implements TagRef {
        @Override
        public Optional<String> in(List<Tag> record) {
            return record.stream()
                    .filter(t -> tag.matcher(t.name()).matches())
                    .findFirst()
                    .map(Tag::value);
        }
    }

    /**
     * @param type what the spec says this field is; {@link DataType#TEXT} where it
     *             said nothing, which is the rule every adapter follows
     */
    record FieldSelector(String block, TagRef tag, Pattern pattern, int group, DataType type) {
        FieldSelector {
            if (!BLOCK_ID.matcher(block).matches()) {
                throw new IllegalArgumentException(
                        "block identifier must be one to three alphanumeric characters, was '"
                                + block + "'");
            }
        }
    }

    record Records(RecordSelector selector, Map<String, FieldSelector> fieldSelectors) {}

    private final Map<String, Records> recordSelectors;

    /**
     * The feed's date and number patterns, applied to what a selector matched.
     * <p>
     * An MT field is text in a shape the standard fixes - a value date as
     * {@code YYMMDD}, an amount with a comma for the decimal mark - and neither is
     * what {@code LocalDateTime.parse} or {@code BigDecimal} read by default. So a
     * spec that declares a {@code DATE} or a {@code DECIMAL} here says
     * {@code dateFormat: yyMMdd} or a {@code locale} whose decimal separator is a
     * comma beside it, exactly as one would for a European CSV, and the shared
     * {@link Formats} does the rest.
     */
    private final Formats formats;

    public MtInputAdapter(InputSpec inputSpec) {
        formats = Formats.of(inputSpec.properties());
        recordSelectors = inputSpec.recordSelectors()
                .stream()
                .collect(toMap(
                        RecordSelectorSpec::name,
                        rs -> new Records(
                                parseRecordSelector(rs),
                                rs.fieldSelectors()
                                        .stream()
                                        .collect(toMap(
                                                        FieldSelectorSpec::name,
                                                        fs -> parseFields(fs.requireText(NOT_COUNTED),
                                                                typeOf(fs))
                                                )
                                        )
                        )
                ));
    }

    /** what the spec declared, or {@code TEXT} where it declared nothing */
    private static DataType typeOf(FieldSelectorSpec fs) {
        return fs.dataType() == null ? DataType.TEXT : fs.dataType();
    }

    /**
     * Separates the parts of a selector, in both the field and the record form.
     * <p>
     * A tilde because it is the one character absent from all three SWIFT
     * character sets that is also inert in a regular expression - the parts are
     * found by position, so the separator can appear neither in the data a
     * pattern describes nor in the pattern itself. {@code SEPARATOR.md} in the
     * project root has the argument, and why {@code /}, {@code |}, {@code _} and
     * {@code §} were each rejected.
     */
    static final char SEPARATOR = '~';

    /**
     * Why a field selector here is never an {@code nth}.
     * <p>
     * xldr's other adapters that count are counting something a record is made
     * of - the fields of a line, the elements of an array, the children of an
     * element. A SWIFT record is a run of tags addressed by their number, and the
     * same number may appear more than once in one record, so the n-th tag is
     * neither what a spec means nor stable between two messages of the same type.
     * Refused when the adapter is built, as the fixed-length adapter refuses it
     * and for the same reason.
     */
    private static final String NOT_COUNTED =
            "an MT record is a run of tags addressed by number, with no components to count";

    /**
     * {@link #SEPARATOR} as a regex, for {@link String#split(String)}.
     */
    private static final String SEPARATOR_REGEX = Pattern.quote(String.valueOf(SEPARATOR));

    private FieldSelector parseFields(String selector, DataType type) {
        int first = selector.indexOf(SEPARATOR);
        int second = selector.indexOf(SEPARATOR, first + 1);
        int last = selector.lastIndexOf(SEPARATOR);
        if (first == -1 || last == -1 || first + 1 == second || last == first) {
            throw new IllegalArgumentException(
                    "field selector '%s' must match [<block>]~[<tagNo>~]<pattern>~[<groupNo>]"
                            .formatted(selector)
            );
        } else {
            return new FieldSelector(
                    first == 0 ? TEXT_BLOCK : selector.substring(0, first),
                    second == last
                            ? new ByPosition(0)
                            : tagRef(selector.substring(first + 1, second), selector),
                    // DOTALL, because a tag's content may run to several lines
                    // and a selector that says .* means the whole of it. Without
                    // it the commonest selector of all silently yields null on
                    // exactly the tags that carry the most text.
                    Pattern.compile(
                            second == last ?
                                    selector.substring(first + 1, last) :
                                    selector.substring(second + 1, last),
                            Pattern.DOTALL
                    ),
                    last == selector.length() - 1 ? 0 : parseInt(selector.substring(last + 1)),
                    type
            );
        }
    }

    /**
     * A tag as a spec writes it: {@code :20:}, {@code :62F:}, or {@code :62a:}.
     */
    private static final Pattern TAG_SELECTOR = Pattern.compile(":[0-9]{2}[A-Za-z]?:");

    /**
     * Compiles one tag of a selector.
     * <p>
     * A trailing <em>lower-case</em> {@code a} is the Message Reference Guide's
     * own notation for "this field, whatever its option letter": {@code 62a}
     * covers {@code :62F:} and {@code :62M:}, {@code 25a} covers {@code :25:}
     * and {@code :25P:}. It is written here as it is written there, and it can
     * be told apart from a literal tag without ambiguity, because a real option
     * letter is always upper case.
     * <p>
     * Anything else is matched literally. The shape is checked rather than
     * assumed: a selector of {@code 61} or {@code :610:} would match no tag in
     * any message and load nothing, without complaint, which is the failure this
     * adapter has spent the most effort not having.
     *
     * @throws IllegalArgumentException if {@code tag} is not a tag
     */
    private static Pattern tagPattern(String tag, String selectorName) {
        if (!TAG_SELECTOR.matcher(tag).matches()) {
            throw new IllegalArgumentException("selector '" + selectorName
                    + "': '" + tag + "' is not a tag; expected :nn:, :nnA: or :nna:");
        }
        return tag.endsWith("a:")
                ? Pattern.compile(":" + tag.substring(1, 3) + "[A-Z]?:")
                : Pattern.compile(Pattern.quote(tag));
    }

    /**
     * Marks a record selector that names a delimited sequence rather than a run
     * of tags: {@code "seq~:15B:"}.
     * <p>
     * A word rather than a tag, because the two forms would otherwise be
     * indistinguishable - {@code ":15B:"} alone could mean either "one record
     * per :15B:, holding only that tag" or "the sequence :15B: opens". Guessing
     * between them is exactly the kind of thing that has cost this adapter a
     * defect before, so the spec says which.
     */
    private static final String SEQUENCE_PREFIX = "seq";

    /**
     * How the spec wants records cut out of the text block.
     * <p>
     * A selector is required rather than defaulted. xldr lets a record selector
     * leave it out, and for a CSV or a fixed-length file that sensibly means
     * "every record"; here a record is a run of tags, so an absent one names
     * nothing and there is no reading of it that could be right. Refusing it
     * outright beats loading a file that produces no rows and no complaint.
     * <p>
     * The two cases this adapter cannot honour raise their complaint through
     * {@link Locator#wrongBecause(String, String)}, which names the offending record selector -
     * useful in a spec that declares several - and says what the author wrote
     * rather than what they left out.
     * <p>
     * A blank selector no longer needs refusing here. This module used to lean
     * on {@code RecordSelectorSpec.requireSelector} for it, because {@code "  "}
     * would otherwise split into one tag that matches nothing; since xldr 0.35
     * {@link Locator.At} refuses a blank selector when it is constructed, so
     * such a spec cannot be built to hand over.
     *
     * @throws IllegalArgumentException if the records are not located, or the
     *                                  selector is not one of the forms above
     */
    private RecordSelector parseRecordSelector(RecordSelectorSpec spec) {
        // A discriminator picks records out of a flat file, where every line is a
        // candidate and the question is which to keep; saying nothing means every
        // record is one. An MT record is located instead - a tag group, or a
        // sequence an opener delimits - so neither reading is available here.
        var because = "an MT record is located rather than filtered: every record selector"
                + " here says which tags a record is cut from";
        return switch (spec.locator()) {
            case Locator.Where where -> throw where.wrongBecause(spec.name(), because);
            case Locator.Every every -> throw every.wrongBecause(spec.name(), because);
            case Locator.At(var selector) -> {
                var parts = selector.split(SEPARATOR_REGEX);
                if (SEQUENCE_PREFIX.equals(parts[0])) {
                    if (parts.length != 2) {
                        throw new IllegalArgumentException("record selector '" + spec.name()
                                + "': " + SEQUENCE_PREFIX + " takes exactly one delimiter tag, as in "
                                + SEQUENCE_PREFIX + SEPARATOR + ":15B:");
                    }
                    var opener = parts[1];
                    yield new DelimitedSequence(
                            tagPattern(opener, spec.name()),
                            // every option of the same field number: :15B: -> :15a:
                            tagPattern(":" + opener.substring(1, 3) + "a:", spec.name()));
                } else yield new TagGroup(Stream.of(parts)
                        .map(tag -> tagPattern(tag, spec.name()))
                        .toList());
            }
        };
    }

    /**
     * The tag part of a field selector: a number counts, a tag names.
     * <p>
     * Both forms are wanted, because the two record selectors differ in what can
     * be relied on. A {@link TagGroup} declares its tags, so counting them is
     * exact; a {@link DelimitedSequence} does not, and its members are mostly
     * optional, so only a name identifies one.
     */
    private static TagRef tagRef(String part, String selector) {
        if (TAG_SELECTOR.matcher(part).matches()) {
            return new ByName(tagPattern(part, selector));
        }
        try {
            return new ByPosition(parseInt(part));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("field selector '" + selector + "': '" + part
                    + "' is neither a tag position nor a tag", e);
        }
    }

    @Override
    public Result parse(InputStream source, String recordSelector, Set<String> fieldSelectors) throws IOException {
        try (var ais = new AsciiReader(source)) {
            var contents = ais.readAllAsString();
            var matcher = MT_PATTERN.matcher(contents);
            var rs = recordSelectors.get(recordSelector);
            // a name the spec does not declare is a typo in a mapping, and this
            // is the only place it can surface. It used to fall into the same
            // empty result as a file that is not an MT message at all, so a
            // mistyped mapping loaded nothing and reported success
            if (rs == null) {
                throw new IllegalArgumentException("no record selector named " + recordSelector
                        + "; the input spec declares " + recordSelectors.keySet());
            }
            var unknown = fieldSelectors.stream()
                    .filter(name -> !rs.fieldSelectors().containsKey(name))
                    .toList();
            if (!unknown.isEmpty()) {
                throw new IllegalArgumentException("record selector " + recordSelector
                        + " declares no field selector(s) " + unknown);
            }
            if (matcher.matches()) {
                var blocks = blocksOf(matcher);
                var fields = rs.fieldSelectors.entrySet()
                        .stream()
                        .filter(e -> fieldSelectors.contains(e.getKey()))
                        .map(e -> new Field(e.getKey(), e.getValue().type().clazz()))
                        .toList();
                List<Row> rows = parseRows(blocks, rs);
                return new Result(fields, rows.stream());
            } else {
                // the file is not a FIN message: a fact about the input rather
                // than about the spec, so no records rather than a refusal
                return new Result(List.of(), Stream.of());
            }
        }
    }

    /**
     * The blocks of the message, keyed by identifier - {@code "1"} through
     * {@code "5"}, and only those that are there.
     * <p>
     * A block the message does not carry is absent from the map rather than
     * present with a null, so a selector naming it resolves the same way as one
     * whose pattern does not match: no value, no exception.
     * <p>
     * That group <em>n</em> of {@link #MT_PATTERN} holds block <em>n</em> is the
     * one place the numbering is baked in, and the only thing standing between
     * this and a message whose blocks are read as they come.
     */
    private static Map<String, String> blocksOf(Matcher matcher) {
        var blocks = new LinkedHashMap<String, String>();
        for (int group = 1; group <= matcher.groupCount(); group++) {
            var content = matcher.group(group);
            if (content != null) {
                blocks.put(String.valueOf(group), content);
            }
        }
        return blocks;
    }

    /**
     * The tags of the text block, in the order the message carries them.
     */
    private static List<Tag> tagsOf(Map<String, String> blocks) {
        var tags = new ArrayList<Tag>();
        var matcher = TEXT_BLOCK_PATTERN.matcher(blocks.getOrDefault(TEXT_BLOCK, ""));
        while (matcher.find()) {
            tags.add(new Tag(matcher.group(1), matcher.group(2)));
        }
        return tags;
    }

    /**
     * What a matched selector yields, as the field declared it.
     * <p>
     * {@code TEXT} is handed back untouched rather than going through
     * {@link Formats}, which reads a blank value as absent. Here it is not: a
     * delimiter such as {@code :15B:} is a tag that is present and carries
     * nothing, and that is the whole of what it says. This adapter can tell an
     * empty match from no match at all - the pattern matched, or it did not - so
     * it should, as the XML adapter keeps an empty element distinct from a
     * missing one and for the same reason.
     * <p>
     * With a type declared, blank really is absent: there is no date and no
     * amount that an empty string could be.
     */
    private @Nullable Object valueOf(FieldSelector fs, String matched) {
        return fs.type() == DataType.TEXT ? matched : formats.parse(fs.type(), matched);
    }

    private List<Row> parseRows(final Map<String, String> blocks, final Records rs) {
        return rs.selector()
                .records(tagsOf(blocks))
                .stream()
                .<Row>map(record -> new Row() {
                    @Override
                    public @Nullable Object get(String name) {
                        return ofNullable(rs.fieldSelectors().get(name))
                                .flatMap(this::parseField)
                                .orElse(null);
                    }

                    private Optional<Object> parseField(FieldSelector fs) {
                        // the text block is the one addressed by tag; every other
                        // block is handed over whole, and one the message does not
                        // carry is simply not in the map
                        var segment = TEXT_BLOCK.equals(fs.block())
                                ? fs.tag().in(record).orElse(null)
                                : blocks.get(fs.block());
                        if (segment == null) {
                            return Optional.empty();
                        }
                        var matcher = fs.pattern().matcher(segment);
                        return matcher.matches()
                                ? ofNullable(valueOf(fs, matcher.group(fs.group())))
                                : Optional.empty();
                    }
                })
                .toList();
    }
}
