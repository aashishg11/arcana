package com.aashishgodambe.arcana.core.data.database.entity

/** A named grouping (the user's HobbyDB "List Name") with its item count and total value. */
data class CollectionGroup(
    val name: String,
    val itemCount: Int,
    val valueCents: Int,
)
