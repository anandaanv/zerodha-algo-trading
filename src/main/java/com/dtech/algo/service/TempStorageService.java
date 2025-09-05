package com.dtech.algo.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
@Slf4j
public class TempStorageService {

  @Value("${sr.collect.temp.dir:}")
  private String srTempDir;

  @Value("${charts.temp.directory:/tmp/charts/temp}")
  private String chartsTempDir;

  /**
   * Resolve a writable temp directory for SR collection and ensure it exists.
   */
  public String getTempDir() {
    String dir = (srTempDir != null && !srTempDir.isBlank()) ? srTempDir : chartsTempDir;
    try {
      Files.createDirectories(Path.of(dir));
    } catch (Exception e) {
      log.warn("Failed to create temp dir {}: {}. Falling back to system temp.", dir, e.getMessage());
      dir = System.getProperty("java.io.tmpdir");
    }
    return dir;
  }

  /**
   * Create an empty temp file in the resolved temp directory.
   */
  public File createTempFile(String prefix, String suffix) {
    try {
      Path p = Files.createTempFile(Path.of(getTempDir()), prefix, suffix);
      return p.toFile();
    } catch (Exception e) {
      throw new RuntimeException("Unable to create temp file: " + e.getMessage(), e);
    }
  }

  /**
   * Write bytes to a temp file and return the file reference.
   */
  public File writeBytesToTempFile(byte[] bytes, String prefix, String suffix) {
    File f = createTempFile(prefix, suffix);
    try (FileOutputStream fos = new FileOutputStream(f)) {
      fos.write(bytes);
    } catch (Exception e) {
      throw new RuntimeException("Unable to write temp file: " + e.getMessage(), e);
    }
    return f;
  }
}
