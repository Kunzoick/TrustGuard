package com.trustguard.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * B-007 rules over production classes only (Rule 16.7: no shared code, no shared context; RULINGS 17, 19).
 */
class AdminModuleArchitectureTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.trustguard");

    @Test
    void admin_does_not_depend_on_sdk_or_tenant() {
        noClasses().that().resideInAPackage("com.trustguard.api.admin..")
                .should().dependOnClassesThat().resideInAnyPackage("com.trustguard.sdk..", "com.trustguard.tenant..")
                .because("Rule 16.7: admin and tenant security never share code or context")
                .check(PRODUCTION);
    }

    @Test
    void sdk_and_tenant_do_not_depend_on_admin() {
        noClasses().that().resideInAnyPackage("com.trustguard.sdk..", "com.trustguard.tenant..")
                .should().dependOnClassesThat().resideInAPackage("com.trustguard.api.admin..")
                .because("Rule 16.7: admin and tenant security never share code or context")
                .check(PRODUCTION);
    }

    @Test
    void infrastructure_does_not_depend_on_admin() {
        noClasses().that().resideInAPackage("com.trustguard.infrastructure..")
                .should().dependOnClassesThat().resideInAPackage("com.trustguard.api..")
                .because("RULING 17: there is no infrastructure -> api dependency")
                .check(PRODUCTION);
    }

    @Test
    void admin_code_never_uses_transactional() {
        noClasses().that().resideInAPackage("com.trustguard.api.admin..")
                .should().beAnnotatedWith(Transactional.class)
                .because("RULING 19: TenantRlsAspect throws without a TenantContext; use TransactionTemplate")
                .check(PRODUCTION);
        noMethods().that().areDeclaredInClassesThat().resideInAPackage("com.trustguard.api.admin..")
                .should().beAnnotatedWith(Transactional.class)
                .because("RULING 19: TenantRlsAspect throws without a TenantContext; use TransactionTemplate")
                .check(PRODUCTION);
    }
}