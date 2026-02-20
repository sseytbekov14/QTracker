package com.kpmg.qtracker.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
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
}
