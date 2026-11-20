package com.kpmg.qtracker.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The attachment types in one place: .msg and .zip added, checked by extension and their signature. */
class AttachmentTypesTest {

    static final byte[] ZIP = {'P', 'K', 3, 4, 20, 0, 0, 0};
    static final byte[] MSG = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
    static final byte[] EXE = {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0};

    @Test
    void theList_labelAndAccept_comeFromOnePlace() {
        assertThat(AttachmentTypes.extensions()).containsExactly("pdf", "docx", "doc", "xlsx", "xls", "csv", "png", "jpg",
                "jpeg", "msg", "zip");
        assertThat(AttachmentTypes.label()).isEqualTo("PDF, DOCX, DOC, XLSX, XLS, CSV, PNG, JPG, JPEG, MSG, ZIP");
        assertThat(AttachmentTypes.accept()).isEqualTo(".pdf,.docx,.doc,.xlsx,.xls,.csv,.png,.jpg,.jpeg,.msg,.zip");
    }

    @Test
    void theExtension_inAnyCase_decides() {
        assertThat(AttachmentTypes.allowedName("Mail.MSG")).isTrue();
        assertThat(AttachmentTypes.allowedName("pack.Zip")).isTrue();
        assertThat(AttachmentTypes.allowedName("run.exe")).isFalse();
        assertThat(AttachmentTypes.allowedName("pack.zip.exe")).isFalse();
        assertThat(AttachmentTypes.allowedName("noextension")).isFalse();
        assertThat(AttachmentTypes.allowedName("trailingdot.zip.")).isFalse();
    }

    @Test
    void zipAndMsg_mustStartWithTheirSignature_theOthersAreTakenByExtension() {
        assertThat(AttachmentTypes.contentMatches("a.zip", ZIP)).isTrue();
        assertThat(AttachmentTypes.contentMatches("empty.zip", new byte[] {'P', 'K', 5, 6})).isTrue();
        assertThat(AttachmentTypes.contentMatches("a.msg", MSG)).isTrue();
        assertThat(AttachmentTypes.contentMatches("program.zip", EXE)).isFalse();
        assertThat(AttachmentTypes.contentMatches("program.msg", EXE)).isFalse();
        assertThat(AttachmentTypes.contentMatches("tiny.zip", new byte[] {'P'})).isFalse();
        assertThat(AttachmentTypes.contentMatches("zip-as.msg", ZIP)).isFalse();
        assertThat(AttachmentTypes.contentMatches("doc.pdf", EXE)).isTrue();
    }

    @Test
    void theDownloadType() {
        assertThat(AttachmentTypes.mimeType("a.msg")).isEqualTo("application/vnd.ms-outlook");
        assertThat(AttachmentTypes.mimeType("A.ZIP")).isEqualTo("application/zip");
        assertThat(AttachmentTypes.mimeType("x.exe")).isEqualTo("application/octet-stream");
    }
}
