package gg.moonrise.hawkeye;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.Architectures.LayeredArchitecture;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The layered package structure of the java-development skill. Each layer uses only the layers below
 * it; the plugin's main class in the root package may use any of them. Every main class must sit in a
 * layer, so a new top-level package fails here until it is placed in one.
 */
public class ArchitectureTest {

    private static final String ROOT = HawkeyePlugin.class.getPackageName();

    private static final String[] BACKEND = {ROOT + ".core.repository..", ROOT + ".core.sql..",
            ROOT + ".core.time..", ROOT + ".core.storage..", ROOT + ".core.queue..", ROOT + ".core.scheduler..",
            ROOT + ".core.bus..", ROOT + ".core.diagnostic.."};

    /** Backend value types a service may hold because they carry a backend handle (a database). */
    private static final Set<String> BACKEND_HANDLES = Set.of(
            ROOT + ".core.storage.shard.Shard", ROOT + ".core.storage.shard.StorageState");

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);

    @Test
    public void layersOnlyUseTheLayersBelowThem() {
        architecture().check(CLASSES);
    }

    @Test
    public void everyClassSitsInALayer() {
        architecture().ensureAllClassesAreContainedInArchitecture().check(CLASSES);
    }

    @Test
    public void servicesTakeTheirValuesFromEntities() {
        noClasses().that().resideInAPackage(ROOT + ".core.service..")
                .should().dependOnClassesThat(backendValueType())
                .because("a value that crosses into the service layer belongs in entity")
                .check(CLASSES);
    }

    @Test
    public void groupingPackagesHoldOnlySubpackages() {
        noClasses().should().resideInAnyPackage(ROOT + ".core", ROOT + ".core.service", ROOT + ".core.storage",
                        ROOT + ".core.repository", ROOT + ".core.repository.coreprotect", ROOT + ".core.queue",
                        ROOT + ".config", ROOT + ".util")
                .because("these packages are split by concern; a class goes into one of their subpackages")
                .check(CLASSES);
    }

    private static DescribedPredicate<JavaClass> backendValueType() {
        return DescribedPredicate.describe("are backend records or sealed interfaces", type -> {
            if (!resideInAnyPackage(BACKEND).test(type)) {
                return false;
            }
            JavaClass outer = type;
            while (outer.getEnclosingClass().isPresent()) {
                outer = outer.getEnclosingClass().get();
            }
            if (BACKEND_HANDLES.contains(outer.getName())) {
                return false;
            }
            Class<?> loaded = type.reflect();
            return loaded.isRecord() || loaded.isSealed();
        });
    }

    private static LayeredArchitecture architecture() {
        return layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                .withOptionalLayers(true)
                .layer("Plugin").definedBy(ROOT)
                .layer("Loader").definedBy(ROOT + ".loader..")
                .layer("PlayerFacing").definedBy(ROOT + ".command..", ROOT + ".listener..", ROOT + ".ui..",
                        ROOT + ".type..", ROOT + ".indicator..", ROOT + ".integration..")
                .layer("Service").definedBy(ROOT + ".core.service..")
                .layer("Backend").definedBy(BACKEND)
                .layer("Bottom").definedBy(ROOT + ".entity..", ROOT + ".config..", ROOT + ".util..",
                        ROOT + ".api..")
                .whereLayer("Plugin").mayNotBeAccessedByAnyLayer()
                .whereLayer("Loader").mayNotBeAccessedByAnyLayer()
                .whereLayer("PlayerFacing").mayOnlyBeAccessedByLayers("Plugin")
                .whereLayer("Service").mayOnlyBeAccessedByLayers("Plugin", "PlayerFacing")
                .whereLayer("Backend").mayOnlyBeAccessedByLayers("Plugin", "Service")
                .whereLayer("Bottom").mayOnlyBeAccessedByLayers("Plugin", "Loader", "PlayerFacing", "Service",
                        "Backend");
    }
}
