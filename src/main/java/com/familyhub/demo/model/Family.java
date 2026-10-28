package com.familyhub.demo.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Entity
@Getter
@Setter
public class Family implements UserDetails {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotBlank
    @Column(nullable = false)
    private String name;

    @NotBlank
    @Column(unique = true, nullable = false)
    private String username;

    /** Where the family's photos live; null for the server's default folder. */
    @JsonIgnore
    @Column(name = "photo_folder", length = 500)
    private String photoFolder;

    /** A folder of existing photos shown in the slideshow too, read-only; null for none. */
    @JsonIgnore
    @Column(name = "photo_source_folder", length = 500)
    private String photoSourceFolder;

    @JsonIgnore
    @Column(nullable = false)
    private String passwordHash;

    /**
     * Bumped on every password change. Sign-in tokens carry the version they were
     * issued under, and a token from an older version is refused, which signs out
     * every device that used the old password.
     */
    @JsonIgnore
    @Column(name = "password_version", nullable = false)
    private int passwordVersion;

    @Column(nullable = false, length = 100)
    private String timezone = "America/Los_Angeles";

    /** Geocoded display name ("San Diego, California"); null until set in settings. */
    @Column(name = "weather_location", length = 120)
    private String weatherLocation;

    private Double latitude;

    private Double longitude;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "family", orphanRemoval = true, cascade = CascadeType.ALL)
    private List<FamilyMember> familyMembers;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public @Nullable String getPassword() {
        return this.passwordHash;
    }
}
