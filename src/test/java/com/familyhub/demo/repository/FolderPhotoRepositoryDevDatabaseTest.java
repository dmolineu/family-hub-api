package com.familyhub.demo.repository;

import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FolderPhoto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The slideshow's random sample on the in-memory H2 database the dev profile (and so
 * the browser tests' backend) runs on; production's Postgres is covered by the
 * integration tests. Native queries are where the two differ.
 */
@DataJpaTest(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@ActiveProfiles("test")
class FolderPhotoRepositoryDevDatabaseTest {

    @Autowired
    FolderPhotoRepository repository;

    @Autowired
    TestEntityManager em;

    @Test
    void samplesAFamilysFolderPhotosOnTheDevDatabase() {
        Family family = new Family();
        family.setName("Dev Family");
        family.setUsername("dev_sample");
        family.setPasswordHash("$2a$10$dummyhashfortesting");
        em.persist(family);
        List<String> ids = List.of("a.jpg", "b.jpg", "c.heic").stream().map(path -> {
            FolderPhoto photo = new FolderPhoto();
            photo.setFamily(family);
            photo.setRelativePath(path);
            photo.setSizeBytes(1);
            photo.setModifiedAt(Instant.parse("2026-10-27T12:00:00Z"));
            return em.persistFlushFind(photo).getId().toString();
        }).toList();

        assertThat(repository.sampleIds(family.getId(), 10))
                .extracting(Object::toString)
                .containsExactlyInAnyOrderElementsOf(ids);
        assertThat(repository.sampleIds(family.getId(), 2)).hasSize(2);
    }
}
