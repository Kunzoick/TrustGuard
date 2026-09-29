package com.trustguard.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

class TenantContextArchTest {

    private final JavaClasses importedClasses =
            new ClassFileImporter().importPackages("com.trustguard");

    private static final ArchCondition<JavaMethod> NOT_USE_BANNED_PROPAGATION =
            new ArchCondition<>("not use SUPPORTS or NOT_SUPPORTED propagation") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    Transactional annotation = method.getAnnotationOfType(Transactional.class);
                    Propagation propagation = annotation.propagation();
                    boolean banned = propagation == Propagation.SUPPORTS
                            || propagation == Propagation.NOT_SUPPORTED;
                    if (banned) {
                        events.add(SimpleConditionEvent.violated(method, String.format(
                                "Method %s uses banned propagation %s (Rule 4.1, Rule 17.11)",
                                method.getFullName(), propagation)));
                    }
                }
            };

    @Test
    void transactional_methods_in_services_must_not_use_supports_or_not_supported() {
        ArchRule rule = methods()
                .that().areAnnotatedWith(Transactional.class)
                .and().areDeclaredInClassesThat().resideInAPackage("..service..")
                .should(NOT_USE_BANNED_PROPAGATION).allowEmptyShould(true);
        rule.check(importedClasses);
    }

    /**
     * Rewritten in Code v2 (CF-002) against ArchUnit's core domain
     * model (JavaClass.getConstructorCallsFromSelf()) rather than the
     * fluent callConstructor() DSL shorthand. The core domain API has
     * been stable across ArchUnit major versions in a way the fluent
     * convenience methods have not — this avoids depending on an
     * unverified overload shape for ArchUnit 1.3.0 (pinned in
     * DEPENDENCY_STANDARDS.md).
     */
    private static final ArchCondition<JavaClass> NOT_CONSTRUCT_TENANT_CONTEXT =
            new ArchCondition<>("not construct TenantContext") {
                @Override
                public void check(JavaClass javaClass, ConditionEvents events) {
                    for (JavaConstructorCall call : javaClass.getConstructorCallsFromSelf()) {
                        JavaClass targetOwner = call.getTarget().getOwner();
                        if (targetOwner.isEquivalentTo(
                                com.trustguard.tenant.context.TenantContext.class)) {
                            events.add(SimpleConditionEvent.violated(javaClass, String.format(
                                    "Class %s constructs TenantContext directly — construction "
                                            + "must be confined to the auth filter package (Rule 4.3). "
                                            + "Call site: %s",
                                    javaClass.getFullName(), call.getDescription())));
                        }
                    }
                }
            };

    @Test
    void tenant_context_must_not_be_constructed_in_service_or_repository_packages() {
        ArchRule rule = classes()
                .that().resideInAPackage("..service..")
                .or().resideInAPackage("..repository..")
                .should(NOT_CONSTRUCT_TENANT_CONTEXT);
        rule.check(importedClasses);
    }
}