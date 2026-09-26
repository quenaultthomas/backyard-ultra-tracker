package fr.backyard.domain;

import fr.backyard.repository.PassageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.asm.SpringAsmInfo;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec increment 1, addendum du 2026-09-26 - CA24 (RG11) et CA25 (RG15), parties [test d'architecture] et [unit].
 * JUnit pur, sans Spring ni base : reflexion sur les classes et analyse du bytecode de production
 * ({@code target/classes}) avec l'ASM embarque par Spring. Le bytecode donne le type exact du receveur
 * de chaque appel, ce qu'une analyse textuelle ne permet pas ({@code Race.setStatus} vs {@code Runner.setStatus}).
 * La partie [persistance] de CA25 est dans {@code fr.backyard.persistence.Increment1AddendumPersistenceTest}.
 */
@Tag("INC-1")
class DomainIntegrityArchitectureTest {

    private static final String RUNNER_OWNER = "fr/backyard/domain/Runner";
    private static final String PASSAGE_OWNER = "fr/backyard/domain/Passage";
    private static final String PASSAGE_REPOSITORY_OWNER = "fr/backyard/repository/PassageRepository";

    private static final Set<String> RUNNER_DNF_SETTERS = Set.of("setStatus", "setDnfReason", "setDnfYard");
    private static final Set<String> PASSAGE_DELETE_METHODS = Set.of(
        "delete", "deleteById", "deleteAll", "deleteAllInBatch", "deleteAllById", "deleteInBatch");
    private static final Set<String> PASSAGE_ALLOWED_PUBLIC_METHODS = Set.of(
        "getId", "getRunner", "getScannedAt", "getYardNumber", "getSource");
    private static final Set<String> PASSAGE_IMMUTABLE_FIELDS = Set.of("runner", "scannedAt", "yardNumber", "source");

    /** Un appel ou une affectation de champ trouve dans le bytecode de production. */
    private record Instruction(String callerClass, String callerMethod, int opcode, String owner, String name) {
        @Override
        public String toString() {
            return callerClass + "#" + callerMethod + " -> " + owner + "." + name;
        }
    }

    private static Path productionClassesRoot() {
        try {
            Path root = Path.of(Runner.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            assertThat(root.resolve("fr/backyard/domain/Runner.class"))
                .as("classes de production introuvables : %s", root)
                .isRegularFile();
            return root;
        } catch (URISyntaxException e) {
            throw new IllegalStateException("emplacement des classes de production illisible", e);
        }
    }

    private static List<Instruction> productionInstructions() {
        Path root = productionClassesRoot();
        List<Instruction> instructions = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            List<Path> classFiles = files.filter(path -> path.toString().endsWith(".class")).toList();
            assertThat(classFiles).as("aucune classe de production analysee").hasSizeGreaterThan(20);
            for (Path classFile : classFiles) {
                collectInstructions(classFile, instructions::add);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("parcours impossible de " + root, e);
        }
        return instructions;
    }

    private static void collectInstructions(Path classFile, Consumer<Instruction> sink) {
        try (InputStream input = Files.newInputStream(classFile)) {
            ClassReader reader = new ClassReader(input);
            String callerClass = reader.getClassName();
            reader.accept(new ClassVisitor(SpringAsmInfo.ASM_VERSION) {
                @Override
                public MethodVisitor visitMethod(int access, String methodName, String descriptor,
                                                 String signature, String[] exceptions) {
                    return new MethodVisitor(SpringAsmInfo.ASM_VERSION) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                            sink.accept(new Instruction(callerClass, methodName, opcode, owner, name));
                        }

                        @Override
                        public void visitFieldInsn(int opcode, String owner, String name, String desc) {
                            sink.accept(new Instruction(callerClass, methodName, opcode, owner, name));
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES);
        } catch (IOException e) {
            throw new UncheckedIOException("lecture impossible de " + classFile, e);
        }
    }

    private static boolean isMethodCall(Instruction instruction) {
        return instruction.opcode() != Opcodes.PUTFIELD && instruction.opcode() != Opcodes.GETFIELD
            && instruction.opcode() != Opcodes.PUTSTATIC && instruction.opcode() != Opcodes.GETSTATIC;
    }

    // ── CA24 — Aucun contournement de la cohérence DNF ──────────────────────

    @Test
    @Tag("INC1-CA24")
    @DisplayName("CA24 - aucune classe de production hors Runner n'appelle Runner.setStatus, setDnfReason ou setDnfYard")
    void ca24_noProductionCallToRunnerDnfSettersOutsideRunner() {
        List<Instruction> instructions = productionInstructions();

        List<Instruction> forbiddenCalls = instructions.stream()
            .filter(DomainIntegrityArchitectureTest::isMethodCall)
            .filter(call -> call.owner().equals(RUNNER_OWNER) && RUNNER_DNF_SETTERS.contains(call.name()))
            .filter(call -> !call.callerClass().equals(RUNNER_OWNER))
            .toList();

        assertThat(forbiddenCalls)
            .as("toute mise en DNF doit passer par Runner.markDnf (RG11)")
            .isEmpty();
    }

    @Test
    @Tag("INC1-CA24")
    @DisplayName("CA24 - controle de l'analyse : les appels a Runner.markDnf sont bien detectes dans la production")
    void ca24_bytecodeAnalysisDetectsRunnerCalls() {
        List<Instruction> instructions = productionInstructions();

        assertThat(instructions)
            .as("l'analyse doit voir les appels a Runner.markDnf, sinon CA24 serait vert par construction")
            .anyMatch(call -> call.owner().equals(RUNNER_OWNER) && call.name().equals("markDnf")
                && !call.callerClass().equals(RUNNER_OWNER));
    }

    // ── CA25 — Immuabilité des passages ─────────────────────────────────────

    @Test
    @Tag("INC1-CA25")
    @DisplayName("CA25 - Passage n'expose aucune methode publique set* ni autre que ses accesseurs et celles d'Object")
    void ca25_passageExposesOnlyAccessors() {
        Set<String> objectMethodNames = Arrays.stream(Object.class.getMethods())
            .map(Method::getName)
            .collect(Collectors.toSet());

        List<String> publicMethodNames = Arrays.stream(Passage.class.getMethods())
            .map(Method::getName)
            .filter(name -> !objectMethodNames.contains(name))
            .toList();

        assertThat(publicMethodNames).noneMatch(name -> name.startsWith("set"));
        assertThat(publicMethodNames).allMatch(PASSAGE_ALLOWED_PUBLIC_METHODS::contains,
            "uniquement getId, getRunner, getScannedAt, getYardNumber, getSource");
    }

    @Test
    @Tag("INC1-CA25")
    @DisplayName("CA25 - runner, scannedAt, yardNumber et source ne sont affectes que par un constructeur de Passage")
    void ca25_passageFieldsAreOnlyAssignedByConstructor() {
        List<Instruction> fieldWrites = productionInstructions().stream()
            .filter(instruction -> instruction.opcode() == Opcodes.PUTFIELD)
            .filter(instruction -> instruction.owner().equals(PASSAGE_OWNER)
                && PASSAGE_IMMUTABLE_FIELDS.contains(instruction.name()))
            .toList();

        assertThat(fieldWrites)
            .as("controle de l'analyse : le constructeur affecte bien les quatre champs")
            .extracting(Instruction::name)
            .containsAll(PASSAGE_IMMUTABLE_FIELDS);
        assertThat(fieldWrites)
            .as("aucune affectation hors constructeur de Passage (RG15)")
            .allMatch(write -> write.callerClass().equals(PASSAGE_OWNER) && write.callerMethod().equals("<init>"));
    }

    @Test
    @Tag("INC1-CA25")
    @DisplayName("CA25 - PassageRepository ne declare aucune methode @Modifying ni requete UPDATE ou DELETE")
    void ca25_passageRepositoryDeclaresNoModifyingQuery() {
        for (Method method : PassageRepository.class.getDeclaredMethods()) {
            assertThat(method.isAnnotationPresent(Modifying.class))
                .as("@Modifying sur PassageRepository.%s", method.getName())
                .isFalse();
            Query query = method.getAnnotation(Query.class);
            if (query != null) {
                String sql = query.value().toUpperCase(Locale.ROOT);
                assertThat(sql)
                    .as("requete de PassageRepository.%s", method.getName())
                    .doesNotContainPattern("\\bUPDATE\\b")
                    .doesNotContainPattern("\\bDELETE\\b");
            }
            assertThat(method.getName())
                .as("methode derivee de suppression declaree sur PassageRepository")
                .doesNotStartWith("delete")
                .doesNotStartWith("remove");
        }
    }

    @Test
    @Tag("INC1-CA25")
    @DisplayName("CA25 - aucune classe de production n'appelle une methode de suppression de PassageRepository")
    void ca25_noProductionCallToPassageRepositoryDeletion() {
        List<Instruction> instructions = productionInstructions();

        assertThat(instructions)
            .as("controle de l'analyse : les appels a PassageRepository sont bien detectes")
            .anyMatch(call -> call.owner().equals(PASSAGE_REPOSITORY_OWNER) && isMethodCall(call));
        assertThat(instructions.stream()
            .filter(DomainIntegrityArchitectureTest::isMethodCall)
            .filter(call -> call.owner().equals(PASSAGE_REPOSITORY_OWNER)
                && PASSAGE_DELETE_METHODS.contains(call.name()))
            .toList())
            .as("suppression de passage depuis la production (RG15)")
            .isEmpty();
    }
}
