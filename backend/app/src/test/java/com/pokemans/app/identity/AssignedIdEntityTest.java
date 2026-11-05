package com.pokemans.app.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.persistence.AssignedIdEntity;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Import;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Saving a new entity always inserts (review finding 41): no SELECT first, and a reused id can't overwrite a row. */
@SpringBootTest(properties = "pokemans.condition.worker.enabled=false")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class AssignedIdEntityTest {

    @Autowired
    private OrgRepository orgs;

    @Test
    void everyEntityKnowsWhenItIsNew() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        var entities = scanner.findCandidateComponents("com.pokemans.app");
        assertThat(entities).hasSizeGreaterThan(30);
        for (var entity : entities) {
            assertThat(AssignedIdEntity.class.isAssignableFrom(Class.forName(entity.getBeanClassName())))
                    .as(entity.getBeanClassName()).isTrue();
        }
    }

    @Test
    void aReusedIdFailsInsteadOfOverwritingTheRow() {
        Org first = orgs.save(new Org("First", OrgType.COLLECTOR));
        assertThat(first.isNew()).isFalse();

        Org second = new Org("Second", OrgType.COLLECTOR);
        ReflectionTestUtils.setField(second, "id", first.getId());

        assertThatThrownBy(() -> orgs.save(second)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(orgs.findById(first.getId()).orElseThrow().getName()).isEqualTo("First");
    }
}
