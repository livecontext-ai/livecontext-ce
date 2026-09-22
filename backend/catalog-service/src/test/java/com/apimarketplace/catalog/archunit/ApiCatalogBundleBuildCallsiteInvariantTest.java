package com.apimarketplace.catalog.archunit;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The API-catalog bundle build must never MATERIALISE its payload.
 *
 * <p><b>The incident this guards.</b> {@code ApiCatalogBundleService} used to
 * call {@code ApiCatalogBundlePayload.canonicalBytes(...)} and then
 * {@code gzip(byte[])}. Both hold the whole payload: the full-catalog JSON is
 * hundreds of megabytes, so the build needed the row tree, Jackson's output
 * segments, the final array copy AND a second copy to compress, all resident at
 * once. Once the catalog passed ~19k endpoints that exceeded the cloud catalog
 * pod's 896 MB heap and every {@code POST /api/catalog/bundles} answered HTTP
 * 500 from an {@code OutOfMemoryError}. Nothing surfaced that: the controller
 * does not catch it, so an admin saw only "An unexpected error occurred", and
 * the whole CE fleet silently stopped receiving API-catalog updates for as long
 * as it lasted.
 *
 * <p><b>Why a callsite rule and not a test on the size.</b> The heap failure
 * scales with the catalog, so no fixture reproduces it at a size CI would
 * tolerate, and a heap-bounded test pins a number that stops meaning anything
 * the moment the pod is resized. Measured once, off-CI, on a synthetic catalog
 * of 700 APIs / 32,900 endpoints (66 MiB payload): the materialising form threw
 * {@code OutOfMemoryError} at -Xmx192m, -Xmx128m and -Xmx64m, while the
 * streaming form completed at all three - including a heap SMALLER than the
 * payload.
 *
 * <p>What this rule pins is the half of that which is structural: which method
 * the build calls. The other half - that the method it calls is actually
 * incremental - is pinned by {@code
 * ApiCatalogBundlePayloadTest#writeCanonicalDoesNotMaterialiseThePayload},
 * because a materialising {@code writeCanonical} would satisfy this rule, the
 * golden hash and the round trip all at once while bringing the OOM straight
 * back.
 *
 * <p>The {@code byte[]} forms are deliberately still public: tests use them, and
 * so may a caller that already knows its payload is small. This rule only says
 * the whole-catalog build is not such a caller.
 *
 * <p>Sibling of {@code orchestrator}'s {@code JsonbWritesCallsiteInvariantTest}:
 * same shape, a rule about which call a specific class may make, because the
 * wrong one fails silently and late.
 */
@DisplayName("API catalog bundle build - callsite invariant (must stream, never materialise)")
class ApiCatalogBundleBuildCallsiteInvariantTest {

    private static final String PAYLOAD = "com.apimarketplace.catalog.bundle.ApiCatalogBundlePayload";

    private static final String SERVICE = "com.apimarketplace.catalog.bundle.ApiCatalogBundleService";
    private static final String CHUNK_READER =
            "com.apimarketplace.catalog.bundle.ApiCatalogBundleChunkReader";
    private static final String ENTITY = "com.apimarketplace.catalog.domain.ApiCatalogBundleEntity";
    private static final String REPOSITORY =
            "com.apimarketplace.catalog.repository.ApiCatalogBundleRepository";

    /** The payload-holding entry points the whole-catalog build must not reach for. */
    private static final List<String> MATERIALISING_METHODS = List.of("canonicalBytes", "gzip");

    /** The methods that answer a download, plus any lambda synthesised inside them. */
    private static final List<String> SERVING_METHODS =
            List.of("getActiveRawBundle", "getRawBundleByVersion", "toRawBundle");

    /**
     * Calls that put the whole payload back in the serving path. The two finders
     * return the ENTITY, so the payload column comes with it whatever the caller
     * then reads; the getter hands over the array directly.
     */
    private static final List<String> PAYLOAD_BEARING_CALLS =
            List.of("findFirstByActiveTrue", "findByVersion", "getPayloadGz");

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            // The whole service, not just the bundle package: the payload-bearing
            // finder is reachable from anywhere, and scoping the importer to one
            // package is what let a boot-time existence check on it go unnoticed.
            .importPackages("com.apimarketplace.catalog");

    @Test
    @DisplayName("ApiCatalogBundleService never calls canonicalBytes/gzip - it streams via "
            + "writeCanonical, or the build OOMs on the real catalog again")
    void theBuildNeverMaterialisesThePayload() {
        List<String> offenders = classes.stream()
                .filter(c -> c.getName().equals(
                        "com.apimarketplace.catalog.bundle.ApiCatalogBundleService"))
                .flatMap(c -> c.getMethodCallsFromSelf().stream())
                .filter(call -> call.getTargetOwner().getName().equals(PAYLOAD))
                .filter(call -> MATERIALISING_METHODS.contains(call.getTarget().getName()))
                .map(call -> call.getOriginOwner().getSimpleName() + "."
                        + call.getOrigin().getName() + " -> "
                        + call.getTarget().getName())
                .sorted()
                .toList();

        assertThat(offenders)
                .as("These callsites hold the whole catalog payload in memory. Use "
                        + "ApiCatalogBundlePayload.writeCanonical(OutputStream, ...) and serialise "
                        + "straight into the GZIP stream instead")
                .isEmpty();
    }

    @Test
    @DisplayName("serving a bundle never loads the payload-bearing entity - the download must stay "
            + "sliced, or the 24 MB humongous allocation per request comes straight back")
    void servingNeverLoadsTheWholePayload() {
        // Serving reads a payload-free projection and then pulls the bytes in
        // slices. Both of the entity-returning finders would undo that in a way
        // nothing observes: the response is byte-identical, the tests stay green,
        // and only the pod's memory graph changes - which is exactly how the
        // original defect survived a full suite.
        //
        // Method REFERENCES count. `Optional.map(ApiCatalogBundleEntity::getPayloadGz)`
        // compiles to invokedynamic and is absent from getMethodCallsFromSelf, so
        // a rule reading only direct calls waves it through - demonstrated.
        List<String> offenders = classes.stream()
                .filter(c -> c.getName().equals(SERVICE))
                .flatMap(c -> Stream.concat(
                        c.getMethodCallsFromSelf().stream(), c.getMethodReferencesFromSelf().stream()))
                .filter(call -> isServingPath(call.getOrigin().getName()))
                .filter(call -> {
                    String owner = call.getTargetOwner().getName();
                    return (owner.equals(ENTITY) || owner.equals(REPOSITORY))
                            && PAYLOAD_BEARING_CALLS.contains(call.getTarget().getName());
                })
                .map(call -> call.getOrigin().getName() + " -> " + call.getTarget().getName())
                .sorted()
                .toList();

        assertThat(offenders)
                .as("These callsites bring the payload column back into a download. Read the "
                        + "envelope through findActiveServingView/findServingViewByVersion and the "
                        + "bytes through ApiCatalogBundleChunkReader instead")
                .isEmpty();
    }

    @Test
    @DisplayName("the serving rule is watching real methods - renaming toRawBundle away would make "
            + "it pass while guarding nothing")
    void theServingRuleActuallyObservesTheServingPath() {
        // SERVING_METHODS is a hardcoded name list and toRawBundle is private, so
        // the rule above holds every payload-bearing callsite behind a name a
        // refactor can silently change.
        Set<String> observed = classes.stream()
                .filter(c -> c.getName().equals(SERVICE))
                .flatMap(c -> c.getMethodCallsFromSelf().stream())
                .map(call -> call.getOrigin().getName())
                .map(ApiCatalogBundleBuildCallsiteInvariantTest::enclosingMethodOf)
                .collect(Collectors.toSet());

        assertThat(observed)
                .as("every method the serving rule names must still exist and still make calls")
                .containsAll(SERVING_METHODS);
    }

    /**
     * Nobody at all may call the active-row entity finder, the service included.
     *
     * <p>The previous shape of this rule excluded the service, on the grounds that
     * the serving rule above already covered it. It does not: that one is keyed to
     * a list of three method NAMES, so a newly ADDED method on the service was
     * free to call the finder and every rule here still passed - demonstrated.
     *
     * <p>What this deliberately does NOT cover: {@code findByVersion} also returns
     * the entity, but {@code ApiCatalogBundleApplier} has three legitimate uses of
     * it, so banning it outright would need an allow-list that goes stale. The
     * serving rule bans it on the serving path, which is where it costs memory per
     * request. Do not read this rule as a global ban on reaching the payload.
     */
    @Test
    @DisplayName("nobody calls the active-row entity finder - an existence check that materialises "
            + "24 MB is the same defect wearing a different hat")
    void nobodyLoadsTheWholePayloadJustToLookAtIt() {
        List<String> offenders = classes.stream()
                .flatMap(c -> Stream.concat(
                        c.getMethodCallsFromSelf().stream(), c.getMethodReferencesFromSelf().stream()))
                .filter(call -> call.getTargetOwner().getName().equals(REPOSITORY)
                        && "findFirstByActiveTrue".equals(call.getTarget().getName()))
                .map(call -> call.getOriginOwner().getSimpleName() + "." + call.getOrigin().getName())
                .sorted()
                .toList();

        assertThat(offenders)
                .as("findFirstByActiveTrue returns the entity, so the ~24 MB payload column comes "
                        + "with it even when the caller only wants to know whether a row exists. "
                        + "Use findActiveMetadata() for that")
                .isEmpty();
    }

    /** True for a serving method, or for a lambda the compiler put inside one. */
    private static boolean isServingPath(String methodName) {
        return SERVING_METHODS.contains(enclosingMethodOf(methodName));
    }

    /**
     * The method a callsite really belongs to.
     *
     * <p>ArchUnit already attributes an access inside a lambda to its enclosing
     * method, so this normally returns its argument unchanged. It is kept for the
     * case where it does not: a synthetic {@code lambda$toRawBundle$0} would
     * match no serving method and slip past the rule in silence, which is the
     * failure mode worth three lines.
     */
    private static String enclosingMethodOf(String methodName) {
        if (!methodName.startsWith("lambda$")) {
            return methodName;
        }
        String owner = methodName.substring("lambda$".length());
        int suffix = owner.lastIndexOf((int) '$');
        return suffix > 0 ? owner.substring(0, suffix) : owner;
    }

    private static boolean isTransactional(com.tngtech.archunit.core.domain.properties.CanBeAnnotated target) {
        // Meta-annotated too: a project-local alias annotated @Transactional is
        // the same thing to Spring, and isAnnotatedWith alone would not see it.
        return target.isAnnotatedWith(org.springframework.transaction.annotation.Transactional.class)
                || target.isAnnotatedWith(jakarta.transaction.Transactional.class)
                || target.isMetaAnnotatedWith(org.springframework.transaction.annotation.Transactional.class)
                || target.isMetaAnnotatedWith(jakarta.transaction.Transactional.class);
    }

    @Test
    @DisplayName("the sliced reader carries no transaction - a REQUIRES_NEW here took a SECOND "
            + "connection per download and could fill the pool under a fleet polling in phase")
    void theSlicedReaderOpensNoTransactionOfItsOwn() {
        // This was the shape that shipped first: @Transactional(REQUIRES_NEW) on
        // payloadLength suspended the service's read-only transaction and took a
        // second connection for the probe, so every download held two at once.
        // Nothing observable changes when it comes back - the bytes are identical
        // and every test stays green - until the pool runs out under load, which
        // is why the rule is structural rather than a test on behaviour.
        // The class level counts too: Spring applies it to every public method,
        // so a class-level annotation is the same regression with none of the
        // per-method ones - demonstrated against an earlier version of this rule,
        // which passed.
        List<String> annotated = classes.stream()
                .filter(c -> c.getName().equals(CHUNK_READER))
                .flatMap(c -> Stream.concat(
                        isTransactional(c) ? Stream.of(c.getName() + " (class level)") : Stream.empty(),
                        c.getMethods().stream().filter(ApiCatalogBundleBuildCallsiteInvariantTest::isTransactional)
                                .map(m -> m.getFullName())))
                .sorted()
                .toList();

        assertThat(annotated)
                .as("A slice must join whatever the caller has open, or run on its own. Opening a "
                        + "transaction here takes an extra connection for the whole of it")
                .isEmpty();
        assertThat(classes.stream().anyMatch(c -> c.getName().equals(CHUNK_READER)))
                .as("the rule must not pass because the class was renamed away")
                .isTrue();
    }

    @Test
    @DisplayName("the streaming entry point still exists and is the one the service uses - a rule "
            + "that passes because nothing calls anything would guard nothing")
    void theServiceDoesCallTheStreamingForm() {
        // Without this, deleting the build entirely would satisfy the rule above.
        boolean streams = classes.stream()
                .filter(c -> c.getName().equals(
                        "com.apimarketplace.catalog.bundle.ApiCatalogBundleService"))
                .flatMap(c -> c.getMethodCallsFromSelf().stream())
                .anyMatch(call -> call.getTargetOwner().getName().equals(PAYLOAD)
                        && call.getTarget().getName().equals("writeCanonical"));

        assertThat(streams)
                .as("ApiCatalogBundleService must build its payload through "
                        + "ApiCatalogBundlePayload.writeCanonical")
                .isTrue();
    }
}
