package com.dtech.kitecon.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

@RestController
public class ImageController {

    @GetMapping("/images")
    public List<String> getImages(@RequestParam String directory) throws IOException {
        List<String> imageFiles = new ArrayList<>();
        Path imageDir = Paths.get(directory);

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(imageDir, "*.jpg")) {
            for (Path file : stream) {
                imageFiles.add(file.getFileName().toString());
            }
        } catch (IOException e) {
            throw new IOException("Unable to read image directory", e);
        }

        return imageFiles;
    }

    @GetMapping("/images/view")
    public Resource getImage(@RequestParam String directory, @RequestParam String filename) throws IOException {
        Path file = Paths.get(directory).resolve(filename);
        Resource resource = new UrlResource(file.toUri());

        if (!resource.exists() || !resource.isReadable()) {
            throw new IOException("Could not read file: " + filename);
        }

        return resource;
    }
}
