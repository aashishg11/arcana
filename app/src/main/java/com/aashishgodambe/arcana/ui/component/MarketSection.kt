package com.aashishgodambe.arcana.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aashishgodambe.arcana.core.ai.model.ActiveListing
import com.aashishgodambe.arcana.core.ai.model.MarketContext
import com.aashishgodambe.arcana.ui.formatUsdCents
import com.aashishgodambe.arcana.ui.theme.ArcanaTheme
import com.aashishgodambe.arcana.ui.theme.Mono

/**
 * The live market card — median active listing + a few live listings + an untracked "Buy on eBay" link.
 * Shared between Detail and (later) Capture Review. Honest about what the source can see: eBay Browse
 * (and the Week-3 mock standing in for it) shows *active* listings only, so there's no "median sold"
 * line and the label says "median active".
 *
 * [buyUrl] is a provider-independent eBay *search* URL — no eBay API, no commission, works for any item.
 */
@Composable
fun MarketSection(market: MarketContext, buyUrl: String, modifier: Modifier = Modifier) {
    val c = ArcanaTheme.colors
    val uriHandler = LocalUriHandler.current
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface)
            .border(1.dp, c.hairline, RoundedCornerShape(18.dp)).padding(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text("Median active listing", color = c.textDim, fontSize = 12.sp)
            ArcanaChip("eBay Browse", ChipStyle.Plain)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            market.medianActivePriceCents?.let { formatUsdCents(it) } ?: "—",
            fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 28.sp, color = c.text,
        )

        market.activeListings.take(3).forEach { listing ->
            ListingRow(listing, onView = { uriHandler.openUri(listing.ebayUrl) })
        }

        Spacer(Modifier.height(14.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
                .border(1.dp, c.iris, RoundedCornerShape(13.dp))
                .clickable { uriHandler.openUri(buyUrl) }.padding(vertical = 13.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Buy on eBay ↗", color = c.iris, fontWeight = FontWeight.Medium, fontSize = 14.sp)
        }
    }
}

/** How the total's shipping is composed: folded in, free, or extra-and-calculated. */
private fun shippingNote(listing: ActiveListing): String = when (listing.shippingCents) {
    null -> "+ shipping"        // eBay calculates it per location — the total shown is the item price only
    0 -> "free shipping"
    else -> "incl. shipping"    // the total already folds in the fixed shipping cost
}

@Composable
private fun ListingRow(listing: ActiveListing, onView: () -> Unit) {
    val c = ArcanaTheme.colors
    HorizontalDivider(color = c.hairline, modifier = Modifier.padding(top = 11.dp))
    Row(
        Modifier.fillMaxWidth().padding(top = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(9.dp)).background(c.elevated).border(1.dp, c.hairline, RoundedCornerShape(9.dp)))
        Column(Modifier.weight(1f)) {
            Text(listing.title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            listing.sellerRating?.let {
                Text("seller ${"%.1f".format(it)}%", fontFamily = Mono, fontSize = 11.sp, color = c.textFaint)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(formatUsdCents(listing.totalCents), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = c.text)
            Text(shippingNote(listing), fontFamily = Mono, fontSize = 9.sp, color = c.textFaint)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "View ↗", fontFamily = Mono, fontSize = 11.sp, color = c.iris,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(c.irisSoft).clickable(onClick = onView).padding(horizontal = 9.dp, vertical = 5.dp),
        )
    }
}
