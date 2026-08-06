package io.github.ralfspoeth.xldr.swift.test;

import io.github.ralfspoeth.xldr.spec.InputSpec;
import io.github.ralfspoeth.xldr.swift.SwiftInputAdapterFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SwiftInputAdapterTest {

    @Test
    void parse() throws IOException {
        var src = """
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
        var is = new ByteArrayInputStream(src.getBytes());
        var ia = new SwiftInputAdapterFactory().createInputAdapter(new InputSpec(
                "text/plain", null, null, List.of(), List.of(), Map.of()
        ));
        ia.parse(is, "asdf", Set.of());
    }
}