package io.github.youndie.keel.item

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement

/**
 * Where items live.
 *
 * **The port exists for the tests, not for the targets** — and that is the thing worth saying,
 * because the brief expected the opposite. It priced a runnable JVM at two implementations behind
 * this interface: sqlx4k on native, Exposed or JDBC on the JVM. That is not needed. `sqlx4k-sqlite`
 * publishes both halves itself — the Rust driver on Kotlin/Native and `org.xerial:sqlite-jdbc` on
 * the JVM — so [SqliteItemStore] below is one implementation in `commonMain` that compiles and runs
 * on both. See `docs/research/research-architecture.md` §1.6 and D1.
 *
 * What the port still buys is what the `ktor-server-feature` skill actually asks for: the route's
 * tests run against an in-memory implementation and say nothing about SQL, while the SQL is tested
 * against a real database file. Two different questions, two different suites.
 */
interface ItemStore {
    suspend fun all(): List<Item>

    /** Returns what was stored, which is not always what was passed — the store is the authority. */
    suspend fun add(item: Item): Item
}

/**
 * The statements that create the table, as text the service runs itself.
 *
 * Not a migration framework: a template that shipped one would be choosing a clone's migration story
 * for it, and that is a decision a real service makes on its second table rather than its first.
 * `CREATE TABLE IF NOT EXISTS` is honest about being the whole of it.
 */
fun itemsSchema(): List<String> =
    listOf(
        """
        CREATE TABLE IF NOT EXISTS items (
            id TEXT PRIMARY KEY,
            name TEXT NOT NULL
        );
        """.trimIndent(),
    )

/**
 * Items on SQLite, through sqlx4k — one implementation, both targets.
 *
 * Takes a [Driver] the service opened rather than opening one: where the database file is and how
 * many connections there are is the service's decision, and a store that opened its own could not be
 * given a different one by a test.
 *
 * **Named parameters, never string interpolation.** `Statement.create(...).bind(...)` is the whole
 * of the injection story here, and the reason to keep it even for an `id` the service generated is
 * that the next person copies this file.
 */
class SqliteItemStore(
    private val db: Driver,
) : ItemStore {
    override suspend fun all(): List<Item> =
        db
            .fetchAll(Statement.create("SELECT id, name FROM items ORDER BY id"))
            .getOrThrow()
            .rows
            .map { row -> Item(id = row.get("id").asString(), name = row.get("name").asString()) }

    override suspend fun add(item: Item): Item {
        db
            .execute(
                Statement
                    .create("INSERT INTO items (id, name) VALUES (:id, :name)")
                    .bind("id", item.id)
                    .bind("name", item.name),
            ).getOrThrow()
        return item
    }
}
