package io.github.ralfspoeth.xldr.swift.test;

/**
 * Realistic FIN messages for the tests.
 * <p>
 * Written out in full rather than assembled from parts: what these tests are
 * about is the shape of a real message - the optional blocks, the tag order, the
 * continuation lines - and a builder would hide exactly the details under test.
 */
final class Messages {

    private Messages() {
    }

    /**
     * An MT940 customer statement: opening balance, two bookings each with an
     * information line, closing and available balance. Blocks 3 and 5 are
     * present.
     * <p>
     * Both {@code :86:} tags run to a second line, which is what a real
     * statement looks like and what the adapter currently drops.
     */
    static final String MT940 = """
            {1:F01BANKDEFFXXXX0000000000}{2:O9401044260806BICODEMMXXXX00000000002608061044N}{3:{108:20260806001}}{4:
            :20:STMT20260806
            :25:DE12345678901234567890
            :28C:145/1
            :60F:C260806EUR54320,50
            :61:2608060806D1250,00NTRFBRF-998234//NONREF
            :86:INVOICE 998234 FEES FOR JULY
            SUPPLIER XYZ SERVICES
            :61:2608060806C8500,00NTRFBRF-998235//NONREF
            :86:CREDIT RECEIVED FROM CLIENT ABC
            INV-2026-4412
            :62F:C260806EUR61570,50
            :64:C260806EUR61570,50
            -}{5:{MAC:12345678}{CHK:ABC123XYZ456}}""";

    /**
     * An MT103 single customer credit transfer, with neither a user header nor a
     * trailer - the case that shows what happens to a field selector naming a
     * block the message does not carry.
     */
    static final String MT103 = """
            {1:F01SENDERXXAXXX0000000000}{2:I103RECEIVERXXXXN}{4:
            :20:REF20260806001
            :23B:CRED
            :32A:260806EUR12500,00
            :50K:/DE89370400440532013000
            MUSTERMANN GMBH
            HAUPTSTRASSE 1
            :59:/FR1420041010050500013M02606
            SOCIETE EXEMPLE SA
            :70:INVOICE 2026-0042
            :71A:SHA
            -}""";

    /**
     * An MT535 statement of holdings: two instruments, the first of them split
     * over two sub-balances.
     * <p>
     * Structurally a different animal from MT940 and MT103. Where those are a
     * flat run of tags, the securities and corporate-action messages delimit
     * their sequences explicitly with {@code :16R:} start-of-block and
     * {@code :16S:} end-of-block, carrying the block's name as the value, and
     * those blocks <em>nest</em>:
     *
     * <pre>
     * &lt;GENL&gt;   ... &lt;/GENL&gt;
     * &lt;FIN&gt;    &lt;SUBBAL&gt;...&lt;/SUBBAL&gt; &lt;SUBBAL&gt;...&lt;/SUBBAL&gt; &lt;/FIN&gt;
     * &lt;FIN&gt;    ... &lt;/FIN&gt;
     * &lt;ADDINFO&gt; ... &lt;/ADDINFO&gt;
     * </pre>
     *
     * Here to pin what this adapter does with such a message, which is not the
     * same as supporting it.
     */
    static final String MT535 = """
            {1:F01BANKDEFFAXXX0000000000}{2:O5351200260806CUSTDEFFXXXX00000000002608061200N}{4:
            :16R:GENL
            :28E:1/ONLY
            :13A::STAT//535
            :20C::SEME//STMT20260806001
            :23G:NEWM
            :98A::STAT//20260806
            :22F::SFRE//DAIL
            :22F::CODE//COMP
            :22F::STTY//CUST
            :22H::STBA//SETT
            :97A::SAFE//DEPOT-4711
            :17B::ACTI//Y
            :16S:GENL
            :16R:FIN
            :35B:ISIN DE0007236101
            SIEMENS AG
            NAMENS-AKT.
            :93B::AGGR//UNIT/1500,
            :16R:SUBBAL
            :93C::AVAI//UNIT/1200,
            :94F::SAFE//NCSD/DAKVDEFFXXX
            :16S:SUBBAL
            :16R:SUBBAL
            :93C::NAVL//UNIT/300,
            :94F::SAFE//NCSD/DAKVDEFFXXX
            :16S:SUBBAL
            :16S:FIN
            :16R:FIN
            :35B:ISIN US0378331005
            APPLE INC
            :93B::AGGR//UNIT/500,
            :16S:FIN
            :16R:ADDINFO
            :95P::PERS//BANKDEFFXXX
            :16S:ADDINFO
            -}""";

    /**
     * An intraday MT940, which carries the <em>intermediate</em> balance options
     * {@code :60M:} and {@code :62M:} where the end-of-day statement carries
     * {@code :60F:} and {@code :62F:}. The Message Reference Guide calls the
     * pair {@code 60a} and {@code 62a}.
     */
    static final String MT940_INTRADAY = """
            {1:F01BANKDEFFXXXX0000000000}{2:O9401044260806BICODEMMXXXX00000000002608061044N}{4:
            :20:STMT20260806-2
            :25:DE12345678901234567890
            :28C:145/2
            :60M:C260806EUR61570,50
            :61:2608060806D400,00NTRFBRF-998236//NONREF
            :86:INTRADAY DEBIT
            :62M:C260806EUR61170,50
            -}""";

    /**
     * A statement whose last {@code :61:} has no {@code :86:} after it, for the
     * rule that only a complete tag sequence produces a record.
     */
    static final String MT940_DANGLING_LINE = """
            {1:F01BANKDEFFXXXX0000000000}{2:O9401044260806BICODEMMXXXX00000000002608061044N}{4:
            :20:STMT20260806
            :61:2608060806D1250,00NTRFBRF-1//NONREF
            :86:FIRST INFO
            :61:2608060806C8500,00NTRFBRF-2//NONREF
            :62F:C260806EUR61570,50
            -}""";
}
