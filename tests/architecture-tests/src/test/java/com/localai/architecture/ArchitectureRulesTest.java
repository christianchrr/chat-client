package com.localai.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureRulesTest {

    private static final JavaClasses AGENT_CORE = new ClassFileImporter()
            .importPackages("com.localai.agentcore");

    @Test
    void agentCoreMustNotDependOnSpringJavaFxSqliteOrOllama() {
        noClasses()
                .that().resideInAPackage("com.localai.agentcore..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "org.openjfx..",
                        "org.sqlite..",
                        "com.ollama..")
                .check(AGENT_CORE);
    }
}
