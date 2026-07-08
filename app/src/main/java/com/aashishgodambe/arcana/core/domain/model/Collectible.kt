package com.aashishgodambe.arcana.core.domain.model

import java.time.LocalDate

/**
 * Domain model. Features depend on this sealed type, never on Room entities. Every call site
 * handles polymorphism via an exhaustive `when` — adding FigPin/PokemonCard later breaks
 * compilation at each site, forcing correct handling before the second category ships.
 */
sealed interface Collectible {
    val localId: Long
    val name: String
    val brand: String
    val imageUrl: String?
    val estimatedValueCents: Int
    val lastKnownValueCents: Int?
    val quantity: Int
    val itemCondition: String
    val packagingCondition: String
    val series: List<String>
    val productionTags: List<String>
    val dateAdded: LocalDate
    val pricePaidCents: Int?
    val storageLocation: String?
}

data class FunkoPop(
    override val localId: Long,
    override val name: String,
    override val brand: String,
    override val imageUrl: String?,
    override val estimatedValueCents: Int,
    override val lastKnownValueCents: Int?,
    override val quantity: Int,
    override val itemCondition: String,
    override val packagingCondition: String,
    override val series: List<String>,
    override val productionTags: List<String>,
    override val dateAdded: LocalDate,
    override val pricePaidCents: Int?,
    override val storageLocation: String?,
    val upc: String,
    val popNumber: String?,
    val exclusiveTo: String?,
    val isNftRedeemable: Boolean,
) : Collectible

// Future: data class FigPin(...) : Collectible / data class PokemonCard(...) : Collectible

/**
 * The value to display *now*: the latest tracked value if price sync has run, else the import estimate.
 * Every value surface (portfolio total, per-list rollup, item row, detail) reads this, so the numbers
 * agree and all move together with sync. Totals still multiply by quantity for duplicate-aware sums.
 */
val Collectible.currentValueCents: Int get() = lastKnownValueCents ?: estimatedValueCents
