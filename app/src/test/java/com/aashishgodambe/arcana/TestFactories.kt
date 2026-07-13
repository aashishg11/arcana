package com.aashishgodambe.arcana

import com.aashishgodambe.arcana.core.domain.model.FunkoPop
import java.time.LocalDate

/** Minimal [FunkoPop] builder for device-free tests. */
fun testFunko(
    id: Long,
    name: String = "Item $id",
    valueCents: Int = 10_000,
    quantity: Int = 1,
    nft: Boolean = false,
    popNumber: String? = null,
    series: List<String> = emptyList(),
    upc: String = "0",
) = FunkoPop(
    localId = id,
    name = name,
    brand = "Funko",
    imageUrl = null,
    estimatedValueCents = valueCents,
    lastKnownValueCents = null,
    quantity = quantity,
    itemCondition = "Mint",
    packagingCondition = "Mint",
    series = series,
    productionTags = emptyList(),
    dateAdded = LocalDate.of(2023, 1, 1),
    pricePaidCents = null,
    storageLocation = null,
    upc = upc,
    popNumber = popNumber,
    exclusiveTo = null,
    isNftRedeemable = nft,
)
