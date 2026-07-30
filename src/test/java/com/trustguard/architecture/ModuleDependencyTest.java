package com.trustguard.architecture;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Enforces the full permitted dependency graph from Rule 2.2. Written
 * completely now rather than as an empty stub, since the graph itself
 * is already fully specified in the Contract and does not change per
 * batch — only which modules currently have classes in them changes.
 *
 * IMPORTANT — read before assuming a passing build means something it
 * does not: as of B-003, only com.trustguard.shared (B-002) and
 * com.trustguard.infrastructure / com.trustguard (this batch) contain
 * any classes. Every rule below referencing tenant, signals, risk,
 * ratelimit, audit, heuristics, feedback, or sdk currently holds
 * VACUOUSLY — there is nothing in those packages yet to violate the
 * rule. A green build here does NOT yet mean those boundaries have
 * been tested; it means they have not yet been given the chance to be
 * violated. Each rule becomes a real, falsifiable test the moment its
 * corresponding module's first class is created in a later batch — at
 * that point this file needs no further edits for that rule to start
 * enforcing.
 */
@AnalyzeClasses(packages = "com.trustguard")
class ModuleDependencyTest {

    @ArchTest
    static final ArchRule shared_depends_on_nothing_internal = noClasses()
            .that().resideInAPackage("com.trustguard.shared..")
            .should().dependOnClassesThat(resideInAPackage("com.trustguard..")
                    .and(com.tngtech.archunit.base.DescribedPredicate.not(resideInAPackage("com.trustguard.shared.."))))
            .because("Rule 2.2: shared -> nothing internal");

    @ArchTest
    static final ArchRule infrastructure_depends_on_nothing_internal = noClasses()
            .that().resideInAPackage("com.trustguard.infrastructure..")
            .should().dependOnClassesThat(resideInAPackage("com.trustguard..")
                    .and(com.tngtech.archunit.base.DescribedPredicate.not(resideInAPackage("com.trustguard.infrastructure.."))
                            .and(com.tngtech.archunit.base.DescribedPredicate.not(resideInAPackage("com.trustguard.shared..")))))
            .because("Rule 2.2: infrastructure -> nothing internal");

    @ArchTest
    static final ArchRule sdk_depends_on_nothing_internal = noClasses()
            .that().resideInAPackage("com.trustguard.sdk..")
            .should().dependOnClassesThat(resideInAPackage("com.trustguard..")
                    .and(com.tngtech.archunit.base.DescribedPredicate.not(resideInAPackage("com.trustguard.sdk.."))))
            .because("Rule 2.2: sdk -> nothing internal. VACUOUS until B-006 creates the sdk module.");

    @ArchTest
    static final ArchRule tenant_depends_on_nothing_internal = noClasses()
            .that().resideInAPackage("com.trustguard.tenant..")
            .should().dependOnClassesThat(resideInAPackage("com.trustguard..")
                    .and(com.tngtech.archunit.base.DescribedPredicate.not(resideInAPackage("com.trustguard.tenant.."))))
            .because("Rule 2.2: tenant -> nothing internal. VACUOUS until B-005 creates the tenant module.");

    @ArchTest
    static final ArchRule signals_only_depends_on_tenant_and_shared = noClasses()
            .that().resideInAPackage("com.trustguard.signals..")
            .should().dependOnClassesThat(resideInAPackage("com.trustguard..")
                    .and(com.tngtech.archunit.base.DescribedPredicate.not(resideInAPackage("com.trustguard.signals.."))
                            .and(com.tngtech.archunit.base.DescribedPredicate.not(resideInAPackage("com.trustguard.tenant.."))
                                    .and(com.tngtech.archunit.base.DescribedPredicate.not(resideInAPackage("com.trustguard.shared.."))))))
            .because("Rule 2.2: signals -> tenant, shared only. VACUOUS until B-009 creates the signals module.");

    @ArchTest
    static final ArchRule risk_only_depends_on_permitted_modules = noClasses()
            .that().resideInAPackage("com.trustguard.risk..")
            .should().dependOnClassesThat(resideInAPackage("com.trustguard..")
                    .and(com.tngtech.archunit.base.DescribedPredicate.not(resideInAPackage("com.trustguard.risk.."))
                            .and(com.tngtech.archunit.base.DescribedPredicate.not(resideInAPackage("com.trustguard.signals.."))
                                    .and(com.tngtech.archunit.base.DescribedPredicate.not(resideInAPackage("com.trustguard.tenant.."))
                                            .and(com.tngtech.archunit.base.DescribedPredicate.not(resideInAPackage("com.trustguard.heuristics.."))
                                                    .and(com.tngtech.archunit.base.DescribedPredicate.not(resideInAPackage("com.trustguard.shared.."))))))))
            .because("Rule 2.2: risk -> signals, tenant, heuristics, shared only. VACUOUS until B-013 creates the risk module.");

    @ArchTest
    static final ArchRule ratelimit_only_depends_on_permitted_modules = noClasses()
            .that().resideInAPackage("com.trustguard.ratelimit..")
            .should().dependOnClassesThat(resideInAPackage("com.trustguard..")
                    .and(com.tngtech.archunit.base.DescribedPredicate.not(resideInAPackage("com.trustguard.ratelimit.."))
                            .and(com.tngtech.archunit.base.DescribedPredicate.not(resideInAPackage("com.trustguard.risk.."))
                                    .and(com.tngtech.archunit.base.DescribedPredicate.not(resideInAPackage("com.trustguard.tenant.."))
                                            .and(com.tngtech.archunit.base.DescribedPredicate.not(resideInAPackage("com.trustguard.shared..")))))))
            .because("Rule 2.2: rate-limit -> risk, tenant, shared only. VACUOUS until B-014 creates the ratelimit module.");

    @ArchTest
    static final ArchRule heuristics_only_depends_on_permitted_modules = noClasses()
            .that().resideInAPackage("com.trustguard.heuristics..")
            .should().dependOnClassesThat(resideInAPackage("com.trustguard..")
                    .and(com.tngtech.archunit.base.DescribedPredicate.not(resideInAPackage("com.trustguard.heuristics.."))
                            .and(com.tngtech.archunit.base.DescribedPredicate.not(resideInAPackage("com.trustguard.signals.."))
                                    .and(com.tngtech.archunit.base.DescribedPredicate.not(resideInAPackage("com.trustguard.tenant.."))
                                            .and(com.tngtech.archunit.base.DescribedPredicate.not(resideInAPackage("com.trustguard.shared..")))))))
            .because("Rule 2.2: heuristics -> signals, tenant, shared only. VACUOUS until B-016 creates the heuristics module.");

    @ArchTest
    static final ArchRule feedback_only_depends_on_permitted_modules = noClasses()
            .that().resideInAPackage("com.trustguard.feedback..")
            .should().dependOnClassesThat(resideInAPackage("com.trustguard..")
                    .and(com.tngtech.archunit.base.DescribedPredicate.not(resideInAPackage("com.trustguard.feedback.."))
                            .and(com.tngtech.archunit.base.DescribedPredicate.not(resideInAPackage("com.trustguard.risk.."))
                                    .and(com.tngtech.archunit.base.DescribedPredicate.not(resideInAPackage("com.trustguard.audit.."))
                                            .and(com.tngtech.archunit.base.DescribedPredicate.not(resideInAPackage("com.trustguard.tenant.."))
                                                    .and(com.tngtech.archunit.base.DescribedPredicate.not(resideInAPackage("com.trustguard.shared.."))))))))
            .because("Rule 2.2: feedback -> risk, audit, tenant, shared only. VACUOUS until B-019 creates the feedback module.");

    /**
     * The single most important rule in this file, per Rule 2.2's own
     * emphasis: "The audit module must never import from risk or
     * signals... If audit imports from risk, business logic changes can
     * alter what gets audited. That is a corruption vector for
     * compliance records." Written now so this cannot possibly be
     * forgotten when B-018 creates the audit module.
     */
    @ArchTest
    static final ArchRule audit_depends_on_nothing_but_shared = noClasses()
            .that().resideInAPackage("com.trustguard.audit..")
            .should().dependOnClassesThat(resideInAPackage("com.trustguard..")
                    .and(com.tngtech.archunit.base.DescribedPredicate.not(resideInAPackage("com.trustguard.audit.."))
                            .and(com.tngtech.archunit.base.DescribedPredicate.not(resideInAPackage("com.trustguard.shared.."))))
            )
            .because("Rule 2.2: audit -> shared only, via the internal event bus, "
                    + "NEVER a direct import from risk or signals. VACUOUS until B-018 creates the audit module — "
                    + "but this is the one rule in this file that matters most once it stops being vacuous.");
}