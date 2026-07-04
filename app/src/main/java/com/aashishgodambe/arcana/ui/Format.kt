package com.aashishgodambe.arcana.ui

/** Cents → a plain USD string, e.g. 59000 → "$590". */
fun formatUsd(cents: Int): String = "$" + "%,d".format(cents / 100)
