package com.kpmg.qtracker.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class FileStorageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void saveFile_usesControlFolderAndUniqueNames() throws Exception {
        FileStorageService service = new FileStorageService();
        Field uploadDirField = FileStorageService.class.getDeclaredField("uploadDir");
        uploadDirField.setAccessible(true);
        uploadDirField.set(service, tempDir.toString());

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "report.pdf",
                "application/pdf",
                "data".getBytes(StandardCharsets.UTF_8)
        );

        String first = service.saveFile(file, "HR11");
        String second = service.saveFile(file, "HR11");

        assertNotEquals(first, second);
        Path firstPath = tempDir.resolve("HR11").resolve(first);
        Path secondPath = tempDir.resolve("HR11").resolve(second);
        assertThat(Files.exists(firstPath)).isTrue();
        assertThat(Files.exists(secondPath)).isTrue();
    }

    @Test
    void toStoredFilename_keepsCyrillicSoDifferentNamesDoNotCollide() {
        assertThat(FileStorageService.toStoredFilename("Акт сверки.pdf")).isEqualTo("Акт_сверки.pdf");
        assertNotEquals(FileStorageService.toStoredFilename("Акт.pdf"), FileStorageService.toStoredFilename("Док.pdf"));
        assertThat(FileStorageService.toStoredFilename("../../etc/passwd")).doesNotContain("/");
    }

    @Test
    void downloadFile_readsFromTheControlFolder_andOldFilesFromTheUploadRoot() throws Exception {
        FileStorageService service = serviceOn(tempDir);
        Files.createDirectories(tempDir.resolve("HR1"));
        Files.writeString(tempDir.resolve("HR1").resolve("report.pdf"), "in folder");
        Files.writeString(tempDir.resolve("20260101_abcd1234_old.pdf"), "in root");

        assertThat(service.downloadFile("report.pdf", "HR1")).asString().isEqualTo("in folder");
        assertThat(service.downloadFile("20260101_abcd1234_old.pdf", "HR1")).asString().isEqualTo("in root");
    }

    @Test
    void downloadFile_looksInTheGivenFoldersInOrder_thenTheUploadRoot() throws Exception {
        FileStorageService service = serviceOn(tempDir);
        Files.createDirectories(tempDir.resolve("HR-CTRL-MF-5_FY26_Central_2H"));
        Files.writeString(tempDir.resolve("HR-CTRL-MF-5_FY26_Central_2H").resolve("cv.pdf"), "earlier id");
        Files.writeString(tempDir.resolve("20260101_abcd1234_old.pdf"), "in root");

        List<String> folders = List.of("KDN", "HR-CTRL-MF-5/FY26/Central/2H");
        assertThat(service.downloadFile("cv.pdf", folders)).asString().isEqualTo("earlier id");
        assertThat(service.downloadFile("20260101_abcd1234_old.pdf", folders)).asString().isEqualTo("in root");
        assertThatThrownBy(() -> service.downloadFile("missing.pdf", folders)).isInstanceOf(java.io.IOException.class);
    }

    @Test
    void moveControlFolder_renamesTheFolder_whenTheNewOneDoesNotExist() throws Exception {
        FileStorageService service = serviceOn(tempDir);
        Path old = Files.createDirectories(tempDir.resolve("HR-CTRL-MF-5_FY26_Central_2H"));
        Files.writeString(old.resolve("Отчёт_за_май.pdf"), "a");
        Files.writeString(old.resolve("plan.xlsx"), "b");

        assertThat(service.moveControlFolder("HR-CTRL-MF-5/FY26/Central/2H", "KDN")).isEqualTo(2);

        assertThat(old).doesNotExist();
        assertThat(tempDir.resolve("KDN").resolve("Отчёт_за_май.pdf")).hasContent("a");
        assertThat(service.downloadFile("plan.xlsx", "KDN")).asString().isEqualTo("b");
    }

    @Test
    void moveControlFolder_intoAnExistingFolder_keepsBothSameNamedFiles() throws Exception {
        FileStorageService service = serviceOn(tempDir);
        Path old = Files.createDirectories(tempDir.resolve("HR1"));
        Path existing = Files.createDirectories(tempDir.resolve("HR2"));
        Files.writeString(old.resolve("a.pdf"), "old a");
        Files.writeString(old.resolve("b.pdf"), "old b");
        Files.writeString(existing.resolve("a.pdf"), "existing a");

        assertThat(service.moveControlFolder("HR1", "HR2")).isEqualTo(1);

        assertThat(existing.resolve("a.pdf")).hasContent("existing a");
        assertThat(existing.resolve("b.pdf")).hasContent("old b");
        assertThat(old.resolve("a.pdf")).hasContent("old a");
        assertThat(old.resolve("b.pdf")).doesNotExist();
    }

    @Test
    void moveControlFolder_withoutAnOldFolder_orToTheSameFolder_movesNothing() throws Exception {
        FileStorageService service = serviceOn(tempDir);
        assertThat(service.moveControlFolder("HR1", "HR2")).isZero();
        Files.createDirectories(tempDir.resolve("HR_1"));
        Files.writeString(tempDir.resolve("HR_1").resolve("a.pdf"), "a");
        // "HR/1" and "HR_1" share a folder name
        assertThat(service.moveControlFolder("HR/1", "HR_1")).isZero();
        assertThat(tempDir.resolve("HR_1").resolve("a.pdf")).exists();
        assertThatThrownBy(() -> service.moveControlFolder("HR_1", "..")).isInstanceOf(SecurityException.class);
    }

    @Test
    void deleteFile_removesTheFileFromTheFirstFolderThatHasIt_neverFromTheRoot() throws Exception {
        FileStorageService service = serviceOn(tempDir);
        Files.createDirectories(tempDir.resolve("OLD"));
        Files.writeString(tempDir.resolve("OLD").resolve("a.pdf"), "a");
        Files.writeString(tempDir.resolve("root.pdf"), "root");

        service.deleteFile("a.pdf", List.of("NEW", "OLD"));
        service.deleteFile("root.pdf", List.of("NEW", "OLD"));

        assertThat(tempDir.resolve("OLD").resolve("a.pdf")).doesNotExist();
        assertThat(tempDir.resolve("root.pdf")).exists();
    }

    @Test
    void downloadAndDelete_refuseNamesThatLeaveTheFolder() throws Exception {
        Path uploads = Files.createDirectories(tempDir.resolve("uploads"));
        FileStorageService service = serviceOn(uploads);
        Files.createDirectories(uploads.resolve("HR1"));
        Files.createDirectories(uploads.resolve("HR2"));
        Files.writeString(uploads.resolve("HR2").resolve("secret.pdf"), "other control");
        Files.writeString(uploads.resolve("root.pdf"), "upload root");
        Files.writeString(tempDir.resolve("outside.txt"), "outside uploads");

        for (String name : java.util.List.of(
                "../HR2/secret.pdf", "..\\HR2\\secret.pdf", "../../outside.txt", "..\\..\\outside.txt",
                "..", ".", "HR2/secret.pdf", "HR2\\secret.pdf", "./../HR2/secret.pdf",
                "/etc/passwd", "\\HR2\\secret.pdf", "C:\\Windows\\win.ini", "C:secret.pdf", "report.pdf\u0000.txt", " ")) {
            assertThatThrownBy(() -> service.downloadFile(name, "HR1"))
                    .as(name).isInstanceOf(SecurityException.class);
            assertThatThrownBy(() -> service.deleteFile(name, "HR1"))
                    .as(name).isInstanceOf(SecurityException.class);
        }
        // A folder name that resolves outside the upload root is refused as well
        assertThatThrownBy(() -> service.downloadFile("outside.txt", ".."))
                .isInstanceOf(SecurityException.class);

        assertThat(uploads.resolve("HR2").resolve("secret.pdf")).exists();
        assertThat(uploads.resolve("root.pdf")).exists();
        assertThat(tempDir.resolve("outside.txt")).exists();
    }

    @Test
    void uploadRoot_isAbsolute_andTheDefaultIsTheUploadsFolderOfTheStartFolder() throws Exception {
        Path start = Path.of("").toAbsolutePath();
        assertThat(FileStorageService.resolveRoot(null)).isEqualTo(start.resolve("uploads"));
        assertThat(FileStorageService.resolveRoot("  ")).isEqualTo(start.resolve("uploads"));
        assertThat(FileStorageService.resolveRoot("data/files")).isEqualTo(start.resolve("data").resolve("files"));
        assertThat(FileStorageService.resolveRoot(tempDir.toString())).isEqualTo(tempDir.toAbsolutePath());

        // Fixed at start and created when missing
        Path configured = tempDir.resolve("attachments");
        FileStorageService service = serviceOn(configured);
        service.init();
        assertThat(configured).isDirectory();
        Files.writeString(Files.createDirectories(configured.resolve("HR1")).resolve("a.pdf"), "a");
        assertThat(service.downloadFile("a.pdf", "HR1")).asString().isEqualTo("a");
    }

    @Test
    void isPlainFileName_allowsStoredNames() {
        assertThat(FileStorageService.isPlainFileName("Отчёт_о_проверке (1).pdf")).isTrue();
        assertThat(FileStorageService.isPlainFileName("20260101_abcd1234_report..v2.pdf")).isTrue();
        assertThat(FileStorageService.isPlainFileName("%2e%2e")).isTrue();
        assertThat(FileStorageService.isPlainFileName("..")).isFalse();
        assertThat(FileStorageService.isPlainFileName("a/b.pdf")).isFalse();
        assertThat(FileStorageService.isPlainFileName("a\\b.pdf")).isFalse();
    }

    private FileStorageService serviceOn(Path uploadDir) throws Exception {
        FileStorageService service = new FileStorageService();
        Field uploadDirField = FileStorageService.class.getDeclaredField("uploadDir");
        uploadDirField.setAccessible(true);
        uploadDirField.set(service, uploadDir.toString());
        return service;
    }
}
