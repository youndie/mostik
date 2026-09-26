package io.github.youndie.keel

import io.github.youndie.kore.config.ConfigKey
import io.github.youndie.kore.config.ConfigSchema
import io.github.youndie.kore.config.ConfigurationException
import io.github.youndie.kore.config.Environment
import io.github.youndie.kore.config.Origin
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The configuration refuses before it serves, and says everything that is wrong.
 *
 * These run on the JVM and on `linuxX64` from one source, which is the point: the schema is common
 * code and the one part of it that is *not* — enumerating the environment — is what the last test
 * here is about.
 */
class KeelConfigTest {
    private val complete =
        mapOf(
            "KEEL_DB_PATH" to "/var/lib/keel/keel.db",
        )

    /**
     * A fresh clone runs with nothing set, which is what the README promises.
     *
     * `KEEL_DB_PATH` was required until B-03, on the argument that a service inventing where its data
     * lives serves wrong data. That is right for a service whose database is somewhere else and wrong
     * for a template whose store is a file beside the process — and it made two things false: the
     * README's `./gradlew run`, and zavarnik's training run, which inherits the build's environment
     * and cannot be given one.
     */
    @Test
    fun `the schema reads with nothing set at all`() {
        val configuration = KeelConfig.SCHEMA.read(Environment.of(emptyMap()))

        assertEquals(8080, configuration[KeelConfig.PORT])
        assertEquals("keel.db", configuration[KeelConfig.DB_PATH])
        assertTrue(configuration.values().all { it.origin == Origin.DEFAULT })
    }

    /**
     * A required key still refuses — keel just no longer has one.
     *
     * The schema demonstrates three of the four shapes a clone will need; this is the fourth, kept as
     * a test rather than lost with the key. A real service's required key is a database address or a
     * credential, and this is what it does when a deployment forgets it.
     */
    @Test
    fun `a required key with no value stops the process instead of a route`() {
        val schema =
            ConfigSchema(
                prefix = "KEEL",
                keys = listOf(ConfigKey.required("DSN")),
            )

        val failure = assertFailsWith<ConfigurationException> { schema.read(Environment.of(emptyMap())) }

        assertEquals("KEEL_DSN", failure.problems.single().variable)
        assertContains(failure.problems.single().message, "is required and is not set")
    }

    /**
     * Every problem, not the first one.
     *
     * A process that fails on the first missing variable makes you fix them one at a time, one
     * restart each — and a deployment being configured for the first time has several. This is the
     * difference between one round trip and three.
     */
    @Test
    fun `every problem is reported rather than the first`() {
        val failure =
            assertFailsWith<ConfigurationException> {
                KeelConfig.SCHEMA.read(
                    Environment.of(
                        mapOf(
                            "KEEL_PORT" to "not-a-number",
                            "KEEL_TRACY_ENDPOINT" to "https://tracy.example",
                        ),
                    ),
                )
            }

        val named = failure.problems.map { it.variable }.toSet()
        assertEquals(
            setOf("KEEL_PORT", "KEEL_TRACY_KEY"),
            named,
            "the unparseable value and the half-set pair are two separate problems and both have to " +
                "arrive together, not one restart each",
        )
    }

    /**
     * Both or neither.
     *
     * One half of the pair set is a deployment that believes it is observed and is not — which is
     * the failure nobody notices, because nothing is missing from the logs that was ever there.
     */
    @Test
    fun `half of the observability pair is refused`() {
        val failure =
            assertFailsWith<ConfigurationException> {
                KeelConfig.SCHEMA.read(Environment.of(complete + ("KEEL_TRACY_ENDPOINT" to "https://tracy.example")))
            }

        assertEquals("KEEL_TRACY_KEY", failure.problems.single().variable)
        assertContains(failure.problems.single().message, "must be set together with")
    }

    /**
     * "The default was used" and "the environment agreed with the default" are different facts.
     *
     * The difference is what a renamed variable looks like, and `--print-config` prints it, so the
     * origin has to survive being read rather than being flattened into the value.
     */
    @Test
    fun `a default is used and is reported as a default`() {
        val configuration = KeelConfig.SCHEMA.read(Environment.of(complete))

        assertEquals(8080, configuration[KeelConfig.PORT])
        val port = configuration.values().single { it.variable == "KEEL_PORT" }
        assertEquals(Origin.DEFAULT, port.origin)

        val fromEnvironment = KeelConfig.SCHEMA.read(Environment.of(complete + ("KEEL_PORT" to "8080")))
        assertEquals(
            Origin.ENV,
            fromEnvironment.values().single { it.variable == "KEEL_PORT" }.origin,
            "the same value from the environment is a different fact from the default",
        )
    }

    /** A secret is masked by the declaration, not by a list of names somebody keeps in sync. */
    @Test
    fun `a secret is masked wherever the configuration is rendered`() {
        val configuration =
            KeelConfig.SCHEMA.read(
                Environment.of(
                    complete +
                        mapOf(
                            "KEEL_TRACY_ENDPOINT" to "https://tracy.example",
                            "KEEL_TRACY_KEY" to "the-actual-secret",
                        ),
                ),
            )

        val rendered = configuration.values().single { it.variable == "KEEL_TRACY_KEY" }.rendered
        assertTrue("the-actual-secret" !in rendered, "the secret was rendered verbatim: $rendered")
    }

    /**
     * A misspelled variable is named, together with the declared one it is probably a misspelling of.
     *
     * `KEEL_DB_PATHS` beside a declared `KEEL_DB_PATH` is a setting somebody wrote and the process is
     * ignoring. A message naming only the unknown half leaves the reader to find the other one.
     */
    @Test
    fun `an unknown variable under the prefix is refused and the near miss is named`() {
        val failure =
            assertFailsWith<ConfigurationException> {
                KeelConfig.SCHEMA.read(Environment.of(complete + ("KEEL_DB_PATHS" to "/var/lib/keel")))
            }

        val problem = failure.problems.single()
        assertEquals("KEEL_DB_PATHS", problem.variable)
        assertContains(
            problem.message,
            "KEEL_DB_PATH",
            message = "the declared name it is a misspelling of is not named",
        )
    }

    /**
     * Where the check cannot run it says so, and never "nothing found".
     *
     * The environment cannot be enumerated on macOS native, so the unknown-variable check is a
     * declared capability rather than a universal one. The asymmetry below is the whole guard: the
     * same unknown variable is a refusal where the names can be listed and silence where they cannot
     * — and silence is correct, because the alternative is a deployment reading "no unknown
     * variables" as evidence on a target that never looked.
     */
    @Test
    fun `an environment that cannot be listed does not report nothing found`() {
        val unlistable =
            Environment.unlistable(
                complete + ("KEEL_DB_PATHS" to "/var/lib/keel"),
                reason = "this target cannot enumerate the environment",
            )

        val configuration = KeelConfig.SCHEMA.read(unlistable)
        assertEquals("/var/lib/keel/keel.db", configuration[KeelConfig.DB_PATH])
    }
}
