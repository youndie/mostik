package io.github.youndie.keel.item

import kotlinx.serialization.Serializable

/**
 * The one entity, and it stays two fields.
 *
 * A starter whose example grows features stops being a starter: a third field here is the brief's red
 * list, at the smallest size that failure comes in.
 */
@Serializable
data class Item(
    val id: String,
    val name: String,
)
