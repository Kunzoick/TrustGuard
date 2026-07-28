package com.trustguard.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Enforces Rule 2.2: "shared module imports nothing internal." This is a
 * build failure per Rule 17.1 — never a code review comment. As more
 * modules are added in later batches (tenant, signals, risk, audit,
 * etc.), this test continues to guarantee com.trustguard.shared never
 * grows a dependency on any of them, since shared sits beneath every
 * other module in the permitted dependency graph (Rule 2.2).
 */
@AnalyzeClasses(packages = "com.trustguard")
class SharedModuleArchitectureTest {

    /**
     * Written as "anything under com.trustguard that is not
     * com.trustguard.shared itself" rather than an explicit list of
     * module names, so this rule automatically covers every module added
     * in future batches (tenant, signals, risk, ratelimit, audit,
     * heuristics, feedback, sdk, infrastructure, api) without needing to
     * be updated each time one is created.
     */
    @ArchTest
    static final ArchRule shared_module_imports_nothing_internal = noClasses()
            .that().resideInAPackage("com.trustguard.shared..")
            .should().dependOnClassesThat(
                    resideInAPackage("com.trustguard..")
                            .and(not(resideInAPackage("com.trustguard.shared..")))
            )
            .because("Rule 2.2: the shared module must import nothing internal - "
                    + "it sits beneath every other module in the permitted dependency graph, "
                    + "and any internal import here would let a change elsewhere ripple "
                    + "into the one module every other module depends on.");

    /**
     * Companion check beyond the letter of Acceptance Criterion #6: the
     * Batch Brief's implementation notes state "No Spring annotations
     * anywhere in this batch - shared module is pure Java." This is not
     * explicitly required by AC #6 (which only asks about internal
     * imports), but it directly enforces that same implementation note,
     * so it is included here rather than left unverified.
     */
    @Test
    void shared_module_uses_no_spring_annotations() {
        JavaClasses importedClasses = new ClassFileImporter().importPackages("com.trustguard.shared");

        for (JavaClass javaClass : importedClasses) {
            boolean hasSpringAnnotation = javaClass.getAnnotations().stream()
                    .anyMatch(annotation -> annotation.getRawType().getPackageName().startsWith("org.springframework"));

            if (hasSpringAnnotation) {
                throw new AssertionError(
                        "Rule violation: " + javaClass.getFullName()
                                + " carries a Spring annotation. The shared module must be pure Java "
                                + "per the B-002 batch brief's implementation notes."
                );
            }
        }
    }
}