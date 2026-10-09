package com.kpmg.qtracker.service.userimport;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Reading the people file (made-up people only). */
class UsersCsvTest {

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    @Test
    void utf8WithBom_commas_quotedAccess() {
        UsersCsv.Table table = UsersCsv.read(withBom("""
                Name,User,Role,Access,Status\r
                Aidar,"Testov, Aidar",User,"My Controls, Edit",Active\r
                Irina,"Samplova, Irina",KDN,"All KDN Controls, Read only",Active\r
                """));

        assertThat(table.ok()).isTrue();
        assertThat(table.encoding()).isEqualTo("UTF-8 with BOM");
        assertThat(table.delimiter()).isEqualTo("comma");
        assertThat(table.header()).containsExactly("Name", "User", "Role", "Access", "Status");
        assertThat(table.records()).hasSize(2);
        UsersCsv.Record first = table.records().get(0);
        assertThat(first.line()).isEqualTo(2);
        assertThat(first.get(UsersCsv.USER)).isEqualTo("Testov, Aidar");
        assertThat(first.get(UsersCsv.ACCESS)).isEqualTo("My Controls, Edit");
        assertThat(first.error()).isNull();
        assertThat(table.records().get(1).line()).isEqualTo(3);
    }

    @Test
    void utf8WithoutBom_semicolons_headerInAnyCaseAndOrder() {
        UsersCsv.Table table = UsersCsv.read("""
                status;ROLE;user;access
                Active;SoQM Team;Testov, Aidar;SoQM Team
                """.getBytes(StandardCharsets.UTF_8));

        assertThat(table.ok()).isTrue();
        assertThat(table.encoding()).isEqualTo("UTF-8");
        assertThat(table.delimiter()).isEqualTo("semicolon");
        UsersCsv.Record record = table.records().get(0);
        assertThat(record.get(UsersCsv.USER)).isEqualTo("Testov, Aidar");
        assertThat(record.get(UsersCsv.ROLE)).isEqualTo("SoQM Team");
        assertThat(record.get(UsersCsv.NAME)).isEmpty();
    }

    @Test
    void tabs_unquotedCommasInsideFields() {
        UsersCsv.Table table = UsersCsv.read(withBom(
                "Name\tUser\tRole\tAccess\tStatus\nAidar\tTestov, Aidar\tUser\tAll Controls, Read Only\tActive\n"));

        assertThat(table.delimiter()).isEqualTo("tab");
        assertThat(table.records().get(0).get(UsersCsv.ACCESS)).isEqualTo("All Controls, Read Only");
    }

    @Test
    void utf16LittleEndianWithBom_asExcelSavesUnicodeText() {
        byte[] text = "User\tRole\tAccess\tStatus\r\nИванов, Пётр\tKDN\t\tActive\r\n".getBytes(StandardCharsets.UTF_16LE);
        byte[] bytes = new byte[text.length + 2];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xFE;
        System.arraycopy(text, 0, bytes, 2, text.length);

        UsersCsv.Table table = UsersCsv.read(bytes);

        assertThat(table.encoding()).isEqualTo("UTF-16LE with BOM");
        assertThat(table.records().get(0).get(UsersCsv.USER)).isEqualTo("Иванов, Пётр");
    }

    @Test
    void quotes_doubledQuotesAndLineBreaksInsideAField_keepTheLineOfTheRecord() {
        UsersCsv.Table table = UsersCsv.read(withBom("""
                User,Role,Access,Status
                "O""Brien, Pat",User,"My Controls, Edit","Act
                ive"
                "Smith, Mary Ann",KDN,,Active
                """));

        assertThat(table.records()).hasSize(2);
        assertThat(table.records().get(0).get(UsersCsv.USER)).isEqualTo("O\"Brien, Pat");
        assertThat(table.records().get(0).line()).isEqualTo(2);
        assertThat(table.records().get(1).line()).isEqualTo(4);
    }

    @Test
    void blankLinesAreSkipped_aLastLineWithoutBreakIsRead() {
        UsersCsv.Table table = UsersCsv.read(withBom("User,Role,Access,Status\n\n\"Testov, Aidar\",KDN,,Active"));

        assertThat(table.records()).hasSize(1);
        assertThat(table.records().get(0).line()).isEqualTo(3);
    }

    @Test
    void aRecordWithTooFewOrTooManyFields_isAnError() {
        UsersCsv.Table table = UsersCsv.read(withBom("""
                User,Role,Access,Status
                Testov,User,My Controls,Edit,Active
                "Samplova, Irina",KDN
                """));

        assertThat(table.records().get(0).error()).isEqualTo("5 fields where the header has 4");
        assertThat(table.records().get(1).error()).isEqualTo("2 fields where the header has 4");
    }

    @Test
    void anUnclosedQuote_isAnError() {
        UsersCsv.Table table = UsersCsv.read(withBom("User,Role,Access,Status\n\"Testov, Aidar,KDN,,Active\n"));

        assertThat(table.records()).singleElement()
                .satisfies(record -> assertThat(record.error()).isEqualTo("a quoted field is not closed"));
    }

    @Test
    void anotherEncoding_isRefused_notGuessed() {
        byte[] cp1251 = "User;Role;Access;Status\nИванов, Пётр;KDN;;Active\n".getBytes(Charset.forName("windows-1251"));

        UsersCsv.Table table = UsersCsv.read(cp1251);

        assertThat(table.ok()).isFalse();
        assertThat(table.error()).contains("not UTF-8").contains("CSV UTF-8");
    }

    @Test
    void noExpectedHeader_isRefused() {
        UsersCsv.Table table = UsersCsv.read(withBom("Name,User,Role,Description\nAidar,\"Testov, Aidar\",User,x\n"));

        assertThat(table.ok()).isFalse();
        assertThat(table.error()).contains("User, Role, Access, Status");
    }

    @Test
    void anEmptyFile_isRefused() {
        assertThat(UsersCsv.read(new byte[0]).error()).isEqualTo("the file is empty");
        assertThat(UsersCsv.read(BOM).error()).isEqualTo("the file is empty");
    }

    private static byte[] withBom(String text) {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[BOM.length + body.length];
        System.arraycopy(BOM, 0, bytes, 0, BOM.length);
        System.arraycopy(body, 0, bytes, BOM.length, body.length);
        return bytes;
    }
}
