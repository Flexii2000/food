package com.fherrmann.food.controller;

import com.fherrmann.food.dto.AndroidReleaseInfo;
import com.fherrmann.food.release.AndroidRelease;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Die Android-App zum Herunterladen - fuer die erste Installation im Browser und fuer
 * die Aktualisierung aus der App heraus. Hinter demselben Token wie alles andere.
 */
@RestController
@RequestMapping("/api/app/android")
public class AppController {

    private static final MediaType APK = MediaType.parseMediaType("application/vnd.android.package-archive");

    private final AndroidRelease release;

    public AppController(AndroidRelease release) {
        this.release = release;
    }

    @GetMapping
    public AndroidReleaseInfo latest() {
        return release.latest().orElseThrow(() -> notPublished());
    }

    @GetMapping("/apk")
    public ResponseEntity<Resource> apk() {
        AndroidReleaseInfo info = release.latest().orElseThrow(() -> notPublished());
        return ResponseEntity.ok()
                .contentType(APK)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("healthy-" + info.versionName() + ".apk").build().toString())
                .body(new FileSystemResource(release.apk()));
    }

    private static ResponseStatusException notPublished() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Es ist keine Android-App veröffentlicht.");
    }
}
