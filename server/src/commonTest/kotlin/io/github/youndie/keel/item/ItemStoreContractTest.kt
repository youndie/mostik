package io.github.youndie.keel.item

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.sqlite.sqlite
import io.github.youndie.keel.keelDatabaseUrl
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The store against a real SQLite, on the JVM and on `linuxX64` from one source.
 *
 * **This suite is what settles D1.** `sqlx4k-sqlite` is two drivers behind one API — the Rust one on
 * Kotlin/Native, `org.xerial:sqlite-jdbc` on the JVM — and the question the brief left open was
 * whether one `commonMain` implementation can sit on both. It compiles here and it passes here, on
 * both, so keel carries one [SqliteItemStore] rather than the two the brief priced in.
 *
 * **A file, not `:memory:`.** The two halves disagree about in-memory databases: the JVM one refuses
 * a pool larger than one, because each connection would get a database of its own. A file is also
 * the shape the service runs in, which is the better reason of the two.
 *
 * **`runBlocking`, not `runTest`.** `runTest`'s clock is virtual, and a driver that bounds its wait
 * with a timeout in the caller's context sees the deadline fire instantly while the database is
 * still answering. On the JVM the same code lands on a real dispatcher and passes, so only the
 * native run would fail — which is the asymmetry this whole suite exists to catch.
 */
class ItemStoreContractTest {
    @Test
    fun `an item survives the round trip`() =
        withStore("round-trip") { store, _ ->
            assertEquals(emptyList(), store.all(), "the database is fresh, so it starts empty")

            val stored = store.add(Item(id = "a", name = "first"))

            assertEquals(Item("a", "first"), stored)
            assertEquals(listOf(Item("a", "first")), store.all())
        }

    @Test
    fun `items come back ordered by id rather than by insertion`() =
        withStore("ordering") { store, _ ->
            store.add(Item(id = "c", name = "third"))
            store.add(Item(id = "a", name = "first"))
            store.add(Item(id = "b", name = "second"))

            assertEquals(listOf("a", "b", "c"), store.all().map { it.id })
        }

    /**
     * The row is read back through raw SQL, not through the store that wrote it.
     *
     * A test that writes and reads the same wrong way passes on a diverged format. This one asks the
     * database directly, so a column written as the wrong type or under the wrong name is visible
     * here rather than in production.
     */
    @Test
    fun `the row lands in the table with the columns the schema declares`() =
        withStore("shape") { store, db ->
            store.add(Item(id = "shape", name = "read me raw"))

            val query =
                Statement
                    .create("SELECT id, name FROM items WHERE id = :id")
                    .bind("id", "shape")
            val row =
                db
                    .fetchAll(query)
                    .getOrThrow()
                    .rows
                    .singleOrNull()

            assertNotNull(row, "not found by its id — the value was stored under another name or type")
            assertEquals("read me raw", row.get("name").asString())

            // The positive control: this looked at that row rather than at an empty table.
            assertEquals(1, store.all().size)
        }

    /** The schema is applied more than once because a restart applies it again. */
    @Test
    fun `applying the schema twice is not an error`() =
        withStore("idempotent") { store, db ->
            itemsSchema().forEach { db.execute(it).getOrThrow() }
            store.add(Item(id = "still", name = "here"))
            assertEquals(1, store.all().size)
        }

    /**
     * A name with characters that cross a charset.
     *
     * On Kotlin/Native every rendered byte goes through glibc `iconv`, and the store is the other end
     * of that path: a value that survives SQLite and comes back changed is the same class of defect
     * as the `scratch` image that could not render a page.
     */
    @Test
    fun `a name that is not ascii survives the round trip`() =
        withStore("charset") { store, _ ->
            val name = "киль — の — ß"
            store.add(Item(id = "unicode", name = name))
            assertEquals(name, store.all().single().name)
        }

    /**
     * The rows are on disk, and a second driver on the same file sees them.
     *
     * **The test the rest of this suite cannot be.** Every other case here opens one driver, writes
     * through it and reads back through it — which passes just as well against a database that lives
     * only in that process. That is not hypothetical: the service shipped exactly that for the length
     * of one build, answering every request correctly and losing everything on restart.
     *
     * So this one closes the driver and opens another on the same path. If the file is not real, the
     * second driver finds an empty table.
     */
    @Test
    fun `the rows survive the driver being closed and reopened`() =
        runBlocking {
            val path = freshDatabase("reopen")

            openDriver(path).let { first ->
                itemsSchema().forEach { first.execute(it).getOrThrow() }
                SqliteItemStore(first).add(Item(id = "durable", name = "still here"))
                first.close()
            }

            val second = openDriver(path)
            try {
                assertEquals(
                    listOf(Item("durable", "still here")),
                    SqliteItemStore(second).all(),
                    "a second driver on the same file found nothing — the database was never on disk",
                )
            } finally {
                second.close()
            }
        }

    /**
     * The same id twice is the database's business, and it refuses.
     *
     * **The message is not asserted, and that is deliberate rather than lazy.** The two halves of
     * sqlx4k are different drivers, so they word a constraint violation differently; pinning the text
     * would be writing down one platform's phrasing as the contract and finding out on the other. The
     * failing is the contract. That the message is not blank is asserted, because a driver that
     * refuses without saying why is worth knowing about.
     */
    @Test
    fun `a duplicate id is refused by the primary key`() =
        withStore("duplicate") { store, _ ->
            store.add(Item(id = "same", name = "first"))

            val failure = assertFailsWith<Throwable> { store.add(Item(id = "same", name = "second")) }

            assertTrue(
                failure.message?.isNotBlank() == true,
                "the primary key refused, but said nothing about why",
            )
            assertEquals(listOf(Item("same", "first")), store.all(), "and the first row is untouched")
        }
}

/**
 * A database per test, deleted first rather than after.
 *
 * Deleted first because a run that failed leaves its file behind to be read, and the next run starts
 * from an empty one either way. The write-ahead log and the journal are separate files, and a stale
 * one beside a fresh database is a different database than the one this test means to open.
 */
private fun withStore(
    name: String,
    body: suspend (ItemStore, Driver) -> Unit,
) = runBlocking {
    val db = openDriver(freshDatabase(name))
    itemsSchema().forEach { db.execute(it).getOrThrow() }
    try {
        body(SqliteItemStore(db), db)
    } finally {
        db.close()
    }
}

/**
 * A database file per test, deleted first rather than after.
 *
 * Deleted first because a run that failed leaves its file behind to be read, and the next run starts
 * from an empty one either way. The write-ahead log and the journal are separate files, and a stale
 * one beside a fresh database is a different database than the one this test means to open.
 */
private fun freshDatabase(name: String): String {
    val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "keel-store-tests"
    FileSystem.SYSTEM.createDirectories(directory)
    val path = directory / "$name.db"
    listOf(path, "$path-wal".toPath(), "$path-shm".toPath(), "$path-journal".toPath())
        .forEach { FileSystem.SYSTEM.delete(it, mustExist = false) }
    return path.toString()
}

/** Opened the way the service opens it — [keelDatabaseUrl], not a URL this suite made up. */
private fun openDriver(path: String): Driver {
    val options =
        ConnectionPool.Options
            .builder()
            .maxConnections(2)
            .build()
    return sqlite(keelDatabaseUrl(path), options)
}
