package com.aashishgodambe.arcana.ui

/** Cents → a plain USD string, e.g. 59000 → "$590". */
fun formatUsd(cents: Int): String = "$" + "%,d".format(cents / 100)

/** Cents → a USD string with cents, e.g. 2450 → "$24.50". For market/listing prices. */
fun formatUsdCents(cents: Int): String = "$" + "%,.2f".format(cents / 100.0)
