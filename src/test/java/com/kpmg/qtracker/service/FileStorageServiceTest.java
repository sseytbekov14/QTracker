package com.kpmg.qtracker.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
