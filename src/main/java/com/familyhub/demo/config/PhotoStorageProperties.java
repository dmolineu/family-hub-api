package com.familyhub.demo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Where photos may be stored. A family chooses its folder in the app, but only under
 * one of these roots, which are the folders mounted into the container for photos
 * (a named volume, a NAS share), so a typed path can never point the app elsewhere.
 */
@ConfigurationProperties(prefix = "photos")
@Configuration
@Data
public class PhotoStorageProperties {
    private List<String> roots = List.of("/photos");
    /** A family that has not chosen a folder keeps its photos here. */
    private String defaultFolder = "/photos/library";
}
