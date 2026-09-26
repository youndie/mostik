package io.github.youndie.keel.item

/**
 * What the route tests run against.
 *
 * A fake rather than a mock: the compiler watches the contract, so changing [ItemStore] stops this
 * compiling and shows which tests went stale — and it holds state, so what survived a run is read
 * off [stored] instead of asserted with "was this called". It also runs on both targets, which a
 * mocking library would not.
 */
class InMemoryItemStore(
    initial: List<Item> = emptyList(),
) : ItemStore {
    val stored = initial.toMutableList()

    override suspend fun all(): List<Item> = stored.sortedBy { it.id }

    override suspend fun add(item: Item): Item {
        stored += item
        return item
    }
}
